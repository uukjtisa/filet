package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.media.JpegOrientation
import dev.niccc2007.filet.media.upright
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Images: look at one, then change it a bit.
 *
 * Round 6 added the editing half, and the list is deliberately short - crop, draw, rotate,
 * flip, invert, greyscale, resize. An image editor inside a file manager competes badly with
 * a real one, and the cases that actually come up on a phone are "straighten this receipt",
 * "cut the useful part out of this screenshot" and "scribble an arrow on it before sending".
 *
 * Every pixel operation lives in `ImageOps.kt` and is tested there. This file owns the decode,
 * the gestures, the undo stack and the encode on the way out, and nothing else.
 */

/**
 * The longest edge a working bitmap may have.
 *
 * An edit holds the source buffer, its pixels, the result's pixels and the result bitmap at
 * the same moment, so the working size is multiplied by four before anything is freed. At this
 * cap that peaks around 90 MB on a square image and far less on a normal photo; at full 108 MP
 * it would be an immediate kill.
 */
private const val EDIT_MAX_EDGE = 2400

private const val UNDO_DEPTH = 5

private enum class Tool { NONE, CROP, DRAW, RESIZE, ROTATE, TEXT }

/** The bottom bar's two menus. One value, so opening one closes the other. */
private enum class PicMenu { EDIT, MORE }

/**
 * @param placeholder drawn while the picture decodes, in place of the bare spinner. A run passes
 *   the file's poster, so arriving at a photo by swiping goes from soft to sharp.
 */
@Composable
fun ImageViewerScreen(vm: BrowserViewModel, node: VNode, placeholder: (@Composable () -> Unit)? = null) {
    val grey by vm.prefs.viewerGrey.collectAsState()
    dev.niccc2007.filet.ui.theme.DarkGround(ViewerGround.of(grey)) {
        ImageViewerContent(vm, node, placeholder, grey)
    }
}

