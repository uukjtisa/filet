package dev.niccc2007.filet.docs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ops.LocalCopy
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet
import androidx.compose.ui.platform.LocalContext
import dev.niccc2007.filet.vfs.VNode
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A PDF, as a scroll of pages that can be pinched.
 *
 * ## Why a scroll and not a pager
 *
 * A pager is what a PDF viewer on a desktop does, because a desktop shows a whole page at
 * once. On a phone a page is taller than the screen whatever is done to it, so a pager means
 * swiping sideways between things that each need scrolling vertically - two axes for one
 * document. One continuous scroll is how every phone reader that people actually use behaves.
 *
 * ## Zoom is the whole document, and vertical movement stays the list's
 *
 * The pinch scales the list as one picture (see [PdfPaging.View]) rather than each page on its
 * own. Zooming a single page inside a scrolling list means two things that both want the same
 * vertical drag, and whichever loses feels broken. Here a one-finger drag up and down is still
 * the list's scroll - with its fling - and only the sideways part of a drag pans, so a diagonal
 * drag over a zoomed page moves freely in both directions.
 *
 * ## Rendered as it is read
 *
 * Whole pages are rendered at the screen's width as they come into view, held within a byte
 * budget and dropped when they leave it - see [PdfPaging.plan]. When zoomed, the visible part of
 * each page on screen is rendered again at the zoom and laid over it, so text sharpens a moment
 * after the fingers stop - see [PdfDoc.renderSlice]. The placeholder keeps the page's real
 * aspect so the scroll does not jump as pages arrive.
 */
@Composable
fun PdfScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var doc by remember(node.path) { mutableStateOf<PdfDoc?>(null) }
    var problem by remember(node.path) { mutableStateOf<PdfDoc.Opened?>(null) }
    var loading by remember(node.path) { mutableStateOf(true) }
    // The password the current attempt is made with. Changing it reopens the file.
    var password by remember(node.path) { mutableStateOf<String?>(null) }
    var asking by remember(node.path) { mutableStateOf(false) }

    // PdfRenderer needs a seekable descriptor, so an entry in an archive or a file on a share
    // has to be a real local file first. See `LocalCopy`.
    LaunchedEffect(node.path, password) {
        loading = true
        // Bug identified here: the screen could be left while the file was still opening. The
        // open finished on the IO dispatcher, the effect was cancelled on its way back, and the
        // result - a renderer holding a file descriptor - was dropped on the floor with nothing
        // left to close it. The holder is how the cancellation path finds it.
        val held = AtomicReference<PdfDoc?>(null)
        try {
            val opened = withContext(Dispatchers.IO) {
                PdfDoc.open(vm.localCopy, node, password).also {
                    if (it is PdfDoc.Opened.Ready) held.set(it.doc)
                }
            }
            if (opened is PdfDoc.Opened.Ready) {
                doc = opened.doc
                problem = null
            } else {
                problem = opened
                asking = opened is PdfDoc.Opened.Locked && opened.canAsk
            }
        } catch (e: CancellationException) {
            held.get()?.close()
            throw e
        }
        loading = false
    }

    // The descriptor is closed when the screen goes, not when the composition happens to be
    // collected: a leaked ParcelFileDescriptor holds the file open, and enough of them
    // exhausts the process's handles.
    DisposableEffect(node.path) {
        onDispose { doc?.close() }
    }

    val current = doc
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        current != null -> PdfPages(current, node)

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    problemText(problem, node),
                    fontSize = 12.sp,
                    color = colors.warn,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                val locked = problem as? PdfDoc.Opened.Locked
                if (locked != null && locked.canAsk) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Enter the password",
                        fontSize = 12.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accent.copy(alpha = 0.12f))
                            .clickable { asking = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }

    val locked = problem as? PdfDoc.Opened.Locked
    if (asking && locked != null && locked.canAsk && !loading) {
        PasswordDialog(
            name = node.name,
            wrong = locked.wrong,
            onOpen = { entered ->
                asking = false
                password = entered
            },
            onDismiss = { asking = false },
        )
    }
}

/** One sentence per way of failing, each naming the actual cause. */
private fun problemText(problem: PdfDoc.Opened?, node: VNode): String = when (problem) {
    PdfDoc.Opened.NoCopy ->
        if (node.size > LocalCopy.MAX_BYTES) "This PDF is too large to copy out of where it is to read."
        else "This PDF could not be copied out of where it is to read."
    PdfDoc.Opened.Unreadable ->
        "Android could not read this as a PDF. It may be damaged, or use something this phone's " +
            "PDF reader does not understand."
    is PdfDoc.Opened.Locked ->
        if (problem.canAsk) "This PDF is password-protected."
        else "This PDF is password-protected, and the PDF reader built into this version of " +
            "Android cannot be given a password. Android 15 and later can open it."
    is PdfDoc.Opened.Ready, null -> "This PDF could not be opened."
}

