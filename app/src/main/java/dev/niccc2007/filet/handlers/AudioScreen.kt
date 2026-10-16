package dev.niccc2007.filet.handlers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.playback.Medium
import dev.niccc2007.filet.playback.PlayerFace
import dev.niccc2007.filet.playback.Playlists
import dev.niccc2007.filet.playback.Repeat
import dev.niccc2007.filet.playback.tracksOf
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.delay

/**
 * The music player, in four faces.
 *
 * The cover art is the screen, the track has a name and an artist rather than a filename, the
 * bar can be scrubbed, and the folder it was opened from is a queue you can see and move
 * around in.
 *
 * ## This screen does not own the player
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
 *
 * ## And it does not own its own controls either
 *
 * Every piece a face draws - the top bar, the artwork, the title block, the scrub, the
 * transport, the chips, the queue - is a composable in `PlayerParts.kt`. A face here is a
 * dozen lines of arrangement. That is the only thing that makes four of them affordable: the
 * fourth copy of a transport row is the one that silently stops matching the other three.
 */

@Composable
fun AudioScreen(vm: BrowserViewModel, node: VNode) {
    val playback = vm.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val art by playback.art.collectAsStateWithLifecycle()
    val source by playback.source.collectAsStateWithLifecycle()
    val colors = Filet.colors

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
        playback.play(tracksOf(q.items), q.index, from = folderContext(node.path))
    }

    val face = PlayerFace.of(vm.prefs.playerFace.collectAsStateWithLifecycle().value)
    val uninterrupted by vm.prefs.uninterrupted.collectAsStateWithLifecycle()
    val glow by vm.prefs.livingGlow.collectAsStateWithLifecycle()
    val glowLevel = dev.niccc2007.filet.playback.GlowLevel.of(
        vm.prefs.glowLevel.collectAsStateWithLifecycle().value,
    )
    val speed by playback.speed.collectAsStateWithLifecycle()
    val sleepAt by playback.sleepAt.collectAsStateWithLifecycle()
    val pitch = dev.niccc2007.filet.playback.PitchMode.of(
        vm.prefs.pitchMode.collectAsStateWithLifecycle().value,
    )
    val maxSpeed by playback.maxSpeedFlow.collectAsStateWithLifecycle()
    // The heart's list. It used to be Bookmarks, which is a list of PLACES shown beside
    // folders and shortcuts - so hearting a song put a file somewhere you cannot press
    // play. Favourites is a playlist, which is the thing a heart is for.
    val lists by vm.playlists.items.collectAsStateWithLifecycle()

    val track = state.current
    val isVideo = track?.medium == Medium.VIDEO

    /**
     * The path of what is playing NOW, which after one press of next is not [node].
     *
     * Everything on this screen that acts on "this track" - the heart, add to a playlist -
     * has to read this and not the node the screen was opened with. A track id IS its path;
     * see `tracksOf`.
     */
    val livePath = remember(track?.id, node.path) {
        track?.id?.let { id ->
            runCatching { dev.niccc2007.filet.vfs.VPath.parse(id) }.getOrNull()
        } ?: node.path
    }

    /** Where the handle is while it is being dragged, or null when it is not. */
    var scrubTo by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var saveQueue by remember { mutableStateOf(false) }
    val shownPosition = scrubTo ?: state.positionMs

    /**
     * The colour borrowed from the artwork, and the one to draw on top of it.
     *
     * The theme accent whenever the artwork has no colour worth taking - see `ArtPalette`
     * for why a sampled colour has a contrast floor forced onto it before it gets here.
     * Classic keeps the theme accent on purpose: a face whose whole point is that it does
     * not react to the artwork should not have reacting controls.
     */
    val (tint, onTint) = dev.niccc2007.filet.playback.rememberArtPalette(
        art = if (face.ambient) art else null,
        fallback = colors.accent,
        onFallback = MaterialTheme.colorScheme.onPrimary,
    )

    // Recomputed once a minute, not once a frame: the chip shows whole minutes and the player
    // already recomposes five times a second off the position ticker.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(sleepAt) {
        while (sleepAt > 0L) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(5_000L)
        }
    }
    val sleepLeft = sleepMinutesLeft(sleepAt, now)

    val folderLine = remember(node.path) { folderContext(node.path) }

    val title = track?.title ?: node.name
    val sub = buildString {
        val tags = listOfNotNull(track?.artist, track?.album).joinToString("  ·  ")
        append(tags.ifEmpty { node.path.parent?.name.orEmpty() })
        // Said out loud, because the screen otherwise claims to be a music player holding a
        // video. The way back to the picture is in the same breath - this screen is the one
        // thing that cannot show it, and the file list is no longer on screen to go through.
        if (isVideo) {
            if (isNotEmpty()) append("  ·  ")
            append("playing as audio")
        }
    }

    val ctx = PlayerCtx(
        vm = vm,
        node = node,
        playback = playback,
        state = state,
        art = art,
        source = source,
        isVideo = isVideo,
        livePath = livePath,
        face = face,
        tint = tint,
        onTint = onTint,
        uninterrupted = uninterrupted,
        glow = glow,
        glowLevel = glowLevel,
        speed = speed,
        sleepLeft = sleepLeft,
        maxSpeed = maxSpeed,
        pitch = pitch,
        bookmarked = lists.firstOrNull { it.id == Playlists.FAVOURITES }
            ?.contains(livePath) == true,
        title = title,
        sub = sub,
        folderLine = folderLine,
        shownPosition = shownPosition,
        onScrub = { scrubTo = it },
        onScrubEnd = { target -> scrubTo = null; playback.seekTo(target) },
        onQueue = { showQueue = !showQueue },
        onWatch = { vm.openVideoOfTrack(track) },
        onSaveQueue = { saveQueue = true },
    )

    // The status bar goes, the navigation bar stays. A player is a screen you leave from, so
    // hiding the way out to gain a clock's worth of height is a bad trade - but the top strip
    // is worth having, because the artwork's light should reach the corner instead of
    // stopping under a black bar.
    ImmersiveViewer(active = face.ambient, statusOnly = true)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (face == PlayerFace.CLASSIC) {
            dev.niccc2007.filet.playback.LivingGlow(
                base = colors.accent,
                on = glow,
                running = state.playing,
                strength = dev.niccc2007.filet.playback.GLOW_OVER_ART * glowLevel.scale,
            )
        }

        when (face) {
            PlayerFace.CLASSIC -> ClassicFace(ctx)
            PlayerFace.AMBIENT -> AmbientFace(ctx)
            PlayerFace.EDITORIAL -> EditorialFace(ctx)
            PlayerFace.IMMERSIVE -> ImmersiveFace(ctx)
            PlayerFace.AURORA -> AuroraFace(ctx)
        }

        // One sheet for all four, drawn last so it is over everything. It covers rather than
        // rearranging: the old panel pushed the artwork and the transport up by 190dp every
        // time it opened, which moved the Play button out from under the thumb about to press
        // it.
        if (saveQueue) {
            // Named from the folder the queue came from, which is almost always the name
            // somebody was going to type anyway.
            SaveQueueDialog(
                suggested = folderLine.substringAfterLast(" / "),
                onDismiss = { saveQueue = false },
            ) { name -> saveQueue = false; vm.saveQueueAsPlaylist(name) }
        }

        QueueSheet(
            tracks = state.queue,
            index = state.index,
            tint = tint,
            visible = showQueue && state.queue.isNotEmpty(),
            onPick = { i -> playback.play(state.queue, i) },
            onClose = { showQueue = false },
        )
    }
}

