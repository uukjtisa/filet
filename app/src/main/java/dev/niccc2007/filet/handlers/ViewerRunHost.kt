package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.launch

/**
 * What a viewer drawn inside a run knows about it.
 *
 * Null outside a run, which is how a viewer opened on its own stays exactly as it was: no
 * counter, no arrows, no swipe to hold.
 *
 * @param label "12 / 240".
 * @param hold stop or allow the swipe. A picture being edited or zoomed holds it, so a drag
 *   meant for the picture cannot throw away an edit or leave a zoomed view.
 * @param step go [-1] back or [1] on, where a swipe cannot: a playing video's drags are its
 *   own, for seeking, brightness and volume.
 */
class RunPage(
    val label: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val hold: (Boolean) -> Unit,
    val step: (Int) -> Unit,
    /** This page's file was deleted: take it out, standing on the one that took its place. */
    val drop: () -> Unit,
)

val LocalViewerRun = staticCompositionLocalOf<RunPage?> { null }

/** Hold the run's swipe while [hold]. Let go of it when this leaves the screen. */
@Composable
fun HoldRunSwipe(hold: Boolean) {
    val page = LocalViewerRun.current ?: return
    DisposableEffect(page, hold) {
        page.hold(hold)
        onDispose { if (hold) page.hold(false) }
    }
}

/**
 * The picture and video viewers for a run, side by side: a swipe moves to the next file of the
 * list the viewer was opened from. See [ViewerRun].
 *
 * Only the page that has settled is a real viewer. A page being swiped across is its poster -
 * the thumbnail, fitted - because decoding a full picture or preparing a video for a page that
 * is only passing is the cost of the swipe, paid every time, for a frame nobody stops on.
 *
 * A video swiped onto waits for a tap. Opening a run on a video plays it, as tapping one always
 * did; arriving at one by swiping is passing through, and a video that starts making noise as a
 * row of holiday photos goes by is the gallery equivalent of autoplay.
 */
@Composable
internal fun ViewerRunHost(vm: BrowserViewModel, run: ViewerRun) {
    val items = run.items
    // A new run - the viewer closed and something else opened - is a new pager, not the old
    // one at a stale page. By identity: `viewerMoved` keeps the same list as the run moves.
    // The bars, held once for the whole run - the pages inside stand down. See ImmersiveViewer.
    ImmersiveViewer(owner = true)
    key(System.identityHashCode(items)) {
        val pager = rememberPagerState(initialPage = run.index) { items.size }
        val scope = rememberCoroutineScope()
        val tapped = remember { run.index }
        var playing by remember { mutableStateOf(if (run.playOnOpen) setOf(tapped) else emptySet()) }
        var held by remember { mutableStateOf(false) }
        val settled = pager.settledPage

        LaunchedEffect(settled) {
            // Leaving a page puts its video back to its poster: coming back to it is arriving.
            playing = playing.filterTo(HashSet()) { it == settled }
            if (vm.openRequest.value?.run?.index != settled) vm.viewerMoved(run.at(settled))
        }

        // The gap between pages and a poster still decoding show the viewer's own ground, so a
        // swipe on grey does not flash black between two grey pictures.
        val grey by vm.prefs.viewerGrey.collectAsState()
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().background(ViewerGround.of(grey)),
            pageSpacing = 12.dp,
            userScrollEnabled = !held,
            key = { items[it].path.toString() },
        ) { page ->
            val node = items[page]
            val video = FileKind.of(node) == FileKind.VIDEO
            val info = remember(page, items.size) {
                RunPage(
                    label = "${page + 1} / ${items.size}",
                    hasPrevious = page > 0,
                    hasNext = page < items.lastIndex,
                    hold = { held = it },
                    step = { d ->
                        scope.launch { pager.animateScrollToPage((page + d).coerceIn(0, items.lastIndex)) }
                    },
                    // A new list, so a new pager - see the key above - standing where it should.
                    drop = {
                        val rest = run.at(page).without(page)
                        if (rest == null) vm.closeHandler() else vm.viewerMoved(rest)
                    },
                )
            }
            CompositionLocalProvider(LocalViewerRun provides info) {
                // The posters stand on the picture viewer's ground and in its dark palette, so a
                // page passing under the finger looks like the page it is about to become.
                when {
                    page != settled -> dev.niccc2007.filet.ui.theme.DarkGround(ViewerGround.of(grey)) {
                        RunPoster(node, video)
                    }
                    video && page !in playing -> dev.niccc2007.filet.ui.theme.DarkGround(ViewerGround.of(grey)) {
                        VideoPoster(vm, node) { playing = playing + page }
                    }
                    video -> {
                        // The player's own drags seek and set brightness and volume; its bar has
                        // the arrows for moving on. See RunPage.step.
                        HoldRunSwipe(true)
                        MediaScreen(vm, node)
                    }
                    else -> ImageViewerScreen(vm, node, placeholder = { RunPoster(node, video = false) })
                }
            }
        }
    }
}

/**
 * A file of the run as its thumbnail, fitted, with a play mark if it is a video.
 *
 * Also what the picture viewer shows while the full image decodes, so arriving at a photo goes
 * from soft to sharp rather than through a spinner on black.
 */
@Composable
internal fun RunPoster(node: VNode, video: Boolean) {
    val context = LocalContext.current
    val known = remember(node.path) { Thumbnails.peek(node.path, node.size, node.mtime, POSTER_PX)?.bitmap }
    var bitmap by remember(node.path) { mutableStateOf<Bitmap?>(known) }
    if (known == null) {
        LaunchedEffect(node.path) {
            val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
            bitmap = runCatching {
                Thumbnails.load(context, graph.vfs, node.path, node.size, node.mtime, POSTER_PX)
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), node.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (video) PlayMark()
    }
}

/**
 * A video arrived at by swiping: its poster, the bar, and a play button. A tap anywhere on the
 * picture plays it, as the button does - the button is where the eye goes, the picture is where
 * the thumb already is.
 */
@Composable
private fun VideoPoster(vm: BrowserViewModel, node: VNode, onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(onClick = onPlay)) { RunPoster(node, video = true) }
        ViewerBar(
            title = node.name,
            subtitle = humanSize(node.size),
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Share, "Share") { vm.share(node) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
        }
    }
}

@Composable
private fun PlayMark() {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        // White on a dark disc whatever the theme: this sits on a photograph, not on a panel.
        Icon(FiletIcons.Play, "Play", tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** The poster's longest edge: sharp enough to stand in for the picture for a moment. */
private const val POSTER_PX = 720