@Composable
private fun PasswordDialog(name: String, wrong: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    // Typed input lives in this dialogue, so a stray tap on the scrim must not throw it away.
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Lock, "Locked PDF", sub = name, onClose = onDismiss)
        DlgBody {
            if (wrong) DlgWarn("That password did not open it.", bad = true)
            DlgField("Password", entry, { entry = it }, password = true)
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Open", kind = BtnKind.PRIMARY, enabled = entry.isNotEmpty()) { onOpen(entry) }
        }
    }
}

/**
 * Ask for a page number. The parse is [PdfPaging.pageIndex], so "0", "abc" and a number past
 * the end are refused by the same tested rule rather than by a scroll that silently clamps.
 */
@Composable
private fun GoToPageDialog(pages: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val target = PdfPaging.pageIndex(entry, pages)
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Doc, "Go to page", sub = "1 to $pages", onClose = onDismiss)
        DlgBody {
            DlgField("Page", entry, { entry = it.filter(Char::isDigit).take(6) }, numeric = true)
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Go", kind = BtnKind.PRIMARY, enabled = target != null) { target?.let(onGo) }
        }
    }
}

/** A rendered slice of a page and the zoom it was rendered for. */
private class SliceBitmap(val slice: PdfPaging.Slice, val scale: Float, val bitmap: Bitmap)

private val PAGE_GAP = 6.dp
private val PAGE_SIDE = 8.dp

/**
 * How long the view must be still before sharp slices are rendered. Long enough that a pinch in
 * progress does not start a render every frame, short enough to read as "it sharpened".
 */
private const val SETTLE_MS = 140L

