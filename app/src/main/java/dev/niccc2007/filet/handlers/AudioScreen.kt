package dev.niccc2007.filet.handlers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.playback.Repeat
import dev.niccc2007.filet.playback.Track
import dev.niccc2007.filet.playback.tracksOf
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * The music player.
 *
 * The cover art is the screen, the track has a name and an artist rather than a filename, the
 * bar can be scrubbed, and the folder it was opened from is a queue you can see and move
 * around in.
 *
 * ## This screen does not own the player any more
 *
 * It used to. Eleven `remember(node.path)` variables and a `MediaPlayer` held in a
 * `DisposableEffect` that released it - so closing the screen stopped the music and there was
 * no way to put a queue in the background. The player is now `graph.playback`, which outlives
 * every composition, and this file draws it.
 *
 * What that changes about reading the code: nothing here decides anything. A press calls a
 * method on the player and the new state arrives back through the flow, which is also what the
 * strip above the bottom bar and the notification do. Three surfaces, one source, no copies to
 * fall out of step.
 *
 * The one piece of state that stays local is the scrub handle. A drag in progress belongs to
 * the finger doing it, and committing every intermediate value would make the player chase it.
 */

@Composable
fun AudioScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val playback = vm.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val art by playback.art.collectAsStateWithLifecycle()

    /** Where the handle is while it is being dragged, or null when it is not. */
    var scrubTo by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }

    // Opening the folder as a queue, once.
    //
    // Keyed on the node, and `play` itself is a no-op when it is already on this track - so
    // coming back to the screen from the strip does not restart what is playing. That matters:
    // a view that reloads the player when it opens would have reproduced the reported bug
    // through a different door.
    LaunchedEffect(node.path) {
        if (playback.isCurrent(node.path.toString())) return@LaunchedEffect
        val siblings = vm.siblingsOf(node)
        val q = audioQueue(siblings, node, vm.currentSort)
        playback.play(tracksOf(q.items), q.index)
    }

    val track = state.current
    val shownPosition = scrubTo ?: state.positionMs

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ViewerBar(
            title = track?.title ?: node.name,
            subtitle = if (state.canStep) "${state.position} of ${state.size}" else "",
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Rows, "Queue", enabled = state.canStep) {
                showQueue = !showQueue
            }
            ViewerAction(FiletIcons.Link, "Open in another app") {
                vm.openExternally(vm.nodeOfTrack(track) ?: node, force = true)
            }
            ViewerAction(FiletIcons.Share, "Share") { vm.share(vm.nodeOfTrack(track) ?: node) }
            ViewerAction(FiletIcons.Info, "Properties") {
                vm.showProperties(vm.nodeOfTrack(track) ?: node)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centred, not stacked from the top. The art is capped by whichever of width and
            // height runs out first, so on a tall phone there is slack, and hanging the whole
            // player off the top leaves it in the middle of the screen with a hole under it.
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                val picture = art
                if (picture != null) {
                    Image(
                        picture.asImageBitmap(), "Cover art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // No embedded art is normal, not a failure, so the placeholder is a piece
                    // of the design rather than an error state.
                    Icon(
                        FiletIcons.Audio, null,
                        tint = colors.accent.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxSize(0.32f),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            Text(
                track?.title ?: node.name,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(track?.artist, track?.album).joinToString("  ·  ").ifEmpty {
                    node.path.parent?.name ?: ""
                },
                fontSize = 12.5.sp,
                color = colors.fg2,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, fontSize = 12.sp, color = colors.warn, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(16.dp))

            ScrubBar(
                positionMs = shownPosition,
                durationMs = state.durationMs,
                enabled = state.durationMs > 0,
                onScrub = { scrubTo = it },
                onScrubEnd = { target ->
                    scrubTo = null
                    playback.seekTo(target)
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    clockOf(shownPosition),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    remainingOf(shownPosition, state.durationMs),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    FiletIcons.Shuffle, "Shuffle", size = 38.dp,
                    active = state.shuffle, enabled = state.size > 2,
                ) { playback.setShuffle(!state.shuffle) }

                // Restart-then-jump is the player's rule, not the screen's. It used to be a
                // `position > 3_000L` branch written here, which meant the notification's
                // Previous button and this one behaved differently.
                TransportButton(FiletIcons.Prev, "Previous", size = 46.dp) { playback.previous() }

                TransportButton(
                    if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                    if (state.playing) "Pause" else "Play",
                    filled = true,
                    size = 64.dp,
                    enabled = track != null,
                ) { playback.toggle() }

                TransportButton(FiletIcons.Next, "Next", size = 46.dp) { playback.next() }

                TransportButton(
                    FiletIcons.Repeat,
                    when (state.repeat) {
                        Repeat.OFF -> "Repeat off"
                        Repeat.ALL -> "Repeat queue"
                        Repeat.ONE -> "Repeat track"
                    },
                    size = 38.dp,
                    active = state.repeat != Repeat.OFF,
                ) {
                    playback.setRepeat(
                        when (state.repeat) {
                            Repeat.OFF -> Repeat.ALL
                            Repeat.ALL -> Repeat.ONE
                            Repeat.ONE -> Repeat.OFF
                        },
                    )
                }
            }
            if (state.repeat == Repeat.ONE) {
                Text("Repeating this track", fontSize = 9.5.sp, color = colors.accent)
            }
            Spacer(Modifier.height(14.dp))
        }

        AnimatedVisibility(visible = showQueue) {
            QueuePanel(
                tracks = state.queue,
                index = state.index,
                onPick = { i -> playback.play(state.queue, i) },
            )
        }
    }
}

/** The folder, as a list you can jump around in. Capped in height so it never eats the art. */
@Composable
private fun QueuePanel(tracks: List<Track>, index: Int, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    if (tracks.isEmpty()) return
    val state = rememberLazyListState()

    LaunchedEffect(index) {
        // Keep the playing row on screen as the queue advances, without yanking the list when
        // it has been scrolled somewhere else on purpose.
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && index !in visible.first().index..visible.last().index) {
            runCatching { state.animateScrollToItem(index) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(colors.raised)
    ) {
        Text(
            "Up next  ·  ${tracks.size} tracks",
            fontSize = 9.5.sp,
            letterSpacing = 0.8.sp,
            color = colors.fg3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                val here = i == index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (here) colors.sel else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (here) colors.accent else colors.fg3,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        t.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (here) colors.accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (here) {
                        Icon(
                            FiletIcons.PlaySolid, null,
                            tint = colors.accent, modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