/**
 * Where the queue came from, as a human would say it.
 *
 * The last two segments of the path, minus the ones that are plumbing. `/storage/emulated/0`
 * is three segments of Android bookkeeping that mean "this phone", so a track in
 * `/storage/emulated/0/Music` rendered as "0 / Music" - which names a directory nobody has
 * ever typed and reads as a bug.
 *
 * Pure, so the cases can be pinned: the emulated-storage prefix, a one-deep folder, a track
 * sitting at the root of a volume, and a remote path with no local shape at all.
 */
fun folderContext(path: dev.niccc2007.filet.vfs.VPath): String {
    val parts = path.path.split('/')
        .dropLast(1)
        .filter { it.isNotEmpty() }
        // The bookkeeping. `0` is the user id, and on a multi-user phone it can be `10` or
        // `11`, so it is matched as a number rather than as the literal.
        .filterNot { it == "storage" || it == "emulated" || it.toIntOrNull() != null }
    return when {
        parts.isEmpty() -> path.scheme
        parts.size == 1 -> parts.last()
        else -> parts.takeLast(2).joinToString(" / ")
    }
}

/**
 * Everything a face needs, in one value.
 *
 * Not a style choice: a face took twenty-one parameters when they were spelled out, and a
 * twenty-one parameter call site is where the fourth face gets one of them wrong. Collecting
 * them also makes the contract explicit - a face may READ all of this and may call these
 * lambdas, and it may not reach for the ViewModel to do anything else.
 */
