package dev.niccc2007.filet.handlers

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Which of the glimpse's two players plays which clip.
 *
 * ## Why two players
 *
 * One player moving from clip to clip has to seek between them, and a seek is a stall: the
 * decoder is flushed, finds the keyframe, and the picture sits still or goes blank until it has.
 * NxDashVids, whose preview this follows, has no switches at all - its clips are cut into one
 * file ahead of time. Nothing is cut here, so the next clip is made ready instead: while one
 * player plays clip i, the other has already sought to clip i + 1 and waits on its frame, paused.
 * At the boundary it starts and is shown, and the first goes on to get clip i + 2 ready.
 *
 * Two decoders only while a glimpse is up, and both are released by every way it ends - see
 * [GlimpseDecks]. When the second cannot be had - a phone out of decoders, a file one decoder
 * takes and a second will not - there is one player and it seeks between clips, as it always
 * did. Quietly: nothing is said, it is only less smooth.
 */
internal object GlimpseSchedule {

    /** At most this many players at once. */
    const val DECKS = 2

    /** The clip [step] plays, round and round through [clips]. */
    fun clipFor(step: Int, clips: Int): Int = if (clips <= 0) 0 else step % clips

    /** The player that plays [step]: alternating when there are two, so it is never the one still showing. */
    fun deckFor(step: Int, decks: Int): Int = if (decks >= DECKS) step % DECKS else 0

    /** The player to make ready for the step after [step] while [step] plays; null with one. */
    fun cueFor(step: Int, decks: Int): Int? = if (decks >= DECKS) deckFor(step + 1, decks) else null
}

/**
 * The glimpse's players, held so that every way out releases every one of them.
 *
 * A decoder left running behind a glimpse is not a small leak: a phone has only so many, and
 * the next screen to want one - the video viewer - is the one that comes up black. So a player
 * is held here from the moment it is made, not from when it is ready, and:
 *
 * - its surface going (the app to the background) releases that one: [drop];
 * - a second one in the same slot releases the first: [put];
 * - the glimpse going (the peek dismissed, the page swiped away) releases all: [close];
 * - one arriving after that - a surface callback that lands late - is released at once.
 *
 * Generic over what it holds, so the bookkeeping is a test (`GlimpseScheduleTest`).
 */
internal class GlimpseDecks<T : Any>(private val release: (T) -> Unit) {
    private val held = arrayOfNulls<Any?>(GlimpseSchedule.DECKS)

    var closed = false
        private set

    @Suppress("UNCHECKED_CAST")
    operator fun get(i: Int): T? = held[i] as T?

    /** How many are held now. */
    val count: Int get() = held.count { it != null }

    /** Hold [deck] in slot [i]; false, and released, once [close] has run. */
    fun put(i: Int, deck: T): Boolean {
        if (closed) {
            runCatching { release(deck) }
            return false
        }
        drop(i)
        held[i] = deck
        return true
    }

    /** Release slot [i], if it holds one. */
    fun drop(i: Int) {
        @Suppress("UNCHECKED_CAST")
        val d = held[i] as T? ?: return
        held[i] = null
        runCatching { release(d) }
    }

    /** Release every one, and hold none from now on. */
    fun close() {
        closed = true
        for (i in held.indices) drop(i)
    }
}

/** What a host draws around the glimpse: how far through it is, and whether a frame has shown. */
internal class GlimpseProgress {
    var segments by mutableIntStateOf(0)
    var current by mutableIntStateOf(0)
    var within by mutableFloatStateOf(0f)
    var showing by mutableStateOf(false)
}

/** One player and the surface it draws on. */
private class Deck(val mp: MediaPlayer, val surface: Surface) {
    fun release() {
        runCatching { mp.release() }
        runCatching { surface.release() }
    }
}

/**
 * A video's glimpse: [GlimpsePlan]'s clips from [source], muted, round and round, for as long as
 * it is on screen. The held tile's peek and the paused preview both use this one, so they show a
 * video the same way and cannot drift apart again; they were two copies of the same player.
 *
 * Muted, and nothing asks for audio focus - a `MediaPlayer` takes none of its own - so whatever
 * else is playing carries on. Local files only: the hosts pass a source only for one.
 *
 * The first clip starts as soon as the glimpse is up. NxDashVids waits 200 ms after the pointer
 * arrives before it plays, so passing over a tile does not set it off. On a phone the long press
 * is that wait already - it takes about 400 ms to be a long press at all - and a wait on top of it
 * would make the glimpse slower, which is what it was too much of. A deliberate difference.
 *
 * @param onAspect the video's shape, once the first player knows it.
 */