@Composable
private fun ImageViewerContent(
    vm: BrowserViewModel,
    node: VNode,
    placeholder: (@Composable () -> Unit)?,
    grey: Boolean,
) {
    val colors = Filet.colors
    val scope = rememberCoroutineScope()

    var image by remember(node.path) { mutableStateOf<Bitmap?>(null) }
    var sourceSize by remember(node.path) { mutableStateOf("") }
    // The file's own size, the way up it is shown, for saying when a save would be smaller.
    var sourceDims by remember(node.path) { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var editing by remember(node.path) { mutableStateOf(false) }
    var tool by remember(node.path) { mutableStateOf(Tool.NONE) }
    var busy by remember(node.path) { mutableStateOf(false) }
    var dirty by remember(node.path) { mutableStateOf(false) }
    var exitArmed by remember(node.path) { mutableStateOf(false) }
    var menu by remember(node.path) { mutableStateOf<PicMenu?>(null) }

    // The controls retract while the picture is being looked at, and a tap brings them back - the
    // same rule as the video viewer, decided in the same place. See ViewerChrome.
    var chromeVisible by remember(node.path) { mutableStateOf(true) }
    var chromeTouchedAt by remember(node.path) { mutableLongStateOf(System.currentTimeMillis()) }

    // Bounded, and the oldest is recycled as it falls off the end: an unbounded history of
    // 20 MP bitmaps is an out-of-memory kill two or three taps in.
    val history = remember(node.path) { mutableStateListOf<Bitmap>() }

    // One value rather than three loose floats, so a gesture handler transforms it instead of
    // reading pieces of it. See ZoomState.kt for why that distinction is the whole bug.
    var view by remember(node.path) { mutableStateOf(ZoomView.NONE) }

    fun resetView() { view = ZoomView.NONE }

    // The viewer's own turn, in quarter turns clockwise, counted up without wrapping so every
    // turn animates the short way round. A view, like the zoom: the file changes only when the
    // turn is saved. So a turn holds nothing - swiping on to the next picture still works, and
    // coming back finds it the way up the file is.
    //
    // Bug identified here: Rotate used to open the editor with a quarter turn already made.
    // That made a turn an unsaved EDIT, the editor's controls replaced the viewer's, and an
    // unsaved edit holds the run's swipe - so one tap on Rotate also stopped the swipe to the
    // next photo, and leaving needed a second Back to discard what looked like nothing.
    var quarters by remember(node.path) { mutableIntStateOf(0) }
    // The turn last saved, so the Save action goes away once it has done its job.
    var savedQuarters by remember(node.path) { mutableIntStateOf(0) }
    val turnDegrees = remember(node.path) { Animatable(0f) }
    LaunchedEffect(quarters) {
        // Zero only when the turn has become part of the picture - saved over the file, or
        // carried into the editor - and by then the picture under it has already turned, so it
        // snaps rather than spinning back.
        if (quarters == 0) turnDegrees.snapTo(0f) else turnDegrees.animateTo(quarters * 90f, tween(220))
    }
    val turnPending = Math.floorMod(quarters, 4) != 0 && quarters != savedQuarters
    // Bumped to read the file again after a turn is saved over it.
    var revision by remember(node.path) { mutableIntStateOf(0) }

    fun keep(previous: Bitmap) {
        history.add(previous)
        while (history.size > UNDO_DEPTH) history.removeAt(0).recycle()
        dirty = true
        exitArmed = false
    }

    /** Run a pixel operation off the main thread and swap the result in. */
    fun runOp(op: (Pixels) -> Pixels) {
        val current = image ?: return
        scope.launch {
            busy = true
            val next = withContext(Dispatchers.Default) {
                runCatching { op(current.toPixels()).toBitmap() }.getOrNull()
            }
            busy = false
            if (next == null) {
                vm.toast("That edit needs more memory than is free right now.")
                return@launch
            }
            keep(current)
            image = next
            resetView()
        }
    }

    /**
     * The viewer's turn made part of the picture, for the editor: what is edited is what was on
     * screen. An edit like any other from there - Undo takes it back off.
     */
    fun bakeTurn() {
        val q = Math.floorMod(quarters, 4)
        val current = image
        if (q == 0 || current == null) { quarters = 0; savedQuarters = 0; return }
        val turn = when (q) { 1 -> Turn.RIGHT; 2 -> Turn.HALF; else -> Turn.LEFT }
        scope.launch {
            busy = true
            val next = withContext(Dispatchers.Default) {
                runCatching { current.toPixels().rotated(turn).toBitmap() }.getOrNull()
            }
            busy = false
            if (next == null) {
                vm.toast("That edit needs more memory than is free right now.")
                return@launch
            }
            keep(current)
            image = next
            quarters = 0
            savedQuarters = 0
        }
    }

    LaunchedEffect(node.path, revision) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // The orientation comes from the same open as the size: the front of the file is
                // read once, looked at, and put back in front of the rest. See JpegOrientation for
                // why BitmapFactory on its own showed portrait photos lying on their side.
                val orientation = vm.openRead(node).use { input ->
                    val head = JpegOrientation.head(input)
                    BitmapFactory.decodeStream(java.io.SequenceInputStream(head.inputStream(), input), null, bounds)
                    JpegOrientation.read(head)
                }
                val w = bounds.outWidth
                val h = bounds.outHeight
                var sample = 1
                while (w / sample > EDIT_MAX_EDGE || h / sample > EDIT_MAX_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    // ARGB_8888 explicitly. A hardware bitmap has no readable pixels at all and
                    // getPixels on one throws, which would make every edit fail on newer phones
                    // and nowhere else.
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = vm.openRead(node).use { BitmapFactory.decodeStream(it, null, opts) }?.upright(orientation)
                val sideways = JpegOrientation.transformOf(orientation).sideways
                Triple(bmp, if (sideways) h else w, if (sideways) w else h)
            }
        }.onSuccess { (bmp, sw, sh) ->
            if (bmp == null) error = "This is not an image Android can decode."
            // A read after a save over the file: the turn is in it now. Swapped in the same
            // frame as the picture, so the new one is never shown turned twice.
            image = bmp
            quarters = 0
            savedQuarters = 0
            sourceSize = "$sw x $sh"
            sourceDims = sw to sh
        }.onFailure { error = it.message ?: "Could not read this image." }
    }

    DisposableEffect(node.path) {
        onDispose {
            image?.recycle()
            image = null
            history.forEach { it.recycle() }
            history.clear()
        }
    }

    val shown = image
    val dims = if (shown == null) sourceSize else "${shown.width} x ${shown.height}"

    // Editing holds the controls open: they ARE the tools, and tools that retract mid-edit are
    // worse than no tools. Looking at a picture holds nothing.
    //
    // `rememberUpdatedState` rather than a plain val, because the loop below is launched once and
    // keyed on the file - anything it closes over is frozen at the composition that started it.
    // That is the trap this file already documents for `pointerInput`, and here it would have let
    // the tools vanish in the middle of an edit begun after the viewer opened.
    //
    // An open menu holds them too: it is drawn from the bar, and the bar retracting takes it away
    // in the middle of a choice. So does any dialogue at all - the QR details are drawn from the
    // chip in the top bar and went with it; see OverlayHold.
    val overlayOpen = dev.niccc2007.filet.ui.dialogs.OverlayHold.held
    val holdingChrome = rememberUpdatedState(editing || tool != Tool.NONE || busy || menu != null || overlayOpen)
    // Closing one restarts the idle clock. Otherwise a dialogue read for longer than the idle
    // time lets the controls vanish the instant it closes, which reads as the close button
    // having taken them too.
    LaunchedEffect(overlayOpen) { if (!overlayOpen) chromeTouchedAt = System.currentTimeMillis() }
    LaunchedEffect(node.path) {
        while (true) {
            kotlinx.coroutines.delay(400)
            if (chromeVisible &&
                ViewerChrome.shouldRetract(
                    System.currentTimeMillis(), chromeTouchedAt, holdingChrome.value,
                )
            ) {
                chromeVisible = false
            }
        }
    }

    /** A tap on the picture: show the controls, or put them away again. */
    fun wakeChrome() {
        chromeVisible = !chromeVisible
        chromeTouchedAt = System.currentTimeMillis()
    }

    // An error or a picture that has not arrived keeps the controls up: there is nothing to look
    // at yet, and the way out is on the bar.
    val chromeUp = chromeVisible || shown == null || error != null

    // In a run, a swipe moves to the next photo - except while that would cost something: an
    // edit in progress or not yet saved, or a zoomed view whose drags are panning it.
    HoldRunSwipe(editing || tool != Tool.NONE || busy || dirty || view.scale > ZOOMED)

    // The picture gets the whole screen, system bars included.
    ImmersiveViewer()

    // A Box rather than a Column, and that IS the fullscreen change. Stacked vertically, the
    // bar owned a strip of the screen whether or not anybody wanted it and the picture got
    // what was left - so hiding the bar RESIZED the picture, which is a worse distraction than
    // the bar was. Overlaid, the picture is the whole screen at all times and the controls
    // come and go on top of it.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> Text(
                    error!!, fontSize = 13.sp, color = colors.fg2,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                shown == null -> {
                    placeholder?.invoke()
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp,
                    )
                }

                tool == Tool.CROP -> CropSurface(shown, busy) { rect ->
                    tool = Tool.NONE
                    if (rect != null) runOp { it.cropped(rect) }
                }

                tool == Tool.DRAW -> DrawSurface(shown, busy) { strokes ->
                    tool = Tool.NONE
                    if (strokes.isNotEmpty()) {
                        scope.launch {
                            busy = true
                            val next = withContext(Dispatchers.Default) {
                                runCatching { bakeStrokes(shown, strokes) }.getOrNull()
                            }
                            busy = false
                            if (next == null) { vm.toast("Not enough memory to apply that."); return@launch }
                            keep(shown)
                            image = next
                        }
                    }
                }

                tool == Tool.ROTATE -> RotateSurface(shown, busy) { angle ->
                    tool = Tool.NONE
                    if (angle != 0f) runOp { it.rotatedBy(angle) }
                }

                tool == Tool.TEXT -> TextSurface(shown, busy) { items ->
                    tool = Tool.NONE
                    if (items.isNotEmpty()) {
                        scope.launch {
                            busy = true
                            val next = withContext(Dispatchers.Default) {
                                runCatching { bakeText(shown, items) }.getOrNull()
                            }
                            busy = false
                            if (next == null) { vm.toast("Not enough memory to apply that."); return@launch }
                            keep(shown)
                            image = next
                        }
                    }
                }

                tool == Tool.RESIZE -> ResizeSurface(shown, busy) { w, h ->
                    tool = Tool.NONE
                    if (w != shown.width || h != shown.height) runOp { it.stretched(w, h) }
                }

                else -> ZoomableImage(
                    shown, node.name, view,
                    // Read in the draw phase, so the turn animates without recomposing the screen.
                    turn = { if (quarters == 0) 0f else turnDegrees.value },
                    onTap = { wakeChrome() },
                ) { view = it }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(30.dp), strokeWidth = 2.5.dp,
                )
            }
        }

        AnimatedVisibility(
            visible = chromeUp,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerBar(
                title = node.name,
                subtitle = listOfNotNull(
                    dims.takeIf { it.isNotEmpty() },
                    when {
                        dirty -> "edited, not saved"
                        turnPending -> "turned, not saved"
                        else -> humanSize(node.size)
                    },
                ).joinToString("  ·  "),
                onClose = {
                    // Walking away from unsaved edits is a real loss, so the first back arms it and
                    // says so. One warning, not a dialog: a modal here would be in the way of the
                    // common case, which is leaving a picture you only looked at.
                    when {
                        !dirty -> vm.closeHandler()
                        exitArmed -> vm.closeHandler()
                        else -> {
                            exitArmed = true
                            vm.toast("Edits are not saved. Tap back again to discard them.")
                        }
                    }
                },
            ) {
                if (editing) {
                    ViewerAction(FiletIcons.Undo, "Undo", enabled = history.isNotEmpty() && !busy) {
                        val previous = history.removeLastOrNull() ?: return@ViewerAction
                        image?.recycle()
                        image = previous
                        dirty = history.isNotEmpty()
                        resetView()
                    }
                    val blocked = remember(node.path) { vm.writeBlockReason(node.path.parent) }
                    /** Encode what is on screen and hand it to the save, [to] null meaning over the original. */
                    fun saveEdit(to: dev.niccc2007.filet.vfs.VPath?) {
                        val bmp = shown ?: return
                        scope.launch {
                            busy = true
                            val encoded = withContext(Dispatchers.Default) {
                                runCatching { encode(bmp, node.extension) }.getOrNull()
                            }
                            busy = false
                            if (encoded == null) { vm.toast("Could not encode the edited image."); return@launch }
                            vm.saveEditedImage(node, encoded.bytes, encoded.extension, to) {
                                dirty = false
                                exitArmed = false
                            }
                        }
                    }
                    val bmp = shown
                    val makes = if (bmp == null) node.extension else encodedExtension(bmp, node.extension)
                    SaveChoice(
                        label = "Save",
                        enabled = bmp != null && !busy,
                        replaceDetail = if (bmp == null || blocked != null ||
                            makes !in dev.niccc2007.filet.pick.PickName.sameKind(node.extension)
                        ) {
                            null
                        } else {
                            editSizeNote(bmp.width, bmp.height, sourceDims, jpeg = makes != "png")
                        },
                        newDetail = newFileNote(node.extension, makes),
                        onReplace = { saveEdit(null) },
                        onNew = { vm.chooseSaveAs(node, makes) { saveEdit(it) } },
                    )
                    ViewerAction(FiletIcons.Close, "Done editing") { editing = false; tool = Tool.NONE }
                } else {
                    // A turn is saved from up here, where the editor's Save is: the one action
                    // that only exists while there is something to save.
                    if (turnPending) {
                        val blocked = remember(node.path) { vm.writeBlockReason(node.path.parent) }
                        val makes = PictureTurn.newFormatFor(node.extension)
                        /** [to] null saves over the original. */
                        fun saveTurn(to: dev.niccc2007.filet.vfs.VPath?) {
                            val q = quarters
                            busy = true
                            vm.saveTurnedPicture(node, Math.floorMod(q, 4), to) { ok ->
                                busy = false
                                if (ok) {
                                    savedQuarters = q
                                    if (to == null) revision++
                                }
                            }
                        }
                        SaveChoice(
                            label = "Save the turn",
                            enabled = shown != null && !busy,
                            replaceDetail = if (blocked != null) {
                                null
                            } else {
                                when (PictureTurn.replaceFor(node.extension)) {
                                    PictureTurn.Replace.TAG -> "Lossless: only which way up it is changes"
                                    PictureTurn.Replace.REWRITE -> "Written again at full size, without loss"
                                    PictureTurn.Replace.NONE -> null
                                }
                            },
                            newDetail = newFileNote(node.extension, makes),
                            onReplace = { saveTurn(null) },
                            onNew = { vm.chooseSaveAs(node, makes) { saveTurn(it) } },
                        )
                    }
                    // Only what describes the picture is up here, beside its name: the QR chip
                    // when it holds a code, and Details. What acts on it is along the bottom.
                    dev.niccc2007.filet.qr.QrChip(node, vm, Modifier.padding(end = 4.dp))
                    ViewerAction(FiletIcons.Info, "Details") { vm.showProperties(node) }
                }
            }
        }

        val runPage = LocalViewerRun.current
        AnimatedVisibility(
            visible = chromeUp && !editing,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PictureActions(
                ready = shown != null && !busy,
                canDelete = remember(node.path) { vm.writeBlockReason(node.path.parent) == null },
                zoomed = view.scale > ZOOMED,
                menu = menu,
                onMenu = {
                    menu = it
                    // Closing a menu is a touch: the bar does not vanish the moment it shuts.
                    chromeTouchedAt = System.currentTimeMillis()
                },
                onShare = { vm.share(node) },
                // Turns the view, not the file; see `quarters`. Saving it is offered at the top.
                onRotate = {
                    quarters += 1
                    resetView()
                    chromeTouchedAt = System.currentTimeMillis()
                },
                onEdit = {
                    editing = true
                    resetView()
                    bakeTurn()
                },
                onOpenWith = { vm.openExternally(node, force = true) },
                runKinds = { RunKindsRow(vm, node) { menu = null } },
                onDelete = {
                    vm.confirmDeleteOf(listOf(node)) {
                        if (runPage != null) runPage.drop() else vm.closeHandler()
                    }
                },
                onFit = { resetView() },
                grey = grey,
                onGround = { vm.prefs.setViewerGrey(!grey) },
                onReveal = {
                    // Out of every viewer, not back one: the folder is where this is going.
                    vm.leaveHandlers()
                    vm.revealInFolder(node)
                },
            )
        }

        AnimatedVisibility(
            visible = chromeUp && editing && tool == Tool.NONE,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
                ToolStrip(
                    enabled = shown != null && !busy,
                    onCrop = { tool = Tool.CROP },
                    onDraw = { tool = Tool.DRAW },
                    onResize = { tool = Tool.RESIZE },
                    onRotate = { tool = Tool.ROTATE },
                    onText = { tool = Tool.TEXT },
                    onOp = ::runOp,
                )
        }
    }
}

