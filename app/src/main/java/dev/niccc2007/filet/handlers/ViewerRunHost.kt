package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
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
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.launch

/**
 * What a viewer drawn inside a run knows about it.
 *
 * Null outside a run, which is how a viewer opened on its own stays exactly as it was: no
 * counter, no arrows, no swipe to hold.
 *
 * @param label "12 / 240".
 * @param hold true to hold the swipe, false to let go of that hold; the swipe is held while
 *   anything holds it. Use [HoldRunSwipe], which pairs them. A picture being edited or zoomed
 *   holds it, so a drag meant for the picture cannot throw away an edit or leave a zoomed view,
 *   and so does a playing video.
 * @param step go [-1] back or [1] on, where a swipe cannot: a playing video's drags are its
 *   own, for seeking, brightness and volume.
 * @param arrived this page was reached by the arrows or a swipe, not opened. See
 *   [VideoStart.arrived].
 */
class RunPage(
    val label: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val hold: (Boolean) -> Unit,
    val step: (Int) -> Unit,
    /** This page's file was deleted: take it out, standing on the one that took its place. */
    val drop: () -> Unit,
    val arrived: Boolean = false,
    /** The whole run has photos and videos both, so which of them a swipe reaches is a choice. */
    val mixed: Boolean = false,
)

val LocalViewerRun = staticCompositionLocalOf<RunPage?> { null }

/**
 * Hold the run's swipe while [hold]. Let go of it when this leaves the screen.
 *
 * Counted, not set: two holders on one page - a video's screen and the music face it dissolves
 * into, both up for the length of the fade - each let go of their own hold only. With one flag,
 * the face that left would set it free while the one that stayed was still playing, and the next
 * drag on the video would swipe the page away.
 */
@Composable
fun HoldRunSwipe(hold: Boolean) {
    val page = LocalViewerRun.current ?: return
    DisposableEffect(page, hold) {
        if (hold) page.hold(true)
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
 * A settled video is its screen, which opens on its silent preview and plays when tapped - the
 * one the run opened on always, one arrived at by the arrows or a swipe as the Next video setting
 * says. See [VideoStart]. The preview lets the swipe through; a playing video holds it.
 *
 * A run of photos and videos both can be narrowed to the kind it stands on ([RunKindsRow]). The
 * pages are then that kind only, and every step and delete is carried back to the whole run by
 * the file, so the run the viewer holds is always all of it and widening again loses nothing.
 */
@Composable
internal fun ViewerRunHost(vm: BrowserViewModel, run: ViewerRun) {
    val onlyKind by vm.prefs.viewerOnlyKind.collectAsState()
    val mixed = remember(run.items) { run.mixed(::runKind) }
    val narrowed = onlyKind && mixed
    // Kept while the swipe stays inside it, which a narrowed run always does, so the pager below
    // is not rebuilt at every step. Both kinds is the run's own list, exactly as before.
    val items = remember(run.items, narrowed, if (narrowed) runKind(run.current) else null) {
        if (narrowed) run.onlyKind(::runKind).items else run.items
    }
    // A new run - the viewer closed and something else opened - is a new pager, not the old
    // one at a stale page. By identity: `viewerMoved` keeps the same list as the run moves.
    // The bars, held once for the whole run - the pages inside stand down. See ImmersiveViewer.
    ImmersiveViewer(owner = true)
    key(System.identityHashCode(items)) {
        val start = items.indexOfFirst { it.path == run.current.path }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = start) { items.size }
        val scope = rememberCoroutineScope()
        val tapped = remember { start }
        // Whether the run has stood anywhere but the page it opened on: after that, coming back
        // to that page is arriving at it. See VideoStart.arrived.
        var movedOff by remember { mutableStateOf(false) }
        // How many things on the settled page hold the swipe. See HoldRunSwipe.
        var holds by remember { mutableIntStateOf(0) }
        val settled = pager.settledPage

        LaunchedEffect(settled) {
            if (settled != tapped) movedOff = true
            // By the file, so a narrowed page lands on its place in the whole run.
            val whole = run.indexOf(items[settled])
            if (whole >= 0 && vm.openRequest.value?.run?.index != whole) vm.viewerMoved(run.at(whole))
        }

        // The gap between pages and a poster still decoding show the viewer's own ground, so a
        // swipe on grey does not flash black between two grey pictures.
        val grey by vm.prefs.viewerGrey.collectAsState()
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().background(ViewerGround.of(grey)),
            pageSpacing = 12.dp,
            userScrollEnabled = holds <= 0,
            key = { items[it].path.toString() },
        ) { page ->
            val node = items[page]
            val video = FileKind.of(node) == FileKind.VIDEO
            val info = remember(page, items.size, movedOff, mixed) {
                RunPage(
                    label = "${page + 1} / ${items.size}",
                    hasPrevious = page > 0,
                    hasNext = page < items.lastIndex,
                    hold = { on -> holds = (holds + if (on) 1 else -1).coerceAtLeast(0) },
                    step = { d ->
                        scope.launch { pager.animateScrollToPage((page + d).coerceIn(0, items.lastIndex)) }
                    },
                    // A new list, so a new pager - see the key above - standing where it should;
                    // narrowed, on the next file of the same kind.
                    drop = {
                        val whole = run.indexOf(node)
                        val kind = runKind(node)
                        val rest = if (narrowed) run.at(whole).without(whole) { runKind(it) == kind } else run.at(whole).without(whole)
                        if (rest == null) vm.closeHandler() else vm.viewerMoved(rest)
                    },
                    arrived = VideoStart.arrived(page, tapped, movedOff),
                    mixed = mixed,
                )
            }
            CompositionLocalProvider(LocalViewerRun provides info) {
                // The posters stand on the picture viewer's ground and in its dark palette, so a
                // page passing under the finger looks like the page it is about to become.
                when {
                    page != settled -> dev.niccc2007.filet.ui.theme.DarkGround(ViewerGround.of(grey)) {
                        RunPoster(node, video)
                    }
                    // Its preview, then its player, which holds the swipe for its own drags; both
                    // bars have the arrows for moving on. See RunPage.step and VideoScreen.
                    video -> MediaScreen(vm, node)
                    else -> ImageViewerScreen(vm, node, placeholder = { RunPoster(node, video = false) })
                }
            }
        }
    }
}

/** What a run tells its kinds apart by: a picture or a video. */
private fun runKind(node: VNode): FileKind = FileKind.of(node)

/**
 * Photos and videos together, or only the kind on screen, for a run that has both: a menu row
 * named for what it switches to, as the viewer's other switches are. Remembered for every run
 * after. Nothing at all on a run of one kind, where there is nothing to choose.
 *
 * Not on a playing video's menu: the swipe is held while it plays, so there is nothing for it
 * to change until the video is back at its preview.
 */
@Composable
internal fun RunKindsRow(vm: BrowserViewModel, node: VNode, close: () -> Unit) {
    val page = LocalViewerRun.current ?: return
    if (!page.mixed) return
    val only by vm.prefs.viewerOnlyKind.collectAsState()
    val these = if (runKind(node) == FileKind.VIDEO) "videos" else "photos"
    PopRow(
        FiletIcons.Grid,
        if (only) "Photos and videos together" else "Only this kind",
        detail = if (only) "A swipe goes through both again." else "A swipe stays on $these.",
    ) {
        vm.prefs.setViewerOnlyKind(!only)
        close()
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

/** The play button over a video's picture: a page passing in a swipe, and the preview's. */
@Composable
internal fun PlayMark() {
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