@Composable
private fun PdfPages(doc: PdfDoc, node: VNode) {
    val colors = Filet.colors
    val context = LocalContext.current
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val sidePx = with(density) { PAGE_SIDE.toPx() }
    var viewport by remember(doc) { mutableStateOf(IntSize.Zero) }
    val view = remember(doc) { mutableStateOf(PdfPaging.View()) }
    // The page box's own width in pixels - the screen less the margins - which is the width a
    // base is rendered at. Rendering at the dp width would be a quarter of the resolution on a
    // modern phone and read as a blurry scan.
    val baseW = (viewport.width - 2 * sidePx).toInt()
    val bases = remember(doc) { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    val slices = remember(doc) { mutableStateOf<Map<Int, SliceBitmap>>(emptyMap()) }
    val budget = remember { PdfPaging.budget(Runtime.getRuntime().maxMemory()) }
    val scope = rememberCoroutineScope()
    var jumping by remember(doc) { mutableStateOf(false) }

    // Bases. One effect, driven by what is on screen: rendering inside each item's own effect
    // means a fling starts and cancels a hundred renders, and this one looks at the range.
    LaunchedEffect(doc, baseW) {
        if (baseW <= 0) return@LaunchedEffect
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
        }.collect { range ->
            if (range.isEmpty()) return@collect
            val plan = PdfPaging.plan(range, doc.pages, PdfDoc.KEEP, budget) {
                PdfPaging.bytesFor(baseW, doc.aspect(it))
            }
            val wanted = plan.toHashSet()
            // Dropped first, so the peak is the budget plus one page rather than two sets of
            // them. A page held at another width goes too: bug identified here - after a
            // rotation the cache still held every page at the old width and, because the index
            // matched, never re-rendered them, so a landscape screen showed portrait-width
            // pages stretched across it.
            val keep = bases.value.filter { (i, b) ->
                i in wanted && b.width == PdfPaging.fit(baseW, doc.aspect(i), PdfDoc.MAX_WIDTH, PdfDoc.MAX_HEIGHT).w
            }
            if (keep.size != bases.value.size) bases.value = keep
            for (i in plan) {
                if (bases.value.containsKey(i)) continue
                val bitmap = withContext(Dispatchers.Default) { doc.render(i, baseW) } ?: continue
                bases.value = bases.value + (i to bitmap)
            }
        }
    }

    // Slices. Latest-wins: a new zoom or scroll position abandons the old set before it is
    // finished, and nothing is rendered until the view has been still for SETTLE_MS.
    LaunchedEffect(doc, baseW) {
        if (baseW <= 0) return@LaunchedEffect
        snapshotFlow {
            view.value to state.layoutInfo.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) }
        }.collectLatest { (v, items) ->
            if (v.scale <= 1.01f) {
                if (slices.value.isNotEmpty()) slices.value = emptyMap()
                return@collectLatest
            }
            delay(SETTLE_MS)
            val w = viewport.width.toFloat()
            val h = viewport.height.toFloat()
            val next = HashMap<Int, SliceBitmap>()
            for ((index, offset, size) in items) {
                val slice = PdfPaging.slice(v, w, h, sidePx, offset.toFloat(), baseW.toFloat(), size.toFloat())
                    ?: continue
                val held = slices.value[index]
                if (held != null && held.slice == slice && held.scale == v.scale) {
                    next[index] = held
                    continue
                }
                val bitmap = withContext(Dispatchers.Default) {
                    doc.renderSlice(index, baseW * v.scale, slice)
                } ?: continue
                next[index] = SliceBitmap(slice, v.scale, bitmap)
            }
            // Replaced in one assignment, so the old set is unreachable the moment the new one
            // is shown: the slices cost at most the screen twice over, briefly, whatever the
            // zoom.
            slices.value = next
        }
    }

    val bottomPad = with(density) {
        PdfPaging.bottomPad(view.value.scale, viewport.height.toFloat()).toDp()
    }

    // Which page the middle of the screen is on. One indicator over the document rather than
    // a caption under every page: under a zoom the captions are scaled off the edge, and a
    // label per page is a row of the same information repeated down the scroll.
    val page by remember(doc) {
        derivedStateOf {
            val row = PdfPaging.middleRow(view.value, viewport.height.toFloat())
            val items = state.layoutInfo.visibleItemsInfo
            (items.firstOrNull { row >= it.offset && row < it.offset + it.size } ?: items.firstOrNull())
                ?.index ?: 0
        }
    }

    // The reading spot is the page the indicator names. See [ReadingSpots].
    val saved = rememberSavedSpot(node.path.toString(), SpotKind.PAGE)
    SaveSpotOnLeave(node.path) {
        if (doc.pages <= 0) return@SaveSpotOnLeave
        val at = page
        val path = node.path.toString()
        ReadingSpots.of(context).left(path, SpotKind.PAGE, top = at == 0, end = at >= doc.pages - 1) {
            ReadingSpot(path, SpotKind.PAGE, item = at, label = "page ${at + 1} of ${doc.pages}", stamp = node.mtime)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            // The pinch. Read in the Initial pass, before the list sees the event, and consumed
            // whenever two fingers are down - so the list's own drag is cancelled the moment a
            // second finger lands instead of scrolling by the first finger's half of the pinch.
            // `view` and `state` are held objects read through, so this block cannot freeze a
            // stale value however long it lives.
            .pointerInput(doc) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val c = event.calculateCentroid(useCurrent = true)
                            val pan = event.calculatePan()
                            val step = PdfPaging.pinch(
                                view.value, size.width.toFloat(),
                                c.x, c.y, event.calculateZoom(), pan.x, pan.y,
                            )
                            view.value = step.view
                            if (step.scroll != 0f) state.dispatchRawDelta(step.scroll)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // The sideways half of a one-finger drag while zoomed. Main pass, AFTER the list:
            // the list takes the vertical part as its scroll and consumes the change, so the
            // horizontal part is read with the consumption ignored rather than lost.
            .pointerInput(doc) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        if (down.size == 1 && view.value.scale > 1.01f) {
                            val dx = down[0].positionChangeIgnoreConsumed().x
                            if (dx != 0f) view.value = PdfPaging.pan(view.value, dx, size.width.toFloat())
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(doc) {
                detectTapGestures(onDoubleTap = { p ->
                    val step = PdfPaging.doubleTap(view.value, size.width.toFloat(), p.x, p.y)
                    view.value = step.view
                    if (step.scroll != 0f) state.dispatchRawDelta(step.scroll)
                })
            },
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val v = view.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = v.scale
                    scaleY = v.scale
                    translationX = v.tx
                },
            state = state,
            verticalArrangement = Arrangement.spacedBy(PAGE_GAP),
            contentPadding = PaddingValues(bottom = bottomPad),
        ) {
            items(doc.pages) { index ->
                val bitmap = bases.value[index]
                val slice = slices.value[index]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_SIDE)
                        // The page's own shape, landscape or portrait, so the base and the
                        // slice both land on a box that matches them. Clamped only against a
                        // degenerate size from a broken page.
                        .aspectRatio(1f / doc.aspect(index).coerceIn(0.05f, 20f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("${index + 1}", fontSize = 11.sp, color = colors.fg3)
                    }
                    if (slice != null) {
                        Image(
                            bitmap = slice.bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.layout { measurable, constraints ->
                                // Placed by the fractions it was cut at, so it stays on the
                                // right part of the page however the list has moved since.
                                val pw = constraints.maxWidth
                                val ph = constraints.maxHeight
                                val s = slice.slice
                                val x = (s.u0 * pw).roundToInt()
                                val y = (s.v0 * ph).roundToInt()
                                val w = ((s.u1 - s.u0) * pw).roundToInt().coerceAtLeast(1)
                                val h = ((s.v1 - s.v0) * ph).roundToInt().coerceAtLeast(1)
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(pw, ph) { placeable.place(x, y) }
                            },
                        )
                    }
                }
            }
        }

        if (doc.pages > 0) {
            // Tapping the indicator asks for a page. A three-hundred-page file read only by
            // scrolling is a minute of flinging to reach an index entry.
            Text(
                "${page + 1} / ${doc.pages}",
                fontSize = 10.5.sp,
                color = colors.fg2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.sunken.copy(alpha = 0.92f))
                    .clickable { jumping = true }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
        SpotCard(saved, Modifier.align(Alignment.TopEnd)) { s ->
            scope.launch { state.scrollToItem(ReadingSpots.goal(s, node.mtime, doc.pages).item) }
        }
    }

    if (jumping) {
        GoToPageDialog(
            pages = doc.pages,
            onGo = { target ->
                jumping = false
                scope.launch { state.scrollToItem(target) }
            },
            onDismiss = { jumping = false },
        )
    }
}