// ── the plain viewer ──

@Composable
private fun ZoomableImage(
    bitmap: Bitmap,
    label: String,
    view: ZoomView,
    turn: () -> Float = { 0f },
    onTap: () -> Unit,
    onView: (ZoomView) -> Unit,
) {
    var box by remember { mutableStateOf(Size.Zero) }

    // Bug identified here: pinch to zoom did nothing.
    //
    // `Modifier.pointerInput(key)` launches a coroutine that restarts ONLY when the key
    // changes, and the key here is the bitmap. Anything the gesture block closes over is
    // therefore frozen at the value it had when the image was opened. The previous version
    // read `scale` directly, so every pinch frame computed `1f * zoom` - and `zoom` is a
    // per-frame ratio of roughly 1.02, so the view never grew and the gesture looked dead.
    //
    // `rememberUpdatedState` is the supported way to reach current state from a long-lived
    // effect: the block reads a holder whose value is swapped on each composition, rather
    // than a copy taken once. Same reason the double tap now toggles back out correctly.
    val live = rememberUpdatedState(view)
    val emit = rememberUpdatedState(onView)
    val tapped = rememberUpdatedState(onTap)
    // In a run, one finger on an unzoomed picture is a swipe to the next one, so it is left for
    // the pager. Two fingers are a pinch, and a zoomed picture's drags pan it, here or not.
    val inRun = LocalViewerRun.current != null

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap) {
                detectTapGestures(
                    // One tap shows or hides the controls. It is the only way back to them once
                    // they have retracted, so it is the whole picture rather than any part of it.
                    onTap = { tapped.value() },
                    onDoubleTap = { at ->
                        emit.value(live.value.doubleTapped(at.x, at.y, box.width, box.height))
                    },
                )
            }
            // Last in the chain so it wins the Main pass over the tap detector above, the same
            // ordering the row drag-and-drop needed in round 3.
            .pointerInput(bitmap, inRun) {
                detectZoomGestures(
                    passThrough = { fingers -> inRun && fingers < 2 && live.value.scale <= ZOOMED },
                ) { centroid, pan, zoom ->
                    // The centroid is the whole fix for a pinch at a corner running away: it
                    // was being discarded, so every pinch scaled about the middle of the
                    // viewport and the picture slid out from under the fingers.
                    emit.value(
                        live.value.pinched(
                            zoom, pan.x, pan.y,
                            centroid.x, centroid.y,
                            box.width, box.height,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Turned about the middle and scaled to fit again, then zoomed and panned in
                // screen space - so a pan still follows the finger on a turned picture.
                val degrees = turn()
                val fit = turnFit(box.width, box.height, bitmap.width.toFloat(), bitmap.height.toFloat(), degrees)
                scaleX = view.scale * fit; scaleY = view.scale * fit
                rotationZ = degrees
                translationX = view.offsetX; translationY = view.offsetY
            },
        )
    }
}