private class PlayerCtx(
    val vm: BrowserViewModel,
    val node: VNode,
    val playback: dev.niccc2007.filet.playback.Playback,
    val state: dev.niccc2007.filet.playback.NowPlaying,
    val art: android.graphics.Bitmap?,
    val source: android.net.Uri?,
    val isVideo: Boolean,
    val livePath: dev.niccc2007.filet.vfs.VPath,
    val face: PlayerFace,
    val tint: androidx.compose.ui.graphics.Color,
    val onTint: androidx.compose.ui.graphics.Color,
    val uninterrupted: Boolean,
    val glow: Boolean,
    val glowLevel: dev.niccc2007.filet.playback.GlowLevel,
    val speed: Float,
    val sleepLeft: Int,
    val maxSpeed: Float,
    val pitch: dev.niccc2007.filet.playback.PitchMode,
    val bookmarked: Boolean,
    val title: String,
    val sub: String,
    val folderLine: String,
    val shownPosition: Long,
    val onScrub: (Long) -> Unit,
    val onScrubEnd: (Long) -> Unit,
    val onQueue: () -> Unit,
    val onWatch: () -> Unit,
    val onSaveQueue: () -> Unit,
)

/** The bar the three ambient faces share, wired to this screen's actions. */
@Composable
private fun FaceTopBar(c: PlayerCtx) {
    PlayerTopBar(
        eyebrow = if (c.uninterrupted) "UNINTERRUPTED  ·  PLAYING FROM" else "PLAYING FROM",
        eyebrowLit = c.uninterrupted,
        context = c.state.from.ifBlank { c.folderLine },
        uninterrupted = c.uninterrupted,
        onClose = { c.vm.closeHandler() },
        onUninterrupted = { c.vm.prefs.setUninterrupted(!c.uninterrupted) },
        tint = c.tint,
    ) {
        PlayerOverflow(
            face = c.face,
            onFace = { c.vm.prefs.setPlayerFace(it.id) },
            onExternal = { c.vm.openTrackExternally(c.state.current, c.node) },
            onShare = { c.vm.shareTrack(c.state.current, c.node) },
            onProperties = { c.vm.propertiesOfTrack(c.state.current, c.node) },
            onAddToPlaylist = { c.vm.askAddToPlaylistPath(c.livePath) },
            onSaveQueue = c.onSaveQueue.takeIf { c.state.canStep },
        )
    }
}

