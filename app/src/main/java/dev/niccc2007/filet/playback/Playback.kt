package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The player. One per process, owned by the graph, outliving every screen.
 *
 * ## What this is for
 *
 * Reported: a playlist is built from the folder and then there is no way to put it in the
 * background, and closing the player stops the music. Both are the same fault - the player and
 * all of its state were `remember`ed inside the composable that drew it, so the composition
 * leaving took the `MediaPlayer` with it. A queue you cannot leave the screen with is a
 * preview, not a player.
 *
 * So the player lives here, the screen became a view onto it, and a strip and a notification
 * are two more views onto the same state. Nothing about playback is owned by a composition any
 * more.
 *
 * ## The four things that make it behave like a music player rather than a file preview
 *
 *  1. **It survives navigation**, which is this class existing at all.
 *  2. **It holds audio focus**, so it ducks for a navigation prompt and pauses for a call
 *     rather than playing underneath them. A player that ignores focus is the one everybody
 *     uninstalls.
 *  3. **It is driven from a `MediaSession`**, which is what makes wired and Bluetooth buttons
 *     and the lock screen work. That lives in [PlaybackService]; this class is what the session
 *     calls into, so there is one implementation of "next track" and the session, the strip and
 *     the screen all reach it.
 *  4. **A video keeps its sound when its screen goes away.** [surface] is cleared instead of
 *     the player being released, which is the whole of the "play video in the background" ask -
 *     `MediaPlayer` carries on decoding audio with no display attached.
 *
 * ## Resolving a track
 *
 * [resolve] turns a track id into something `MediaPlayer` can open, and it is injected because
 * doing it properly means staging a file off a network share first - which is the VFS's
 * business and not a player's. It is suspending for the same reason.
 */