/** Past this scale a picture counts as zoomed: its drags pan it and a run's swipe waits. */
private const val ZOOMED = 1.01f

/**
 * Compose's `detectTransformGestures`, with one difference: a gesture [passThrough] says is
 * not this picture's is left alone - not counted, not consumed - so a pager around it can take
 * it. In a run that is one finger on an unzoomed picture, which is a swipe to the next one.
 *
 * Asked on every event rather than once per gesture, so a second finger landing turns a swipe
 * that has not started yet into a pinch.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectZoomGestures(
    passThrough: (fingers: Int) -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    var zoom = 1f
    var pan = Offset.Zero
    var pastTouchSlop = false
    val touchSlop = viewConfiguration.touchSlop
    awaitFirstDown(requireUnconsumed = false)
    do {
        val event = awaitPointerEvent()
        val canceled = event.changes.any { it.isConsumed }
        val fingers = event.changes.count { it.pressed }
        if (!canceled && !(passThrough(fingers) && !pastTouchSlop)) {
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastTouchSlop) {
                zoom *= zoomChange
                pan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                if (abs(1 - zoom) * centroidSize > touchSlop || pan.getDistance() > touchSlop) pastTouchSlop = true
            }
            if (pastTouchSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (zoomChange != 1f || panChange != Offset.Zero) onGesture(centroid, panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    } while (!canceled && event.changes.any { it.pressed })
}

// ── crop ──

private enum class Grab { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, INSIDE }

private fun grabFor(
    x: Float,
    y: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    tolerance: Float,
): Grab {
    fun near(a: Float, b: Float) = abs(a - b) <= tolerance
    return when {
        near(x, left) && near(y, top) -> Grab.TOP_LEFT
        near(x, right) && near(y, top) -> Grab.TOP_RIGHT
        near(x, left) && near(y, bottom) -> Grab.BOTTOM_LEFT
        near(x, right) && near(y, bottom) -> Grab.BOTTOM_RIGHT
        x > left && x < right && y > top && y < bottom -> Grab.INSIDE
        else -> Grab.NONE
    }
}

/**
 * A crop frame over the picture.
 *
 * The frame starts at the middle rather than at nothing, because an empty overlay gives no
 * hint that there is anything to drag. Corners move on their own; the inside moves all four.
 *
 * @param onDone the rectangle to crop to, or null if the user backed out - so cancelling costs
 *   nothing rather than running a full-image crop that happens to be a no-op.
 */