/** The error line, in the one place every face puts it. */
@Composable
private fun ColumnScope.FaceError(c: PlayerCtx) {
    c.state.error?.let { message ->
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = Filet.colors.warn,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── A: classic ────────────────────────────────────────────────────────────────

/**
 * What the player looked like before: flat ground, art in a box, everything centred.
 *
 * Kept whole rather than expressed as "Ambient without the backdrop". It is the fallback for
 * somebody who does not want the artwork deciding the colours, and a fallback assembled out of
 * the thing it is a fallback FROM is one shared bug away from not being one.
 */
@Composable
private fun ClassicFace(c: PlayerCtx) {
    val colors = Filet.colors
    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = c.title,
            subtitle = listOfNotNull(
                "Uninterrupted".takeIf { c.uninterrupted },
                "${c.state.position} of ${c.state.size}".takeIf { c.state.canStep },
            ).joinToString("  ·  "),
            onClose = { c.vm.closeHandler() },
        ) {
            ViewerAction(
                FiletIcons.Shield,
                if (c.uninterrupted) "Uninterrupted - nothing will pause this"
                else "Uninterrupted playback",
                active = c.uninterrupted,
            ) { c.vm.prefs.setUninterrupted(!c.uninterrupted) }
            ViewerAction(FiletIcons.Rows, "Queue", enabled = c.state.canStep, onClick = c.onQueue)
            // The SAME overflow the other three faces carry, and it is not a tidying
            // exercise: it holds the face switcher. Classic's bar had the three actions
            // spelled out and no overflow, so choosing Classic was a one-way door - the
            // only control that changes the look was on the looks it was not.
            PlayerOverflow(
                face = c.face,
                onFace = { c.vm.prefs.setPlayerFace(it.id) },
                onExternal = { c.vm.openTrackExternally(c.state.current, c.node) },
                onShare = { c.vm.shareTrack(c.state.current, c.node) },
                onProperties = { c.vm.propertiesOfTrack(c.state.current, c.node) },
                onAddToPlaylist = { c.vm.askAddToPlaylistPath(c.livePath) },
                onSaveQueue = c.onSaveQueue.takeIf { c.state.canStep },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centred, not stacked from the top. The art is capped by whichever of width and
            // height runs out first, so on a tall phone there is slack, and hanging the whole
            // player off the top leaves it in the middle of the screen with a hole under it.
            verticalArrangement = Arrangement.Center,
        ) {
            PlayerArt(
                art = c.art,
                isVideo = c.isVideo,
                playing = c.state.playing,
                source = c.source,
                plain = true,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f),
            )
            if (c.isVideo) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Playing as audio", fontSize = 11.sp, color = colors.fg3)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Watch",
                        fontSize = 11.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                            .clickable(onClick = c.onWatch)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                c.title,
                fontSize = 19.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                c.sub,
                fontSize = 12.5.sp,
                color = colors.fg2,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            FaceError(c)
            Spacer(Modifier.height(16.dp))
            PlayerScrub(c.shownPosition, c.state, c.tint, c.onScrub, c.onScrubEnd)
            Spacer(Modifier.height(10.dp))
            PlayerTransport(c.state, c.tint, c.onTint, scale = 0.94f, playback = c.playback)
            Spacer(Modifier.height(PlayerMetrics.CHIPS_TOP))
            // The chips are here too, even though "Classic" means what the player looked
            // like before and the row did not exist then. Leaving them off would make the
            // speed and the sleep timer unreachable on one of the four looks, and a control
            // that exists on three screens out of four is a control somebody will think
            // they imagined.
            PlayerChips(
                c.speed, c.maxSpeed, c.sleepLeft, c.pitch, c.tint, c.onTint, c.playback,
            ) { c.vm.prefs.setPitchMode(it.id) }
            Spacer(Modifier.height(14.dp))
        }
    }
}

// ── B: ambient ────────────────────────────────────────────────────────────────

/**
 * The approved face: the artwork lights the room, and the type sits under it, left-aligned.
 *
 * The first cut of this was Classic with a wash behind it, which is what "you just added an
 * ambient colouring to the old one" meant and it was fair. The arrangement is the face: an
 * eyebrow that names where the queue came from, a big shadowed square, a title block with the
 * heart in it, a chip row, and a queue you pull up rather than one that shoves the player up.
 */