@Composable
internal fun GlimpsePlayer(
    source: Uri,
    progress: GlimpseProgress,
    modifier: Modifier = Modifier,
    onAspect: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val decks = remember(source) { GlimpseDecks<Deck> { it.release() } }
    var ready0 by remember(source) { mutableStateOf(false) }
    var ready1 by remember(source) { mutableStateOf(false) }
    var plan by remember(source) { mutableStateOf<List<GlimpsePlan.Clip>>(emptyList()) }
    var front by remember(source) { mutableIntStateOf(0) }
    DisposableEffect(source) {
        onDispose {
            decks.close()
            progress.showing = false
        }
    }

    /** Player [index] is gone: its surface went, or it failed. The other carries on. */
    fun lose(index: Int) {
        decks.drop(index)
        if (index == 0) {
            ready0 = false
            progress.showing = false
        } else {
            ready1 = false
        }
    }

    /** Make player [index] on [texture]; [onReady] once it is prepared. Held in [decks] from the start. */
    fun open(index: Int, texture: SurfaceTexture, onReady: (MediaPlayer) -> Unit) {
        val mp = MediaPlayer()
        val out = Surface(texture)
        if (!decks.put(index, Deck(mp, out))) return
        runCatching {
            mp.setDataSource(context, source)
            mp.setSurface(out)
            mp.setVolume(0f, 0f)
            if (index == 0) {
                // Before the prepare: the size is reported during it, not after.
                mp.setOnVideoSizeChangedListener { _, vw, vh -> if (vw > 0 && vh > 0) onAspect(vw.toFloat() / vh) }
                mp.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) progress.showing = true
                    false
                }
            }
            // Quiet: a player that fails is dropped, and the glimpse does without it. The first
            // failing leaves the still the host drew under it, as a video that will not play did.
            mp.setOnErrorListener { _, _, _ ->
                lose(index)
                true
            }
            mp.setOnPreparedListener { onReady(it) }
            mp.prepareAsync()
        }.onFailure { lose(index) }
    }

    // Keyed on the source, so another video gets new views with new listeners rather than a
    // texture still calling into the last one's players.
    key(source) {
        Box(modifier) {
            DeckView(visible = progress.showing && front == 0, onGone = { lose(0) }) { texture ->
                open(0, texture) { mp ->
                    val p = GlimpsePlan.of(mp.duration.toLong())
                    mp.isLooping = GlimpsePlan.isWhole(p)
                    plan = p
                    progress.segments = p.size
                    ready0 = true
                }
            }
            // The second player only for clips: a video playing whole loops on one.
            if (plan.size > 1) {
                DeckView(visible = progress.showing && front == 1, onGone = { lose(1) }) { texture ->
                    open(1, texture) { ready1 = true }
                }
            }
        }
    }

    LaunchedEffect(ready0, plan) {
        val first = decks[0]?.takeIf { ready0 } ?: return@LaunchedEffect
        if (plan.isEmpty()) return@LaunchedEffect
        if (GlimpsePlan.isWhole(plan)) {
            front = 0
            progress.current = 0
            runCatching { first.mp.start() }
            val total = plan[0].lengthMs.coerceAtLeast(1L)
            while (isActive) {
                progress.within = runCatching { first.mp.currentPosition.toFloat() / total }.getOrDefault(0f).coerceIn(0f, 1f)
                delay(TICK_MS)
            }
            return@LaunchedEffect
        }
        var step = 0
        // Which player already sits on the next clip, and the job getting it there.
        var cued: Int? = null
        var cue: Job? = null
        while (isActive) {
            val count = if (ready1 && decks[1] != null) GlimpseSchedule.DECKS else 1
            val d = GlimpseSchedule.deckFor(step, count)
            val deck = decks[d] ?: break
            val index = GlimpseSchedule.clipFor(step, plan.size)
            val c = plan[index]
            cue?.join()
            // Not made ready ahead (one player, or the second only just arrived): seek now, and
            // start the clip's clock once the seek has landed, so a slow seek does not eat it.
            if (cued != d) seekLanded(deck.mp, c.startMs)
            cued = null
            runCatching { deck.mp.start() }
            front = d
            progress.current = index
            // The other player stops only once this one is going, so the switch has no gap.
            for (other in 0 until GlimpseSchedule.DECKS) {
                if (other != d) decks[other]?.let { o -> runCatching { o.mp.pause() } }
            }
            GlimpseSchedule.cueFor(step, count)?.let { nd ->
                val next = decks[nd]
                val nc = plan[GlimpseSchedule.clipFor(step + 1, plan.size)]
                cue = if (next == null) null else launch {
                    if (seekLanded(next.mp, nc.startMs)) cued = nd
                }
            }
            val began = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                val elapsed = android.os.SystemClock.uptimeMillis() - began
                progress.within = (elapsed.toFloat() / c.lengthMs).coerceIn(0f, 1f)
                if (elapsed >= c.lengthMs) break
                delay(TICK_MS)
            }
            step++
        }
    }
}

/** One player's texture, shown only while it is the one playing. */
@Composable
private fun DeckView(visible: Boolean, onGone: () -> Unit, onSurface: (SurfaceTexture) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (visible) 1f else 0f },
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) = onSurface(texture)
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit

                    // The app went to the background, or the view went: a new texture comes with
                    // a new player when it is back.
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onGone()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}

/**
 * Seek [mp] to the keyframe nearest [ms] and wait until it is there, up to [SEEK_WAIT_MS].
 *
 * The cheap seek (`SEEK_CLOSEST_SYNC`): a glimpse needs a moment from that part of the video,
 * not that exact frame, and an exact seek decodes from the keyframe up to it. False when the seek
 * failed or did not land in time.
 */
private suspend fun seekLanded(mp: MediaPlayer, ms: Long): Boolean =
    withTimeoutOrNull(SEEK_WAIT_MS) {
        suspendCancellableCoroutine { c ->
            runCatching {
                mp.setOnSeekCompleteListener { if (c.isActive) c.resume(true) }
                mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST_SYNC)
            }.onFailure { if (c.isActive) c.resume(false) }
        }
    } ?: false

/** The glimpse's progress: a segment per clip, those played full, the playing one filling. */
@Composable
internal fun GlimpseBar(progress: GlimpseProgress, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until progress.segments) {
            val fill = when {
                i < progress.current -> 1f
                i == progress.current -> progress.within
                else -> 0f
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.32f)),
            ) {
                Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
            }
        }
    }
}

private const val TICK_MS = 50L

/** The longest a clip waits for its seek before it plays from wherever the player is. */
private const val SEEK_WAIT_MS = 1_500L