@Composable
private fun CropSurface(bitmap: Bitmap, busy: Boolean, onDone: (CropRect?) -> Unit) {
    val colors = Filet.colors
    var box by remember { mutableStateOf(Size.Zero) }
    // Held in IMAGE pixels, so the frame survives a rotation of the screen and maps onto the
    // buffer at apply time with no second conversion to get wrong.
    var left by remember(bitmap) { mutableFloatStateOf(bitmap.width * 0.15f) }
    var top by remember(bitmap) { mutableFloatStateOf(bitmap.height * 0.15f) }
    var right by remember(bitmap) { mutableFloatStateOf(bitmap.width * 0.85f) }
    var bottom by remember(bitmap) { mutableFloatStateOf(bitmap.height * 0.85f) }
    var grabbed by remember(bitmap) { mutableStateOf(Grab.NONE) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                            grabbed = if (!fit.ready) Grab.NONE else grabFor(
                                fit.toImageX(at.x, bitmap.width),
                                fit.toImageY(at.y, bitmap.height),
                                left, top, right, bottom,
                                // A fingertip-sized target converted into image pixels. A
                                // handle measured in image pixels alone would be unusable on a
                                // 4000px photo and enormous on a 200px icon.
                                tolerance = 24.dp.toPx() / fit.scale,
                            )
                        },
                        onDragEnd = { grabbed = Grab.NONE },
                        onDragCancel = { grabbed = Grab.NONE },
                    ) { change, drag ->
                        change.consume()
                        val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                        if (fit.ready) {
                            val dx = drag.x / fit.scale
                            val dy = drag.y / fit.scale
                            val w = bitmap.width.toFloat()
                            val h = bitmap.height.toFloat()
                            when (grabbed) {
                                Grab.TOP_LEFT -> {
                                    left = (left + dx).coerceIn(0f, right - 1f)
                                    top = (top + dy).coerceIn(0f, bottom - 1f)
                                }
                                Grab.TOP_RIGHT -> {
                                    right = (right + dx).coerceIn(left + 1f, w)
                                    top = (top + dy).coerceIn(0f, bottom - 1f)
                                }
                                Grab.BOTTOM_LEFT -> {
                                    left = (left + dx).coerceIn(0f, right - 1f)
                                    bottom = (bottom + dy).coerceIn(top + 1f, h)
                                }
                                Grab.BOTTOM_RIGHT -> {
                                    right = (right + dx).coerceIn(left + 1f, w)
                                    bottom = (bottom + dy).coerceIn(top + 1f, h)
                                }
                                Grab.INSIDE -> {
                                    // Clamp the shift, not each edge: clamping the edges lets
                                    // the frame silently shrink when it reaches a side.
                                    val sx = dx.coerceIn(-left, w - right)
                                    val sy = dy.coerceIn(-top, h - bottom)
                                    left += sx; right += sx; top += sy; bottom += sy
                                }
                                Grab.NONE -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val fit = fitInside(size.width, size.height, bitmap.width, bitmap.height)
                if (fit.ready) {
                    val l = fit.toViewX(left)
                    val t = fit.toViewY(top)
                    val r = fit.toViewX(right)
                    val b = fit.toViewY(bottom)
                    // Four bands rather than a hole punched in a saved layer: no layer to
                    // allocate on every frame of a drag, and it reads identically.
                    val veil = Color.Black.copy(alpha = 0.55f)
                    drawRect(veil, Offset(0f, 0f), Size(size.width, t))
                    drawRect(veil, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(veil, Offset(0f, t), Size(l, b - t))
                    drawRect(veil, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(
                        colors.accent, Offset(l, t), Size(r - l, b - t),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    // Thirds, because straightening something against a grid is most of what a
                    // crop on a phone is actually for.
                    val third = Color.White.copy(alpha = 0.22f)
                    for (i in 1..2) {
                        val x = l + (r - l) * i / 3f
                        val y = t + (b - t) * i / 3f
                        drawLine(third, Offset(x, t), Offset(x, b), strokeWidth = 1.dp.toPx())
                        drawLine(third, Offset(l, y), Offset(r, y), strokeWidth = 1.dp.toPx())
                    }
                    val handle = 9.dp.toPx()
                    for (p in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                        drawCircle(colors.accent, handle, p)
                        drawCircle(Color.White, handle * 0.42f, p)
                    }
                }
            }
        }
        val rect = cropRect(left, top, right, bottom, bitmap.width, bitmap.height)
        ToolFooter(
            caption = "${rect.width} x ${rect.height}",
            confirmLabel = "Crop",
            enabled = !busy,
            secondaryLabel = "Whole image",
            onSecondary = {
                left = 0f; top = 0f
                right = bitmap.width.toFloat(); bottom = bitmap.height.toFloat()
            },
            onCancel = { onDone(null) },
            onConfirm = { onDone(rect) },
        )
    }
}

// ── draw ──

/** One freehand line, in image pixels so it lands where it was drawn whatever the view does. */
private class InkStroke(val colour: Color, val widthPx: Float) {
    val points = mutableListOf<Offset>()
}

private val PEN_COLOURS = listOf(
    Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF30A46C),
    Color(0xFF3E9BFF), Color(0xFF111111), Color(0xFFFFFFFF),
)

@Composable
private fun DrawSurface(bitmap: Bitmap, busy: Boolean, onDone: (List<InkStroke>) -> Unit) {
    val colors = Filet.colors
    var box by remember { mutableStateOf(Size.Zero) }
    val strokes = remember(bitmap) { mutableStateListOf<InkStroke>() }
    var ink by remember(bitmap) { mutableStateOf(PEN_COLOURS.first()) }
    // Scaled off the picture's own size: a 6px line on a 3000px photo is invisible, and the
    // same line on a 300px icon covers half of it.
    val longEdge = maxOf(bitmap.width, bitmap.height).toFloat()
    var nib by remember(bitmap) { mutableFloatStateOf(longEdge / 180f) }
    // The stroke list is mutated in place while a finger is down, and Compose cannot see that.
    // Bumping a counter is what makes the line appear as it is drawn rather than on lift.
    var ticks by remember(bitmap) { mutableIntStateOf(0) }
    // The draw surface gets its own zoom. Editing the edges of a picture means being able to see
    // them, and this tool had none at all - a pinch here just drew another line.
    var view by remember(bitmap) { mutableStateOf(ZoomView.NONE) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(bitmap) {
                    // One finger draws, two fingers move the picture.
                    //
                    // `detectDragGestures` cannot tell them apart - it reports a pinch as a drag,
                    // so putting a second finger down to zoom drew a second line instead. Nor can
                    // two ready-made detectors be stacked: they compete for the same events and
                    // whichever consumes first wins. So the pointer loop is written out and the
                    // finger count is read directly.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.count { it.pressed }
                            if (down >= 2) {
                                // The first finger has usually already begun a stroke by the time
                                // the second lands. Without dropping it, every pinch leaves a dot
                                // or a flick behind on the picture.
                                if (strokes.isNotEmpty()) {
                                    strokes.removeAt(strokes.lastIndex)
                                    ticks++
                                }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    view = view.pinched(zoom, pan.x, pan.y, box.width, box.height)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.pressed } ?: continue
                            val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                            if (!fit.ready) continue
                            // Through the transform, not around it: at any zoom other than 1 the
                            // finger is not over the pixel it appears to be over, and a stroke
                            // that skips this drifts further from the finger the further in it is.
                            val ix = fit.toImageX(view.unprojectX(change.position.x, box.width), bitmap.width)
                            val iy = fit.toImageY(view.unprojectY(change.position.y, box.height), bitmap.height)
                            if (change.previousPressed) {
                                strokes.lastOrNull()?.points?.add(Offset(ix, iy))
                            } else {
                                val stroke = InkStroke(ink, nib)
                                stroke.points.add(Offset(ix, iy))
                                strokes.add(stroke)
                            }
                            ticks++
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Both layers carry the same transform, so the ink stays on the pixels it was put on.
            val layer = Modifier.fillMaxSize().graphicsLayer {
                scaleX = view.scale
                scaleY = view.scale
                translationX = view.offsetX
                translationY = view.offsetY
            }
            Image(bitmap.asImageBitmap(), null, modifier = layer)
            Canvas(layer) {
                // Reading the counter inside the draw scope is what subscribes this canvas to
                // it; without the read the in-place mutations above go unnoticed.
                if (ticks >= 0) {
                    val fit = fitInside(size.width, size.height, bitmap.width, bitmap.height)
                    if (fit.ready) {
                        for (s in strokes) {
                            val w = (s.widthPx * fit.scale).coerceAtLeast(1f)
                            if (s.points.size == 1) {
                                val p = s.points[0]
                                drawCircle(s.colour, w / 2f, Offset(fit.toViewX(p.x), fit.toViewY(p.y)))
                            } else {
                                for (i in 1 until s.points.size) {
                                    val a = s.points[i - 1]
                                    val b = s.points[i]
                                    drawLine(
                                        s.colour,
                                        Offset(fit.toViewX(a.x), fit.toViewY(a.y)),
                                        Offset(fit.toViewX(b.x), fit.toViewY(b.y)),
                                        strokeWidth = w,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.raised)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (c in PEN_COLOURS) {
                Box(
                    Modifier
                        .size(28.dp)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (c == ink) 2.dp else 1.dp,
                            color = if (c == ink) colors.accent else colors.lineSoft,
                            shape = CircleShape,
                        )
                        .clickable { ink = c }
                )
            }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = nib,
                onValueChange = { nib = it },
                valueRange = (longEdge / 600f)..(longEdge / 40f),
                modifier = Modifier.weight(1f),
            )
        }

        ToolFooter(
            caption = if (strokes.isEmpty()) "Draw on the picture" else "${strokes.size} strokes",
            confirmLabel = "Apply",
            enabled = !busy,
            secondaryLabel = if (strokes.isEmpty()) null else "Undo stroke",
            onSecondary = { strokes.removeLastOrNull(); ticks++ },
            onCancel = { onDone(emptyList()) },
            onConfirm = { onDone(strokes.toList()) },
        )
    }
}

/** Burn the strokes into a copy of the bitmap, at the working image's full resolution. */
/**
 * A piece of text placed on the picture, in the picture's own coordinates.
 *
 * Stored where it lands on the IMAGE rather than where it was tapped on screen, for the same
 * reason ink is: the surface can be zoomed and panned afterwards, and the text has to stay on the
 * pixels it was put on rather than on the part of the screen it was dropped at.
 */
private data class ImageText(
    val text: String,
    val colour: Color,
    val sizePx: Float,
    val x: Float,
    val y: Float,
)

/**
 * Burn text onto a copy of the bitmap.
 *
 * Centred on its anchor, so a tap puts the text where the finger was rather than starting a line
 * to the right of it.
 *
 * **A dark outline is drawn under every glyph**, and that is not decoration. Text placed on a
 * photograph lands on whatever happens to be there, and white on a bright sky or black on a
 * shadow is text that cannot be read at all. The outline is what lets one chosen colour work on
 * any background, which is why this does not simply draw the fill.
 */
private fun bakeText(source: Bitmap, items: List<ImageText>): Bitmap {
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    for (item in items) {
        if (item.text.isEmpty()) continue
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                (item.colour.alpha * 255).roundToInt(),
                (item.colour.red * 255).roundToInt(),
                (item.colour.green * 255).roundToInt(),
                (item.colour.blue * 255).roundToInt(),
            )
            textSize = item.sizePx
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val edge = android.graphics.Paint(fill).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (item.sizePx / 14f).coerceAtLeast(1.5f)
            color = android.graphics.Color.argb(190, 0, 0, 0)
        }
        canvas.drawText(item.text, item.x, item.y, edge)
        canvas.drawText(item.text, item.x, item.y, fill)
    }
    return out
}

private fun bakeStrokes(source: Bitmap, strokes: List<InkStroke>): Bitmap {
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (s in strokes) {
        paint.color = android.graphics.Color.argb(
            (s.colour.alpha * 255).roundToInt(),
            (s.colour.red * 255).roundToInt(),
            (s.colour.green * 255).roundToInt(),
            (s.colour.blue * 255).roundToInt(),
        )
        paint.strokeWidth = s.widthPx.coerceAtLeast(1f)
        if (s.points.size == 1) {
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(s.points[0].x, s.points[0].y, s.widthPx / 2f, paint)
            continue
        }
        paint.style = android.graphics.Paint.Style.STROKE
        val path = android.graphics.Path()
        path.moveTo(s.points[0].x, s.points[0].y)
        for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
        canvas.drawPath(path, paint)
    }
    return out
}

// ── resize / stretch ──

@Composable
private fun ResizeSurface(bitmap: Bitmap, busy: Boolean, onDone: (Int, Int) -> Unit) {
    val colors = Filet.colors
    var wPercent by remember(bitmap) { mutableFloatStateOf(100f) }
    var hPercent by remember(bitmap) { mutableFloatStateOf(100f) }
    val w = (bitmap.width * wPercent / 100f).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * hPercent / 100f).roundToInt().coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The preview is the same bitmap under a layer scale, so dragging a slider costs
            // nothing. Resampling a 20 MP buffer sixty times a second would not survive it.
            Image(
                bitmap.asImageBitmap(), null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = wPercent / 100f
                    scaleY = hPercent / 100f
                },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.raised)
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            ResizeRow("Width", wPercent, bitmap.width, w) { wPercent = it }
            ResizeRow("Height", hPercent, bitmap.height, h) { hPercent = it }
        }
        ToolFooter(
            caption = "$w x $h",
            confirmLabel = "Resize",
            enabled = !busy,
            secondaryLabel = "Reset",
            onSecondary = { wPercent = 100f; hPercent = 100f },
            onCancel = { onDone(bitmap.width, bitmap.height) },
            onConfirm = { onDone(w, h) },
        )
    }
}

