package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

private enum class Tool { NONE, CROP, DRAW, RESIZE }

@Composable
fun ImageViewerScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val scope = rememberCoroutineScope()

    var image by remember(node.path) { mutableStateOf<Bitmap?>(null) }
    var sourceSize by remember(node.path) { mutableStateOf("") }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var editing by remember(node.path) { mutableStateOf(false) }
    var tool by remember(node.path) { mutableStateOf(Tool.NONE) }
    var busy by remember(node.path) { mutableStateOf(false) }
    var dirty by remember(node.path) { mutableStateOf(false) }
    var exitArmed by remember(node.path) { mutableStateOf(false) }

    // Bounded, and the oldest is recycled as it falls off the end: an unbounded history of
    // 20 MP bitmaps is an out-of-memory kill two or three taps in.
    val history = remember(node.path) { mutableStateListOf<Bitmap>() }

    // One value rather than three loose floats, so a gesture handler transforms it instead of
    // reading pieces of it. See ZoomState.kt for why that distinction is the whole bug.
    var view by remember(node.path) { mutableStateOf(ZoomView.NONE) }

    fun resetView() { view = ZoomView.NONE }

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

    LaunchedEffect(node.path) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                vm.openRead(node).use { BitmapFactory.decodeStream(it, null, bounds) }
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
                val bmp = vm.openRead(node).use { BitmapFactory.decodeStream(it, null, opts) }
                bmp to "$w x $h"
            }
        }.onSuccess { (bmp, d) ->
            if (bmp == null) error = "This is not an image Android can decode."
            image = bmp
            sourceSize = d
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

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = listOfNotNull(
                dims.takeIf { it.isNotEmpty() },
                if (dirty) "edited, not saved" else humanSize(node.size),
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
                ViewerAction(FiletIcons.Save, "Save a copy", enabled = shown != null && !busy) {
                    val bmp = shown ?: return@ViewerAction
                    scope.launch {
                        busy = true
                        val encoded = withContext(Dispatchers.Default) {
                            runCatching { encode(bmp, node.extension) }.getOrNull()
                        }
                        busy = false
                        if (encoded == null) { vm.toast("Could not encode the edited image."); return@launch }
                        vm.saveEditedImage(node, encoded.bytes, encoded.extension) {
                            dirty = false
                            exitArmed = false
                        }
                    }
                }
                ViewerAction(FiletIcons.Close, "Done editing") { editing = false; tool = Tool.NONE }
            } else {
                ViewerAction(FiletIcons.Rename, "Edit", enabled = shown != null) {
                    editing = true
                    resetView()
                }
                ViewerAction(FiletIcons.Refresh, "Reset zoom", enabled = shown != null) { resetView() }
                // The gap found while looking at a PNG: the in-app viewer had no way out.
                ViewerAction(FiletIcons.Link, "Open in another app") { vm.openExternally(node, force = true) }
                ViewerAction(FiletIcons.Share, "Share") { vm.shareFromViewer(node) }
                ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                error != null -> Text(
                    error!!, fontSize = 13.sp, color = colors.fg2,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                shown == null -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp,
                )

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

                tool == Tool.RESIZE -> ResizeSurface(shown, busy) { w, h ->
                    tool = Tool.NONE
                    if (w != shown.width || h != shown.height) runOp { it.stretched(w, h) }
                }

                else -> ZoomableImage(shown, node.name, view) { view = it }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(30.dp), strokeWidth = 2.5.dp,
                )
            }
        }

        if (editing && tool == Tool.NONE) {
            ToolStrip(
                enabled = shown != null && !busy,
                onCrop = { tool = Tool.CROP },
                onDraw = { tool = Tool.DRAW },
                onResize = { tool = Tool.RESIZE },
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
    onView: (ZoomView) -> Unit,
) {
    var box by remember { mutableStateOf(Size.Zero) }

    // The fix for "pinch to zoom stopped working".
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

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        emit.value(live.value.doubleTapped(at.x, at.y, box.width, box.height))
                    },
                )
            }
            // Last in the chain so it wins the Main pass over the tap detector above, the same
            // ordering the row drag-and-drop needed in round 3.
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    emit.value(live.value.pinched(zoom, pan.x, pan.y, box.width, box.height))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = view.scale; scaleY = view.scale
                translationX = view.offsetX; translationY = view.offsetY
            },
        )
    }
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
                            if (fit.ready) {
                                val s = InkStroke(ink, nib)
                                s.points.add(
                                    Offset(
                                        fit.toImageX(at.x, bitmap.width),
                                        fit.toImageY(at.y, bitmap.height),
                                    )
                                )
                                strokes.add(s)
                                ticks++
                            }
                        },
                    ) { change, _ ->
                        change.consume()
                        val fit = fitInside(box.width, box.height, bitmap.width, bitmap.height)
                        if (fit.ready) {
                            strokes.lastOrNull()?.points?.add(
                                Offset(
                                    fit.toImageX(change.position.x, bitmap.width),
                                    fit.toImageY(change.position.y, bitmap.height),
                                )
                            )
                            ticks++
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
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

@Composable
private fun ToolStrip(
    enabled: Boolean,
    onCrop: () -> Unit,
    onDraw: () -> Unit,
    onResize: () -> Unit,
    onOp: ((Pixels) -> Pixels) -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(FiletIcons.Crop, "Crop", enabled, onCrop)
        ToolButton(FiletIcons.Draw, "Draw", enabled, onDraw)
        ToolButton(FiletIcons.Stretch, "Resize", enabled, onResize)
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

private fun Pixels.toBitmap(): Bitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

private class Encoded(val bytes: ByteArray, val extension: String)

/**
 * Encode for saving, and say what was actually produced.
 *
 * PNG whenever the source was PNG-like or the result carries transparency, because JPEG has no
 * alpha and would composite it onto black without saying so. JPEG otherwise, at 92, because a
 * photo re-saved as PNG is several times the size for no visible gain. Formats Android can
 * decode but not write - HEIC, AVIF - come back as JPEG, which is why the extension travels
 * with the bytes rather than being assumed from the original name.
 */
private fun encode(bitmap: Bitmap, sourceExtension: String): Encoded {
    val png = sourceExtension in PNG_LIKE || bitmap.hasAlpha()
    val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
    return if (png) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        Encoded(out.toByteArray(), "png")
    } else {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        Encoded(out.toByteArray(), if (sourceExtension == "jpeg") "jpeg" else "jpg")
    }
}

private val PNG_LIKE = setOf("png", "webp", "gif", "bmp")