class Playback(
    private val app: Context,
    private val scope: CoroutineScope,
    private val resolve: suspend (String) -> Uri?,
    private val readTags: suspend (String, Uri) -> TrackTags,
    /**
     * Asked whether the service should be running, and told when that answer changes.
     *
     * A lambda rather than a direct `startForegroundService` call, so this class is testable
     * and so the Android service lifecycle stays in one file.
     */
    private val onServiceWanted: (Boolean) -> Unit = {},
) {

    /** Tags as the file reports them. A separate type so [Track] stays free of a `Bitmap`. */
    data class TrackTags(
        val title: String,
        val artist: String?,
        val album: String?,
        val art: Bitmap?,
    )

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    /**
     * The current cover art, kept out of [NowPlaying].
     *
     * A `Bitmap` in a data class makes every state comparison an identity check on a few
     * megabytes, and `NowPlaying` is compared on every tick of the position ticker. It is also
     * a different lifetime: the art arrives after the track starts and survives a pause.
     */
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art.asStateFlow()

    /**
     * What the current track actually resolved to, for a second reader of the same file.
     *
     * The player screen's looping video peek opens its own muted `MediaPlayer` on this, and
     * resolving it again there would mean a second copy off a network share - the staging
     * rule, the cache-reuse check and the size cap all live in `TrackMedia` and must have one
     * answer. Null while nothing is loaded or while a load is in flight.
     */
    private val _source = MutableStateFlow<Uri?>(null)
    val source: StateFlow<Uri?> = _source.asStateFlow()

    /**
     * Uninterrupted playback: take no audio focus, and yield to none.
     *
     * Asked for directly, and it is genuinely two switches behind one name. Not requesting
     * focus means starting a track does not pause whatever else is playing; ignoring the focus
     * callbacks means nothing else pausing us. Both are deliberate bad citizenship, which is
     * why it is off by default and why it is one setting rather than something clever - the
     * person who wants it wants both halves.
     *
     * What it cannot do is override the platform: a phone call still takes the speaker, and an
     * OEM battery manager can still stop a background service. The setting's own text says so
     * rather than promising more than Android allows.
     */
    @Volatile
    var uninterrupted: Boolean = false
        set(value) {
            field = value
            // Turning it on while something else holds focus does not need a request; turning
            // it OFF mid-track does, because we may have been playing without ever asking.
            if (!value && _state.value.playing) requestFocus()
        }

    /**
     * The one handle, never in Compose state.
     *
     * A `MediaPlayer` read from a recomposition is a half-released one eventually. Exactly one
     * place creates and destroys it, and that place is this file.
     */
    private var player: MediaPlayer? = null
    private var loadJob: Job? = null
    private var ticker: Job? = null
    private var surface: Surface? = null

    /**
     * Whether playback was paused by us because something else took focus.
     *
     * Kept apart from an ordinary pause, because resuming on focus return is only correct when
     * the pause was not the reader's own. Resuming a track somebody deliberately paused, just
     * because a notification sound finished, is the kind of behaviour that gets a player
     * removed from a phone.
     */
    private var pausedByFocusLoss = false

    private val audio = app.getSystemService(AudioManager::class.java)

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // The second half of uninterrupted playback. Checked here rather than by not
        // registering the listener, because the flag can be turned on while focus is already
        // held - and a listener we registered earlier keeps arriving either way.
        if (uninterrupted) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Gone for good - another player started. Stop asking for it back.
                pausedByFocusLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_state.value.playing) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck rather than pause: this is a navigation prompt or a notification, and
                // stopping the music for a two-second announcement is worse than lowering it.
                runCatching { player?.setVolume(DUCKED, DUCKED) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { player?.setVolume(1f, 1f) }
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val focusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }

    // ── opening ──

    /**
     * Start a queue at [index].
     *
     * Re-opening the same track at the same index is a no-op rather than a restart, because
     * returning to the player screen must not interrupt what is already playing. That is the
     * second half of the report: the screen is now a view, and a view that reloads the player
     * when it opens would have reproduced the bug through a different door.
     */
    fun play(tracks: List<Track>, index: Int, startAtMs: Long = 0L, asAudio: Boolean = false) {
        if (tracks.isEmpty()) return
        _asAudio.value = asAudio
        val safe = index.coerceIn(0, tracks.lastIndex)
        val now = _state.value
        val sameTrack = now.active && now.current?.id == tracks[safe].id
        _state.value = now.copy(queue = tracks, index = safe, active = true, error = null)
        if (sameTrack && player != null && startAtMs <= 0L) {
            onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
            return
        }
        load(restart = true, startAtMs = startAtMs)
    }

    /** Whether [id] is the track the player is on, so a screen can tell. */
    fun isCurrent(id: String): Boolean = _state.value.current?.id == id

    /**
     * True while a VIDEO is being held as sound only, because its screen was left.
     *
     * This is what decides which face a video opens into. Before it, tapping the player strip
     * on a video reopened the full video screen - which is the screen you had just
     * deliberately left, so the gesture that means "show me what is playing" undid the thing
     * you had asked for. With it, a handed-over video opens as the player: the title, the
     * queue, the scrub bar, and its own picture looping in the art square.
     *
     * Reset by [play], so opening a file from the list always means what it says, and by
     * [watchAsVideo] when the picture is asked for back.
     */
    private val _asAudio = MutableStateFlow(false)
    val asAudio: StateFlow<Boolean> = _asAudio.asStateFlow()

    /** Give the pictures back. The next composition of the media host routes to the video. */
    fun watchAsVideo() {
        _asAudio.value = false
    }

    private fun load(restart: Boolean, startAtMs: Long = 0L) {
        loadJob?.cancel()
        releasePlayer()
        val track = _state.value.current ?: run {
            stop()
            return
        }
        _state.value = _state.value.copy(playing = false, positionMs = 0, durationMs = 0, error = null)
        _art.value = null
        _source.value = null
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))

        loadJob = scope.launch {
            val uri = runCatching { resolve(track.id) }.getOrNull()
            if (uri == null) {
                fail("This track has to be copied to local storage before it can play.")
                return@launch
            }
            // Built and prepared off the main thread. `prepare()` reads and decodes a header,
            // and on a file staged from a share that is a network round trip - on the main
            // thread it is a visible stall at best and an ANR at worst.
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(attributes)
                        setDataSource(app, uri)
                        prepare()
                    }
                }.getOrNull()
            }
            if (prepared == null) {
                fail("Android cannot decode ${track.name}.")
                return@launch
            }
            player = prepared
            _source.value = uri
            surface?.let { runCatching { prepared.setSurface(it) } }
            prepared.setOnCompletionListener { onCompletion() }
            // Repeat-one loops INSIDE the decoder rather than by re-opening the file.
            //
            // It used to go round through onCompletion -> load(restart = true), which tears
            // the player down and prepares a new one - a gap of a few hundred milliseconds,
            // and a header re-read each time. On a three-minute song that is a seam you might
            // not notice. On the two-second clip he is looping it is most of the playback.
            applyLooping(prepared)
            _state.value = _state.value.copy(
                durationMs = prepared.duration.toLong().coerceAtLeast(0L),
            )
            // Sought BEFORE starting, so a hand-off from the video screen resumes where that
            // screen was rather than playing a moment from the beginning first.
            if (startAtMs > 0L) seekTo(startAtMs)
            if (restart) resume()

            val tags = runCatching { readTags(track.id, uri) }.getOrNull()
            if (tags != null) {
                _art.value = tags.art
                // The queue entry is updated rather than a parallel tag map kept, so every
                // surface reading the queue gets the real title without asking anything else.
                val q = _state.value.queue.toMutableList()
                val at = _state.value.index
                if (q.getOrNull(at)?.id == track.id) {
                    q[at] = q[at].copy(title = tags.title, artist = tags.artist, album = tags.album)
                    _state.value = _state.value.copy(queue = q)
                }
            }
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(playing = false, error = message)
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
    }

    // ── transport ──

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun resume() {
        val p = player ?: run {
            // Nothing loaded, but a queue is still here - this is the Play button on a
            // notification after the process was trimmed. Reload rather than doing nothing.
            if (_state.value.current != null) load(restart = true)
            return
        }
        if (!requestFocus()) return
        runCatching { p.start() }.onFailure { return }
        pausedByFocusLoss = false
        _state.value = _state.value.copy(playing = true, error = null)
        startTicker()
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
    }

    fun pause() {
        runCatching { player?.pause() }
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false)
        // Focus is NOT dropped on a pause. Releasing it tells the system this app is finished
        // with audio, and some launchers then hand the media buttons to another player - so the
        // Play button on our own notification would start somebody else's music.
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
    }

    fun next() = step(1)

    /**
     * Previous track, or the start of this one.
     *
     * Past a few seconds in, Previous restarts the current track instead of leaving it - which
     * is what every other player does and what a hand reaching for it usually means.
     */
    fun previous() {
        if (_state.value.positionMs > RESTART_WINDOW_MS) {
            seekTo(0)
            return
        }
        step(-1)
    }

    private fun step(delta: Int) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val next = when {
            s.shuffle && s.size > 2 -> {
                // Any track but this one. Not a shuffled permutation: a folder queue has no
                // end to reach, and a Next that can land on the track you are on reads as
                // broken rather than as random.
                var i = Random.nextInt(s.size)
                while (i == s.index) i = Random.nextInt(s.size)
                i
            }
            // Wrapping at both ends. A dead Next on the last track of a folder queue is a
            // common irritation and a folder has no natural end anyway.
            else -> ((s.index + delta) % s.size + s.size) % s.size
        }
        _state.value = s.copy(index = next)
        load(restart = true)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val to = ms.coerceIn(0, _state.value.durationMs.coerceAtLeast(0))
        runCatching { p.seekTo(to.toInt()) }
        _state.value = _state.value.copy(positionMs = to)
    }

    fun setRepeat(repeat: Repeat) {
        _state.value = _state.value.copy(repeat = repeat)
        // Applied to the running player, not only to the next one. Switching to Repeat one
        // while a track is playing has to loop THIS track at its end, which means telling the
        // decoder now - and switching away has to stop it looping, or the queue is stuck.
        player?.let { applyLooping(it) }
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
    }

    /** Cycle off -> all -> one -> off. One implementation, for the screen and the shade. */
    fun cycleRepeat() = setRepeat(
        when (_state.value.repeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        },
    )

    /**
     * Hand repeat-one to the decoder.
     *
     * `isLooping` suppresses the completion callback entirely, which is the point - but it is
     * also why this has to be kept in step with [setRepeat] rather than set once at load.
     */
    private fun applyLooping(p: MediaPlayer) {
        runCatching { p.isLooping = _state.value.repeat == Repeat.ONE }
    }

    fun setShuffle(on: Boolean) {
        _state.value = _state.value.copy(shuffle = on)
    }

    /** Finished with. Clears the queue, so the strip and the notification both go away. */
    fun stop() {
        loadJob?.cancel()
        releasePlayer()
        abandonFocus()
        _art.value = null
        _source.value = null
        _asAudio.value = false
        _state.value = NowPlaying()
        onServiceWanted(false)
    }

    private fun onCompletion() {
        val s = _state.value
        when {
            s.repeat == Repeat.ONE -> load(restart = true)
            // The end of a queue with repeat off stops rather than wrapping. Wrapping is what
            // Next does on a deliberate press; doing it unasked means a folder of music plays
            // for ever, which is a battery complaint rather than a feature.
            s.repeat == Repeat.OFF && !s.shuffle && s.index == s.size - 1 -> {
                _state.value = s.copy(playing = false, positionMs = s.durationMs)
                ticker?.cancel()
                onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
            }
            else -> step(1)
        }
    }

    // ── video ──

    /**
     * Where the pictures go, or null for none.
     *
     * Clearing it is what makes a video carry on playing with its screen closed: `MediaPlayer`
     * keeps decoding audio with no display attached, so the sound continues and the frames are
     * simply not drawn. Releasing the player instead - which is what closing the video screen
     * used to do - is the thing that stopped it.
     */
    fun setSurface(target: Surface?) {
        surface = target
        runCatching { player?.setSurface(target) }
    }

    // ── internals ──

    private fun startTicker() {
        ticker?.cancel()
        // 200 ms: smooth enough for a scrub bar, cheap enough to ignore. Read from the player
        // rather than counted, so a seek or a stall cannot make the bar lie.
        ticker = scope.launch {
            while (_state.value.playing) {
                val at = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L
                _state.value = _state.value.copy(positionMs = at)
                delay(TICK_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        // Never asked for. Requesting AUDIOFOCUS_GAIN is what tells the system to stop
        // everybody else, so the only way not to interrupt other playback is not to ask.
        if (uninterrupted) return true
        val manager = audio ?: return true
        val granted = if (focusRequest != null) {
            manager.requestAudioFocus(focusRequest)
        } else {
            manager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val manager = audio ?: return
        if (focusRequest != null) manager.abandonAudioFocusRequest(focusRequest)
        else manager.abandonAudioFocus(focusListener)
    }

    private fun releasePlayer() {
        ticker?.cancel()
        runCatching { player?.setSurface(null) }
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val TICK_MS = 200L

        /** Lowered to this for a ducking request rather than silenced. */
        const val DUCKED = 0.2f

        /** Past this, Previous restarts the track instead of leaving it. */
        const val RESTART_WINDOW_MS = 4_000L
    }
}