/**
 * Glyphs that can be dropped on a picture without opening a keyboard.
 *
 * Deliberately short. A full emoji keyboard is the system's and it is one tap away in the field;
 * what this row is for is the handful anybody actually puts on a screenshot, reachable without
 * the keyboard covering the picture they are aiming at.
 */
private val QUICK_GLYPHS = listOf(
    "→", "←", "↑", "↓", "✔", "✘", "⭐", "❤",
    "👍", "🔥", "😂", "🤔", "👀",
    "⚠", "📌", "🎉",
)

/**
 * Put text or an emoji on the picture.
 *
 * One tool for both, because an emoji IS text - what the tool actually has to provide is
 * placement, size, colour and a way to pick a glyph without a keyboard in the way.
 *
 * **Tap to place, drag to move.** The text appears where it is tapped and can be dragged
 * afterwards, because nobody lands it in the right spot first time and a tool that makes you
 * undo to reposition is a tool people stop using.
 */
@Composable
private fun TextSurface(bitmap: Bitmap, busy: Boolean, onDone: (List<ImageText>) -> Unit) {
    val colors = Filet.colors
    var box by remember { mutableStateOf(Size.Zero) }
    var typed by remember(bitmap) { mutableStateOf("") }
    var ink by remember(bitmap) { mutableStateOf(PEN_COLOURS.first()) }
    val longEdge = maxOf(bitmap.width, bitmap.height).toFloat()
    var size by remember(bitmap) { mutableFloatStateOf(longEdge / 12f) }
    val placed = remember(bitmap) { mutableStateListOf<ImageText>() }
    var ticks by remember(bitmap) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(bitmap) {
                    detectTapGestures { at ->
                        if (typed.isEmpty()) return@detectTapGestures
                        val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                        if (!fit.ready) return@detectTapGestures
                        placed.add(
                            ImageText(
                                text = typed,
                                colour = ink,
                                sizePx = size,
                                x = fit.toImageX(at.x, bitmap.width),
                                y = fit.toImageY(at.y, bitmap.height),
                            ),
                        )
                        ticks++
                    }
                }
                .pointerInput(bitmap) {
                    // Dragging moves whatever was placed last, which is the one just put down.
                    detectDragGestures { change, drag ->
                        change.consume()
                        val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                        val last = placed.lastOrNull() ?: return@detectDragGestures
                        if (!fit.ready) return@detectDragGestures
                        placed[placed.lastIndex] = last.copy(
                            x = last.x + drag.x / fit.scale,
                            y = last.y + drag.y / fit.scale,
                        )
                        ticks++
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (ticks >= 0) {
                    val fit = fitInside(this.size.width, this.size.height, bitmap.width, bitmap.height)
                    if (fit.ready) {
                        for (item in placed) {
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = item.sizePx * fit.scale
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            val outline = android.graphics.Paint(paint).apply {
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = (paint.textSize / 14f).coerceAtLeast(1.5f)
                                color = android.graphics.Color.argb(190, 0, 0, 0)
                            }
                            paint.color = android.graphics.Color.argb(
                                (item.colour.alpha * 255).roundToInt(),
                                (item.colour.red * 255).roundToInt(),
                                (item.colour.green * 255).roundToInt(),
                                (item.colour.blue * 255).roundToInt(),
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                item.text, fit.toViewX(item.x), fit.toViewY(item.y), outline,
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                item.text, fit.toViewX(item.x), fit.toViewY(item.y), paint,
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.raised)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.sunken)
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        if (typed.isEmpty()) {
                            Text("Type something, then tap the picture", fontSize = 12.sp, color = colors.fg3)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (glyph in QUICK_GLYPHS) {
                    Text(
                        glyph,
                        fontSize = 19.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { typed += glyph }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (c in PEN_COLOURS) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (c == ink) 2.dp else 1.dp,
                                color = if (c == ink) colors.accent else colors.lineSoft,
                                shape = CircleShape,
                            )
                            .clickable { ink = c },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = (longEdge / 40f)..(longEdge / 4f),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ToolFooter(
            caption = when {
                placed.isEmpty() && typed.isEmpty() -> "nothing placed"
                placed.isEmpty() -> "tap the picture to place it"
                else -> "${placed.size} placed"
            },
            confirmLabel = "Add text",
            enabled = !busy && placed.isNotEmpty(),
            secondaryLabel = "Undo last",
            onSecondary = {
                if (placed.isNotEmpty()) {
                    placed.removeAt(placed.lastIndex)
                    ticks++
                }
            },
            onCancel = { onDone(emptyList()) },
            onConfirm = { onDone(placed.toList()) },
        )
    }
}

/**
 * Turn the picture by any angle.
 *
 * The preview is a layer rotation on the same bitmap, so dragging the slider costs nothing -
 * resampling a 20 MP buffer sixty times a second would not survive it. The real rotation happens
 * once, on confirm, through `rotatedBy`, which grows the canvas so the corners are not cut off.
 *
 * Snapping is offered rather than imposed. The common use is straightening a photograph by a
 * degree or two, and a slider that jumps to the nearest fifteen degrees cannot do that at all;
 * the step buttons are there for when a round number is what was wanted.
 */
@Composable
private fun RotateSurface(bitmap: Bitmap, busy: Boolean, onDone: (Float) -> Unit) {
    val colors = Filet.colors
    var angle by remember(bitmap) { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                bitmap.asImageBitmap(), null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = angle
                        // Held back from the edges so the corners stay on screen while turning.
                        // The real operation grows the canvas instead; this is only the preview.
                        scaleX = 0.72f
                        scaleY = 0.72f
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.raised)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Angle", fontSize = 11.sp, color = colors.fg2, modifier = Modifier.width(50.dp))
                Slider(
                    value = angle,
                    onValueChange = { angle = it },
                    valueRange = -180f..180f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatAngle(angle),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.fg3,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(62.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (step in listOf(-90f, -45f, -1f, 1f, 45f, 90f)) {
                    Text(
                        (if (step > 0) "+" else "") + step.toInt(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.fg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp))
                            .clickable { angle = (angle + step).coerceIn(-180f, 180f) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }
        ToolFooter(
            caption = if (angle == 0f) "unchanged" else "turns " + formatAngle(angle),
            confirmLabel = "Rotate",
            enabled = !busy,
            secondaryLabel = "Reset",
            onSecondary = { angle = 0f },
            onCancel = { onDone(0f) },
            onConfirm = { onDone(angle) },
        )
    }
}

/** One decimal and a degree sign, so a one-degree straighten is visibly different from none. */
private fun formatAngle(angle: Float): String = "%.1f°".format(angle)

@Composable
private fun ResizeRow(label: String, percent: Float, from: Int, to: Int, onChange: (Float) -> Unit) {
    val colors = Filet.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = colors.fg2, modifier = Modifier.width(50.dp))
        Slider(
            value = percent,
            onValueChange = onChange,
            valueRange = 20f..300f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$from to $to",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.fg3,
            textAlign = TextAlign.End,
            modifier = Modifier.width(92.dp),
        )
    }
}

// ── shared chrome ──

/**
 * What can be done to the picture, along the bottom, each button with its name under it.
 *
 * The bar used to be a row of bare icons along the top, and two of them did not say what they
 * did: a circular arrow that read as "rotate" and was "reset zoom", and a pencil that opened a
 * whole editor. A name under a button is the cheapest way to stop it being guessed at, and the
 * bottom is where the thumb already is. Details stays top right, beside the name it describes.
 */
@Composable
private fun PictureActions(
    ready: Boolean,
    canDelete: Boolean,
    zoomed: Boolean,
    menu: PicMenu?,
    onMenu: (PicMenu?) -> Unit,
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onDelete: () -> Unit,
    onFit: () -> Unit,
    grey: Boolean,
    onGround: () -> Unit,
    onReveal: () -> Unit,
    /** The run's "only photos / only videos" row, drawn by the run itself; see `RunKindsRow`. */
    runKinds: @Composable () -> Unit = {},
) {
    val ground = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            // The top bar's gradient, upside down: dense behind the labels, gone by the picture.
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to ground.copy(alpha = 0.55f),
                    1f to ground.copy(alpha = 0.92f),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(FiletIcons.Share, "Share", onClick = onShare)
        BarButton(FiletIcons.RotateRight, "Rotate", enabled = ready, onClick = onRotate)
        Box {
            BarButton(FiletIcons.Rename, "Edit") { onMenu(PicMenu.EDIT) }
            dev.niccc2007.filet.ui.dialogs.PopMenu(
                expanded = menu == PicMenu.EDIT,
                onDismiss = { onMenu(null) },
                below = 52.dp,
                above = true,
                centred = true,
            ) {
                if (ready) {
                    dev.niccc2007.filet.ui.dialogs.PopRow(
                        FiletIcons.Crop,
                        "Edit here",
                        detail = "Crop, draw, turn, resize. Saved as a copy.",
                    ) { onMenu(null); onEdit() }
                }
                dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Link, "Open with another app") {
                    onMenu(null)
                    onOpenWith()
                }
            }
        }
        if (canDelete) BarButton(FiletIcons.Delete, "Delete", onClick = onDelete)
        Box {
            BarButton(FiletIcons.More, "More") { onMenu(PicMenu.MORE) }
            dev.niccc2007.filet.ui.dialogs.PopMenu(
                expanded = menu == PicMenu.MORE,
                onDismiss = { onMenu(null) },
                below = 52.dp,
                above = true,
            ) {
                dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Folder, "Show in folder") {
                    onMenu(null)
                    onReveal()
                }
                runKinds()
                // Named for what it switches TO, as a light switch is: the row is the action.
                dev.niccc2007.filet.ui.dialogs.PopRow(
                    FiletIcons.Brightness,
                    if (grey) "Black background" else "Grey background",
                ) {
                    onMenu(null)
                    onGround()
                }
                // Only while there is a zoom to undo. A double tap does the same; this is the
                // way to it that can be read.
                if (zoomed) {
                    dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Stretch, "Fit to screen") {
                        onMenu(null)
                        onFit()
                    }
                }
            }
        }
    }
}

/**
 * Save, as a choice: over the original, or as a new file chosen in the picker.
 *
 * Two choices rather than one button, because they are different promises. Replacing is the
 * common wish - the photo was simply the wrong way up, or it is the crop that was wanted - and a
 * new file keeps the original exactly as it was. The editor used to have only "Save a copy",
 * beside the original under a name it chose, so a photo could neither be corrected in place nor
 * saved anywhere else.
 *
 * One menu for the turn and the edit, so the two saves cannot drift into two shapes.
 *
 * @param replaceDetail what replacing would do, or null where it is not offered: a folder that
 *   cannot be written to, or a format Android cannot write back. A new file can still be saved
 *   anywhere else, so the menu always opens.
 */
@Composable
private fun SaveChoice(
    label: String,
    enabled: Boolean,
    replaceDetail: String?,
    newDetail: String,
    onReplace: () -> Unit,
    onNew: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ViewerAction(FiletIcons.Save, label, enabled = enabled) { open = true }
        dev.niccc2007.filet.ui.dialogs.PopMenu(expanded = open, onDismiss = { open = false }, below = 44.dp) {
            if (replaceDetail != null) {
                dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Save, "Replace original", detail = replaceDetail) {
                    open = false
                    onReplace()
                }
            }
            dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Copy, "Save as new", detail = newDetail) {
                open = false
                onNew()
            }
        }
    }
}