@Composable
private fun BoxScope.AmbientFace(c: PlayerCtx) {
    dev.niccc2007.filet.playback.AmbientBackdrop(c.art)
    // Over the blurred artwork and under the veil: the lights have to be on top of the colour
    // they are moving, and under the ground that the text sits on.
    dev.niccc2007.filet.playback.LivingGlow(
        base = c.tint,
        on = c.glow,
        running = c.state.playing,
        strength = dev.niccc2007.filet.playback.GLOW_OVER_ART * c.glowLevel.scale,
    )
    dev.niccc2007.filet.playback.AmbientVeil(MaterialTheme.colorScheme.background)
    Column(Modifier.fillMaxSize()) {
        FaceTopBar(c)
        // `.artwrap{padding:14px 28px 0;flex:1;place-items:center}`
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                PlayerArt(
                    art = c.art,
                    isVideo = c.isVideo,
                    playing = c.state.playing,
                    source = c.source,
                    plain = false,
                    modifier = Modifier.fillMaxSize(),
                )
                if (c.isVideo) VideoBadge(onWatch = c.onWatch)
            }
        }
        Column(Modifier.padding(horizontal = PlayerMetrics.GUTTER)) {
            Spacer(Modifier.height(24.dp))
            PlayerTitleBlock(
                title = c.title,
                sub = c.sub,
                isVideo = c.isVideo,
                centred = false,
                bookmarked = c.bookmarked,
                tint = c.tint,
                onHeart = { c.vm.toggleFavourite(c.livePath) },
            )
            FaceError(c)
            Spacer(Modifier.height(PlayerMetrics.SCRUB_TOP))
            PlayerScrub(c.shownPosition, c.state, c.tint, c.onScrub, c.onScrubEnd)
            Spacer(Modifier.height(PlayerMetrics.TRANSPORT_TOP))
            PlayerTransport(c.state, c.tint, c.onTint, playback = c.playback)
            Spacer(Modifier.height(PlayerMetrics.CHIPS_TOP))
            PlayerChips(
                c.speed, c.maxSpeed, c.sleepLeft, c.pitch, c.tint, c.onTint, c.playback,
            ) { c.vm.prefs.setPitchMode(it.id) }
        }
        Spacer(Modifier.height(16.dp))
        if (c.state.canStep) QueueHandle(c.state, c.onQueue) else Spacer(Modifier.height(16.dp))
    }
}

// ── C: editorial ──────────────────────────────────────────────────────────────

/**
 * Type carries the screen; the art drops to 44% of the width beside it.
 *
 * Buys a lot of vertical room, which is worth most on a short phone and on a video - a 9:16
 * clip in a square box is mostly letterbox, and here the box is small enough that the waste
 * does not dominate.
 */
@Composable
private fun BoxScope.EditorialFace(c: PlayerCtx) {
    val colors = Filet.colors
    dev.niccc2007.filet.playback.AmbientBackdrop(c.art)
    // Over the blurred artwork and under the veil: the lights have to be on top of the colour
    // they are moving, and under the ground that the text sits on.
    dev.niccc2007.filet.playback.LivingGlow(
        base = c.tint,
        on = c.glow,
        running = c.state.playing,
        strength = dev.niccc2007.filet.playback.GLOW_OVER_ART * c.glowLevel.scale,
    )
    dev.niccc2007.filet.playback.AmbientVeil(MaterialTheme.colorScheme.background)
    Column(Modifier.fillMaxSize()) {
        FaceTopBar(c)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PlayerMetrics.GUTTER),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.44f).aspectRatio(1f)) {
                PlayerArt(
                    art = c.art,
                    isVideo = c.isVideo,
                    playing = c.state.playing,
                    source = c.source,
                    plain = false,
                    radius = 14.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                if (c.isVideo) VideoBadge(onWatch = c.onWatch, inset = 7.dp)
            }
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(
                    "NOW PLAYING",
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp,
                    color = colors.fg3,
                )
                Spacer(Modifier.height(6.dp))
                // 18sp, not the ambient face's 22: the column is 56% of the width here and
                // the same size would scroll on almost every name rather than on long ones.
                PlayerTitleBlock(
                    title = c.title,
                    sub = c.sub,
                    isVideo = c.isVideo,
                    centred = false,
                    titleSize = 18.sp,
                    bookmarked = c.bookmarked,
                    tint = c.tint,
                    onHeart = { c.vm.toggleFavourite(c.livePath) },
                )
            }
        }
        Column(Modifier.padding(horizontal = PlayerMetrics.GUTTER)) {
            FaceError(c)
            Spacer(Modifier.height(8.dp))
            PlayerScrub(c.shownPosition, c.state, c.tint, c.onScrub, c.onScrubEnd)
            Spacer(Modifier.height(PlayerMetrics.TRANSPORT_TOP))
            PlayerTransport(c.state, c.tint, c.onTint, playback = c.playback)
            Spacer(Modifier.height(PlayerMetrics.CHIPS_TOP))
            PlayerChips(
                c.speed, c.maxSpeed, c.sleepLeft, c.pitch, c.tint, c.onTint, c.playback,
            ) { c.vm.prefs.setPitchMode(it.id) }
        }
        // Weighted on BOTH sides, so the block sits in the middle of what is left rather
        // than against the queue handle. With only the leading spacer the whole top half of
        // a tall phone was empty and the face read as having fallen down the screen.
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        if (c.state.canStep) QueueHandle(c.state, c.onQueue) else Spacer(Modifier.height(16.dp))
    }
}

