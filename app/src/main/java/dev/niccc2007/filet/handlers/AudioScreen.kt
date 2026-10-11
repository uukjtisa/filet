package dev.niccc2007.filet.handlers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import dev.niccc2007.filet.playback.marqueeFade
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
    val isVideo = track?.medium == dev.niccc2007.filet.playback.Medium.VIDEO
    // Only collected for a video, but a composable cannot be conditional about that; the flow
    // is null the rest of the time and the peek is not drawn, so it costs nothing.
    val source by playback.source.collectAsStateWithLifecycle()
    val face = dev.niccc2007.filet.playback.PlayerFace.of(
        vm.prefs.playerFace.collectAsStateWithLifecycle().value,
    )
    val uninterrupted by vm.prefs.uninterrupted.collectAsStateWithLifecycle()
    val classic = face == dev.niccc2007.filet.playback.PlayerFace.CLASSIC

    /**
     * The colour borrowed from the artwork, and the one to draw on top of it.
     *
     * The theme accent whenever the artwork has no colour worth taking - see `ArtPalette`
     * for why a sampled colour has a contrast floor forced onto it before it gets here.
     * Classic keeps the theme accent on purpose: a face whose whole point is that it does
     * not react to the artwork should not have reacting controls.
     */
    val (tint, onTint) = dev.niccc2007.filet.playback.rememberArtPalette(
        art = if (classic) null else art,
        fallback = colors.accent,
        onFallback = MaterialTheme.colorScheme.onPrimary,
    )

    /** How much of the title does not fit, measured, so the marquee travels the right way. */
    var titleOverflow by remember(track?.id) { mutableStateOf(0f) }
    val marquee = dev.niccc2007.filet.playback.rememberMarquee(titleOverflow)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The light, under everything. Absent on Classic, which is what makes it classic.
        if (!classic) {
            dev.niccc2007.filet.playback.AmbientBackdrop(art)
            dev.niccc2007.filet.playback.AmbientVeil(MaterialTheme.colorScheme.background)
        }
        Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = track?.title ?: node.name,
            subtitle = listOfNotNull(
                "Uninterrupted".takeIf { uninterrupted },
                "${state.position} of ${state.size}".takeIf { state.canStep },
            ).joinToString("  ·  "),
            onClose = { vm.closeHandler() },
        ) {
            // Uninterrupted lives in the BAR, not in a row of chips with speed and a
            // sleep timer. It is a mode, and a mode belongs in the chrome where its state
            // can be read without anything being opened - and it is not trivia: it is the
            // switch that decides whether a phone call can take the sound away.
            //
            // The same preference Settings and the video player write, so turning it on
            // here is on there.
            ViewerAction(
                FiletIcons.Shield,
                if (uninterrupted) "Uninterrupted - nothing will pause this" else "Uninterrupted playback",
                active = uninterrupted,
            ) { vm.prefs.setUninterrupted(!uninterrupted) }
            ViewerAction(FiletIcons.Rows, "Queue", enabled = state.canStep) {
                showQueue = !showQueue
            }
            ViewerAction(FiletIcons.Link, "Open in another app") {
                vm.openTrackExternally(track, node)
            }
            ViewerAction(FiletIcons.Share, "Share") { vm.shareTrack(track, node) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.propertiesOfTrack(track, node) }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centred, not stacked from the top. The art is capped by whichever of width and
            // height runs out first, so on a tall phone there is slack, and hanging the whole
            // player off the top leaves it in the middle of the screen with a hole under it.
            verticalArrangement = Arrangement.Center,
        ) {
            /**
             * The square: cover art, or a few seconds of the video on a loop.
             *
             * Written as one lambda used in two places rather than two copies, because it is
             * also what the peek shows while its first frame is still decoding - and a
             * placeholder that differs from the thing it precedes is a visible swap.
             */
            val still: @Composable () -> Unit = {
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isVideo) FiletIcons.Video else FiletIcons.Audio, null,
                            tint = colors.accent.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxSize(0.32f),
                        )
                    }
                }
            }

            // Shrinks when paused, and the shadow goes with it.
            //
            // The only feedback a player screen can give about its own state without words:
            // playing is bigger. It is also why the shadow is animated rather than fixed -
            // a card that changes size under a constant shadow reads as a scaling bitmap
            // rather than as an object lifting.
            val lift by animateFloatAsState(
                if (state.playing) 1f else 0.9f,
                animationSpec = tween(380),
                label = "art lift",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .then(
                        if (classic) Modifier
                        else Modifier
                            .graphicsLayer { scaleX = lift; scaleY = lift }
                            .shadow(
                                elevation = (34 * lift * lift).dp,
                                shape = RoundedCornerShape(20.dp),
                                clip = false,
                            ),
                    )
                    .clip(RoundedCornerShape(if (classic) 14.dp else 20.dp))
                    .background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (isVideo) {
                    dev.niccc2007.filet.playback.VideoPeekView(
                        source = source,
                        playing = state.playing,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = still,
                    )
                } else {
                    still()
                }
            }

            // Said out loud, because the screen otherwise claims to be a music player holding
            // a video. The row is also where Watch lives: the one thing this screen cannot do
            // is show you the picture at any size, and the way back has to be here rather than
            // in the file list you are no longer looking at.
            if (isVideo) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FiletIcons.Video, null,
                        tint = colors.fg3,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Playing as audio", fontSize = 11.sp, color = colors.fg3)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Watch",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable {
                                vm.openVideoOfTrack(track)
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // A filename is not a song title: it is long, it has no spaces and it cannot
            // wrap, so ellipsis always lost the end of it - which on a name like
            // "VID_20261009_120104" is the only part that differs from its neighbours.
            //
            // So it scrolls, out and back, with a hold at each end: a marquee that turns
            // round the instant it arrives is unreadable at exactly the moment the end is
            // finally visible. Measured rather than guessed, and still rather than moving
            // whenever the name fits.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .then(
                        if (classic) Modifier else Modifier.marqueeFade(titleOverflow > 2f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    track?.title ?: node.name,
                    fontSize = if (classic) 19.sp else 21.sp,
                    fontWeight = if (classic) FontWeight.SemiBold else FontWeight.Bold,
                    letterSpacing = if (classic) 0.sp else (-0.4).sp,
                    maxLines = if (classic) 2 else 1,
                    softWrap = classic,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.graphicsLayer {
                        translationX = if (classic) 0f else marquee
                    },
                    onTextLayout = { layout ->
                        // The only number the marquee needs, and it depends on the font, the
                        // width and the name - so it is read off the laid-out text rather
                        // than estimated from a character count.
                        if (!classic) {
                            titleOverflow = maxOf(
                                0f,
                                layout.multiParagraph.maxIntrinsicWidth - layout.size.width,
                            )
                        }
                    },
                )
            }
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
                    fill = tint, onFill = onTint,
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
                    // See ArtPalette: borrowed from the artwork, with a contrast floor
                    // forced onto it, so a dark track cannot produce an invisible button.
                    fill = tint,
                    onFill = onTint,
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
                    fill = tint, onFill = onTint,
                ) {
                    // The cycle lives on the player, because the notification's repeat
                    // button runs the same one. Two copies of off-all-one-off is two places
                    // for the order to drift.
                    playback.cycleRepeat()
                }
            }
            if (state.repeat == Repeat.ONE) {
                Text("Repeating this track", fontSize = 9.5.sp, color = tint)
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