/** The "Save as new" line: where it goes, and what it will be when that is not what it was. */
private fun newFileNote(extension: String, makes: String): String {
    val ext = extension.lowercase()
    return if (makes in dev.niccc2007.filet.pick.PickName.sameKind(ext)) {
        "Choose a folder and a name; the original stays as it is"
    } else {
        "As a " + makes.uppercase() + ": Android cannot write " + ext.uppercase() + " back. Choose a folder and a name"
    }
}

/**
 * What replacing a photo with the edit does to it, said before it is done.
 *
 * The editor works on a copy at most 2400 pixels on its longest edge, so replacing a larger
 * photo with its edit makes the photo smaller - permanently, which is exactly the kind of thing
 * a menu has to say rather than leave to be discovered.
 */
internal fun editSizeNote(w: Int, h: Int, source: Pair<Int, Int>?, jpeg: Boolean): String {
    val smaller = source != null && w.toLong() * h < source.first.toLong() * source.second
    return buildString {
        append("Saved at ").append(w).append(" x ").append(h)
        if (smaller) append(", smaller than the original's ").append(source!!.first).append(" x ").append(source.second)
        if (jpeg) append(". Date and place kept")
    }
}

/** One of [PictureActions]: an icon and its name, bright enough to read over a photograph. */
@Composable
private fun BarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = if (enabled) colors.fg2 else colors.fg3)
    }
}