// ── E: aurora ─────────────────────────────────────────────────────────────────

/**
 * A drifting gradient, and the artwork as a disc that turns while it plays.
 *
 * The one face whose background is NOT the artwork, which is the point of it: the other three
 * ambient looks all resolve to "that picture, softened", and on a track with no embedded art
 * they resolve to nothing at all. This one is built from the sampled colour rather than from
 * the pixels, so a tagless recording still gets a face instead of a dark rectangle.
 *
 * Centred rather than left-aligned, because a disc is a circle and a circle with its text
 * ranged left looks like it slipped.
 */
@Composable
private fun BoxScope.AuroraFace(c: PlayerCtx) {
    dev.niccc2007.filet.playback.AuroraBackdrop(
        base = c.tint,
        ground = MaterialTheme.colorScheme.background,
        running = c.glow && c.state.playing,
    )
    // The turn. A full revolution every 24 seconds - a 33rpm record is 1.8, which at this
    // size is a blur and makes the artwork unreadable. This is slow enough to look still
    // until you watch it.
    val spin = rememberInfiniteTransition(label = "disc")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(24_000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "angle",
    )
    // Coasts rather than stopping dead. A disc that halts the instant the sound does reads as
    // a dropped frame; one that eases out reads as a platter.
    val turning by animateFloatAsState(
        if (c.state.playing) 1f else 0f,
        animationSpec = tween(1400),
        label = "coast",
    )
    var held by remember { mutableStateOf(0f) }
    if (c.state.playing) held = angle

    /**
     * How far the finger has turned the record, on top of whatever the platter was doing.
     *
     * Kept as its own number rather than folded into [held] so letting go does not snap: the
     * disc keeps the turn it was given and carries on spinning from there.
     */
    var scratched by remember(c.node.path) { mutableFloatStateOf(0f) }
    var scratching by remember(c.node.path) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        FaceTopBar(c)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        rotationZ = (if (c.state.playing) angle else held) + scratched
                    }
                    // Turn the record to move through the track.
                    //
                    // It PLAYS while it moves, which is the whole point and the thing the
                    // scrub bar cannot do: the handle moves and the sound arrives when you
                    // let go. Here you hear where you are going, so you stop when you hear
                    // the place rather than guessing at a bar. See `Scratch` for the
                    // geometry and for why the decoder is told twelve times a second rather
                    // than sixty.
                    .pointerInput(c.node.path) {
                        val centre = androidx.compose.ui.geometry.Offset(
                            size.width / 2f,
                            size.height / 2f,
                        )
                        var at = 0L
                        var told = 0L
                        detectDragGestures(
                            onDragStart = {
                                scratching = true
                                at = c.state.positionMs
                                told = 0L
                            },
                            onDragEnd = { scratching = false },
                            onDragCancel = { scratching = false },
                        ) { change, _ ->
                            change.consume()
                            val turned = dev.niccc2007.filet.playback.Scratch.turn(
                                change.previousPosition.x, change.previousPosition.y,
                                change.position.x, change.position.y,
                                centre.x, centre.y,
                            )
                            scratched += turned
                            at = dev.niccc2007.filet.playback.Scratch.seekTo(
                                at, turned, c.state.durationMs,
                            )
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - told >= dev.niccc2007.filet.playback.Scratch.SEEK_EVERY_MS) {
                                told = now
                                c.playback.seekTo(at)
                            }
                        }
                    },
            ) {
                PlayerArt(
                    art = c.art,
                    isVideo = c.isVideo,
                    playing = c.state.playing,
                    source = c.source,
                    plain = false,
                    radius = 1000.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                // The vinyl rim was tried here and reverted. It was a faithful record -
                // label, grooves, lead-in - and it cost the artwork more than half its
                // radius, which on the one face whose whole subject is the artwork is the
                // wrong trade. The picture stays big; the disc is said with a ring of light
                // instead of with a rim of black.
                //
                // The ring ANSWERS the sound: it brightens and widens with the level coming
                // out of the decoder, so it beats rather than pulses on a timer. See
                // `BeatRing`.
                dev.niccc2007.filet.playback.BeatRing(
                    tint = c.tint,
                    playback = c.playback,
                    // Lit while a finger is on it, whether or not the sound is running, so
                    // the record answers the hand immediately.
                    turning = if (scratching) 1f else turning,
                    modifier = Modifier.fillMaxSize(),
                )
                // The spindle. Small and dim: enough that the turn has a centre to be about,
                // not so much that there is a hole in the middle of the picture.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(c.tint.copy(alpha = 0.55f + 0.45f * turning)),
                    )
                }
            }
            if (c.isVideo) VideoBadge(onWatch = c.onWatch, inset = 0.dp)
        }
        Column(Modifier.padding(horizontal = PlayerMetrics.GUTTER)) {
            PlayerTitleBlock(
                title = c.title,
                sub = c.sub,
                isVideo = c.isVideo,
                centred = true,
                bookmarked = c.bookmarked,
                tint = c.tint,
                onHeart = { c.vm.toggleFavourite(c.livePath) },
            )
            FaceError(c)
            Spacer(Modifier.height(PlayerMetrics.SCRUB_TOP))
            PlayerScrub(c.shownPosition, c.state, c.tint, c.onScrub, c.onScrubEnd)
            Spacer(Modifier.height(PlayerMetrics.TRANSPORT_TOP))
            PlayerTransport(c.state, c.tint, c.onTint, playback = c.playback)
            Spacer(Modifier.height(PlayerMetrics.CHIPS_TOP))
            PlayerChips(
                c.speed, c.maxSpeed, c.sleepLeft, c.pitch, c.tint, c.onTint, c.playback,
            ) { c.vm.prefs.setPitchMode(it.id) }
        }
        Spacer(Modifier.height(16.dp))
        if (c.state.canStep) QueueHandle(c.state, c.onQueue) else Spacer(Modifier.height(16.dp))
    }
}

