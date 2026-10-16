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
    fun play(
        tracks: List<Track>,
        index: Int,
        startAtMs: Long = 0L,
        asAudio: Boolean = false,
        /**
         * Where this queue came from, or null to keep what is already recorded.
         *
         * Null rather than an empty default, because the commonest caller is the queue sheet
         * jumping to a row in the queue it is already playing - and an empty default there
         * would wipe the source every time somebody picked a track out of the list.
         */
        from: String? = null,
    ) {
        if (tracks.isEmpty()) return
        _asAudio.value = asAudio
        val safe = index.coerceIn(0, tracks.lastIndex)
        val now = _state.value
        val sameTrack = now.active && now.current?.id == tracks[safe].id
        _state.value = now.copy(
            queue = tracks, index = safe, active = true, error = null,
            from = from ?: now.from,
        )
        if (sameTrack && player != null && startAtMs <= 0L) {
            onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
            return
        }
        load(restart = true, startAtMs = startAtMs)
    }

    /**
     * Take a video's sound over WITHOUT a gap, from the screen that is still playing it.
     *
     * The ordinary [play] path seeks to a position captured when the button was pressed, and
     * by the time `prepare()` has read the header that position is a few hundred milliseconds
     * stale - so the music started by replaying the bit you had just heard. Reported as the
     * switch "cutting".
     *
     * [positionNow] is read AFTER the decoder is prepared and immediately before it starts,
     * so it is asked of the still-running video at the last possible moment. The two players
     * therefore meet at the same instant of the file instead of at two instants a prepare
     * apart.
     *
     * `asAudio` is also deferred: it is what routes the media host from the video screen to
     * the music face, so flipping it at the START of the hand-off would unmount the video -
     * and the video is the thing [positionNow] is reading. It is set once the sound is
     * actually running, and [whenPlaying] is the screen's cue to let go.
     */
    fun handOffFromVideo(
        track: Track,
        positionNow: () -> Long,
        whenPlaying: () -> Unit = {},
        whenFailed: () -> Unit = {},
    ) {
        _state.value = _state.value.copy(
            queue = listOf(track), index = 0, active = true, error = null,
        )
        load(
            restart = true,
            startAt = positionNow,
            then = {
                _asAudio.value = true
                whenPlaying()
            },
            // Reported as "sometimes the video viewer doesn't disappear". `load` gives up
            // through `fail` - a file the shared decoder cannot open as audio, a URI that has
            // gone, a device that will not hand the same file to a second MediaPlayer while
            // the VideoView still holds it - and when it did, `then` never ran, so `asAudio`
            // never flipped and the screen it was supposed to leave simply stayed. Silent,
            // intermittent, and indistinguishable from the app ignoring the button.
            orFailed = whenFailed,
        )
    }

    /**
     * Put tracks at the end of what is playing, or immediately after it.
     *
     * The queue was always the folder and nothing else, which made a queue of tracks from two
     * places impossible - the one thing a file manager's player is otherwise well placed to
     * do, since it is already looking at both folders.
     *
     * With nothing playing this is just [play]: "add to queue" on a silent player meaning
     * nothing would be a dead control, and starting is the only reading of it that is not.
     *
     * @param next true to insert after the current track rather than at the end.
     */
    fun enqueue(tracks: List<Track>, next: Boolean = false) {
        if (tracks.isEmpty()) return
        val now = _state.value
        if (!now.active || now.current == null) {
            play(tracks, 0)
            return
        }
        // A track already in the queue is not added twice. Pressing Add on a folder you
        // already queued should not give you every song two deep, and the copy that is
        // already there keeps its position rather than moving under you.
        val have = now.queue.map { it.id }.toSet()
        val fresh = tracks.filterNot { it.id in have }
        if (fresh.isEmpty()) return
        val at = if (next) (now.index + 1).coerceAtMost(now.queue.size) else now.queue.size
        val merged = now.queue.toMutableList().apply { addAll(at, fresh) }
        _state.value = now.copy(queue = merged)
        onServiceWanted(PlaybackNotice.shouldKeepService(_state.value))
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

    private fun load(
        restart: Boolean,
        startAtMs: Long = 0L,
        /** Read just before the decoder starts, for a gapless hand-off. See [handOffFromVideo]. */
        startAt: (() -> Long)? = null,
        /** Run once the track is actually playing, on the main scope. */
        then: (() -> Unit)? = null,
        /** Run instead of [then] when this never got as far as playing. */
        orFailed: (() -> Unit)? = null,
    ) {
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
                orFailed?.invoke()
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
                orFailed?.invoke()
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
            val at = startAt?.invoke() ?: startAtMs
            if (at > 0L) seekTo(at)
            applySpeed(prepared)
            if (restart) resume()
            then?.invoke()

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

    /**
     * Playback speed, 0.5x to 2x.
     *
     * On the player rather than on the screen, for the same reason repeat is: the chip in the
     * music face, the one in the video chrome and anything the session grows later have to
     * agree, and a speed held in a composition is lost the moment the screen is left.
     *
     * `playbackParams` restarts a paused `MediaPlayer` on some devices - a documented quirk,
     * not a rumour - so it is applied and then the play state is put back the way it was.
     */
    fun setSpeed(rate: Float) {
        val clamped = rate.coerceIn(MIN_SPEED, maxSpeed)
        _speed.value = clamped
        player?.let { applySpeed(it) }
    }

    /**
     * The fastest this DEVICE will actually play, discovered rather than assumed.
     *
     * Asked for a slider that goes to 100x. It cannot, and the reason is not a policy: at
     * 100x a 48 kHz track is 4.8 MHz of samples a second going into a sink that runs at 48,
     * so `AudioTrack` refuses the rate and `setPlaybackParams` throws
     * `IllegalArgumentException`. Android's own resampler tops out far below that.
     *
     * Rather than pick a number and hope, the player starts optimistic at [MAX_SPEED] and
     * walks DOWN the first time a device says no, remembering what it accepted. So a phone
     * that manages 4x gets 4x and a phone that manages 2x gets 2x, and neither gets a slider
     * position that silently does nothing.
     */
    private val _maxSpeed = MutableStateFlow(MAX_SPEED)
    val maxSpeedFlow: StateFlow<Float> = _maxSpeed.asStateFlow()
    private val maxSpeed: Float get() = _maxSpeed.value

    private fun applySpeed(p: MediaPlayer) {
        val rate = _speed.value
        val pitch = _pitch.value.pitchFor(rate)
        // 1.0 at the default pitch is what a fresh player already does; touching
        // `playbackParams` costs a reconfigure, so a player nobody has slowed is left alone.
        if (rate == 1f && pitch == 1f && !speedTouched) return
        speedTouched = true
        val wasPlaying = _state.value.playing
        val ok = runCatching {
            p.playbackParams = p.playbackParams
                .setSpeed(rate)
                // The whole of the smoothness question, and it is one number.
                //
                // At pitch 1.0 the framework runs a TIME-STRETCHER: it keeps the pitch by
                // cutting the audio into grains and overlapping them, and at 0.75x and below
                // that is what warbles - a metallic flutter on sustained notes and a stutter
                // on speech. It is not Filet dropping frames, which is what it sounds like.
                //
                // Pitch tied to speed is plain resampling - the tape effect - and resampling
                // has no artefacts at all, because there is nothing to invent. It is the
                // smooth option, and the cost is that a slowed voice goes deep.
                //
                // Which of the two it gets is [PitchMode]'s decision, and the default
                // holds the pitch only while the stretcher is good at it. See PitchMode.
                .setPitch(pitch)
        }.isSuccess
        if (!ok) {
            // The device refused this rate. Remember the fastest it DID take and fall back
            // to it, rather than leaving the slider somewhere that plays nothing.
            if (rate > 1f) {
                _maxSpeed.value = maxOf(1f, (rate - SPEED_BACKOFF))
                _speed.value = _maxSpeed.value
                runCatching { p.playbackParams = p.playbackParams.setSpeed(_speed.value).setPitch(1f) }
            }
            return
        }
        // Documented quirk: on some devices writing `playbackParams` STARTS a paused player.
        if (!wasPlaying) runCatching { p.pause() }
    }

    /**
     * What happens to the pitch when the speed changes.
     *
     * Held here and not only in Prefs because the decoder has to be told, and because the
     * sheet, Settings and the video player read one value. Set from Prefs at startup, the
     * same way [uninterrupted] is.
     */
    private val _pitch = MutableStateFlow(PitchMode.DEFAULT)
    val pitch: StateFlow<PitchMode> = _pitch.asStateFlow()

    fun setPitchMode(mode: PitchMode) {
        if (_pitch.value == mode) return
        _pitch.value = mode
        player?.let { applySpeed(it) }
    }

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private var speedTouched = false

    /**
     * The sleep timer: stop playing at a wall-clock moment, or never.
     *
     * The deadline is published rather than the remaining minutes, so every surface counts
     * down off one number instead of each keeping its own ticker - and a surface that was not
     * on screen while it ran shows the right thing the moment it appears.
     *
     * It PAUSES rather than stopping. Falling asleep to a folder of music and finding the
     * queue gone in the morning is a worse outcome than finding it paused, and pause is the
     * state the notification can undo with one press.
     */
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt.asStateFlow()
    private var sleepJob: Job? = null

    /** @param minutes 0 to cancel. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepAt.value = 0L
            return
        }
        val ms = minutes * 60_000L
        _sleepAt.value = android.os.SystemClock.elapsedRealtime() + ms
        sleepJob = scope.launch {
            delay(ms)
            _sleepAt.value = 0L
            if (_state.value.playing) pause()
        }
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
        sleepJob?.cancel()
        _sleepAt.value = 0L
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
    /**
     * Where the player is this instant, not where the ticker last wrote it down.
     *
     * The ticker runs five times a second, which is right for a scrub bar and wrong for
     * anything that has to land ON something: reading the state flow and extrapolating adds
     * the age of the last tick to every answer. Asking the player costs one JNI call.
     *
     * Returns null when there is no player, rather than 0 - a missing answer and the start
     * of the track are different things, and conflating them is a light that fires at zero.
     */
    fun positionNow(): Long? = runCatching { player?.currentPosition?.toLong() }.getOrNull()

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