@Composable
private fun ToolStrip(
    enabled: Boolean,
    onCrop: () -> Unit,
    onDraw: () -> Unit,
    onResize: () -> Unit,
    onRotate: () -> Unit,
    onText: () -> Unit,
    onOp: ((Pixels) -> Pixels) -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            // After the background, so the row's colour runs under the navigation bar and its
            // buttons stop above it. They sat under three-key navigation's keys.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(FiletIcons.Crop, "Crop", enabled, onCrop)
        ToolButton(FiletIcons.Draw, "Draw", enabled, onDraw)
        ToolButton(FiletIcons.Rename, "Text", enabled, onText)
        ToolButton(FiletIcons.Stretch, "Resize", enabled, onResize)
        ToolButton(FiletIcons.RotateRight, "Turn", enabled, onRotate)
        ToolButton(FiletIcons.RotateLeft, "Left", enabled) { onOp { it.rotated(Turn.LEFT) } }
        ToolButton(FiletIcons.RotateRight, "Right", enabled) { onOp { it.rotated(Turn.RIGHT) } }
        ToolButton(FiletIcons.FlipH, "Mirror", enabled) { onOp { it.flipped(horizontal = true) } }
        ToolButton(FiletIcons.FlipV, "Flip", enabled) { onOp { it.flipped(horizontal = false) } }
        ToolButton(FiletIcons.Invert, "Invert", enabled) { onOp { it.inverted() } }
        ToolButton(FiletIcons.Grey, "Grey", enabled) { onOp { it.greyscale() } }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, label,
            tint = if (enabled) colors.fg2 else colors.fg3.copy(alpha = 0.4f),
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 9.sp, color = colors.fg3)
    }
}

/** Cancel, a caption, an optional middle action, confirm. Every tool is dismissed the same way. */
@Composable
private fun ToolFooter(
    caption: String,
    confirmLabel: String,
    enabled: Boolean,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Cancel", fontSize = 12.5.sp, color = colors.fg2,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            caption,
            fontSize = 10.5.sp,
            color = colors.fg3,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (secondaryLabel != null) {
            Text(
                secondaryLabel, fontSize = 12.sp, color = colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSecondary)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            confirmLabel,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) colors.accent else colors.high)
                .clickable(enabled = enabled, onClick = onConfirm)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

// ── bitmap in, bytes out ──

private fun Bitmap.toPixels(): Pixels {
    val buf = IntArray(width * height)
    getPixels(buf, 0, width, 0, 0, width, height)
    return Pixels(width, height, buf)
}

/** Marked opaque when it is: see [Pixels.isOpaque], and [encodedExtension] which reads the mark. */
private fun Pixels.toBitmap(): Bitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(!isOpaque()) }

internal class Encoded(val bytes: ByteArray, val extension: String)

/**
 * Encode for saving, and say what was actually produced.
 *
 * PNG whenever the source was PNG-like or the result carries transparency, because JPEG has no
 * alpha and would composite it onto black without saying so. JPEG otherwise, at 92, because a
 * photo re-saved as PNG is several times the size for no visible gain. Formats Android can
 * decode but not write - HEIC, AVIF - come back as JPEG, which is why the extension travels
 * with the bytes rather than being assumed from the original name.
 */
internal fun encode(bitmap: Bitmap, sourceExtension: String): Encoded {
    val png = encodedExtension(bitmap, sourceExtension) == "png"
    val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
    return if (png) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        Encoded(out.toByteArray(), "png")
    } else {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        Encoded(out.toByteArray(), if (sourceExtension == "jpeg") "jpeg" else "jpg")
    }
}

internal val PNG_LIKE = setOf("png", "webp", "gif", "bmp")

/**
 * What [encode] will produce, known before it runs - so a "Save as new" can offer the right name
 * before anything is encoded. The same rule, in the one place both read it from.
 */
internal fun encodedExtension(bitmap: Bitmap, sourceExtension: String): String {
    val ext = sourceExtension.lowercase()
    return when {
        ext in PNG_LIKE || bitmap.hasAlpha() -> "png"
        ext == "jpeg" -> "jpeg"
        else -> "jpg"
    }
}