// ── D: immersive ──────────────────────────────────────────────────────────────

/**
 * The picture is the screen, and the controls tap away.
 *
 * Two background layers, not one: filling a tall screen with a square image crops it to a
 * band, worst of all for a 9:16 video frame, so the blurred copy fills and the sharp one is
 * contained on top of it. Nothing is stretched and nothing is lost - which was the specific
 * objection to the first version of this face.
 *
 * The controls FADE rather than unmount, so a tap always lands on something: a control that is
 * gone cannot be brought back by pressing where it was.
 */
@Composable
private fun BoxScope.ImmersiveFace(c: PlayerCtx) {
    var chrome by remember { mutableStateOf(true) }
    ImmersiveBackdrop(c.art, c.isVideo, c.state.playing, c.source)
    dev.niccc2007.filet.playback.LivingGlow(
        base = c.tint,
        on = c.glow,
        running = c.state.playing,
        strength = dev.niccc2007.filet.playback.GLOW_OVER_ART * c.glowLevel.scale,
    )
    ImmersiveVeil()
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { chrome = !chrome }
        },
    )
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            FaceTopBar(c)
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Column {
                if (c.state.error != null) {
                    Text(
                        c.state.error.orEmpty(),
                        fontSize = 12.sp,
                        color = Filet.colors.warn,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                }
                if (c.isVideo) {
                    // The one thing this face could not do. Every other face puts a WATCH
                    // badge on its artwork; here the artwork IS the screen, so the way back
                    // to the picture goes in the chrome with everything else.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WatchChip(onWatch = c.onWatch)
                    }
                }
                MiniControls(
                    state = c.state,
                    title = c.title,
                    sub = c.sub,
                    shownPosition = c.shownPosition,
                    tint = c.tint,
                    onTint = c.onTint,
                    playback = c.playback,
                    onQueue = c.onQueue,
                    onScrub = c.onScrub,
                    onScrubEnd = c.onScrubEnd,
                )
            }
        }
    }
}
