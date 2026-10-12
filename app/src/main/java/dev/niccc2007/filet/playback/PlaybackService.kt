package dev.niccc2007.filet.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.Notifications
import dev.niccc2007.filet.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps playback alive with the app in the background, and puts it on the lock screen.
 *
 * ## Why a service at all
 *
 * Android does not let an app in the background hold a `MediaPlayer` for long. Without a
 * foreground service the process is trimmed and the music stops - which is the reported fault
 * in its second form: the screen was not the only thing killing playback, backgrounding the
 * app would have done it too.
 *
 * SEARCH.md §7.2's rule rules this **in** on the same grounds as the share service: it is
 * asked for by an action, it is visible, its notification carries the controls, and it stops
 * itself the moment there is nothing playing. The rule that forbids a permanent indexing
 * service is the one that permits this.
 *
 * `mediaPlayback` is the honest foreground type and it is the one with no time cap, because
 * playing a long album is exactly what it is for.
 *
 * ## The session is what makes the hardware work
 *
 * A notification with buttons on it only serves a phone being looked at. Earphone buttons, a
 * car stereo, a smartwatch and the lock screen all go through `MediaSession`, and all of them
 * arrive here. Every one of them calls into [Playback], so there is one implementation of
 * "next track" rather than one per surface.
 *
 * The notification id comes from [Notifications] and never from a literal - see that file for
 * the foreground-service notification that was cancelled out from under a running service.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var drift: Job? = null
    private var session: MediaSession? = null

    /**
     * The last card's contents, so the drift can re-post it without the flows firing.
     *
     * The shade draws a flat card from one colour and cannot animate it, so the only way for
     * the notification to take part in the living glow is for its colour to be different the
     * next time it is posted. That needs the state and the artwork at a moment when nothing
     * about the track has changed, which is exactly what a flow does not give you.
     */
    private var lastState: NowPlaying? = null
    private var lastArt: android.graphics.Bitmap? = null

    /** Tap counting for a one-button wired remote. See [MediaButtons] for why it is needed. */
    private val tapHandler = Handler(Looper.getMainLooper())
    private var taps = 0
    private var pendingTap: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground FIRST, before anything that can fail or take time, including the stop
        // path. `startForegroundService` lights a five-second fuse and the platform kills the
        // process if `startForeground` has not been called when it burns out - and a path that
        // returns early without calling it reads to somebody holding the phone as "the app
        // crashed when I pressed play".
        runCatching { post(null) }

        val graph = runCatching { FiletApp.graphOf(applicationContext) }.getOrNull()
        if (graph == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val playback = graph.playback

        when (intent?.action) {
            ACTION_TOGGLE -> playback.toggle()
            ACTION_NEXT -> playback.next()
            ACTION_PREVIOUS -> playback.previous()
            ACTION_REPEAT -> playback.cycleRepeat()
            ACTION_STOP -> {
                playback.stop()
                leave()
                return START_NOT_STICKY
            }
        }

        if (!PlaybackNotice.shouldKeepService(playback.state.value)) {
            // Nothing playing. Do not sit in the notification shade claiming otherwise.
            leave()
            return START_NOT_STICKY
        }

        if (session == null) session = openSession(playback)

        // One collector, replacing itself rather than stacking. A second delivery of the same
        // intent must not leave two collectors posting the same notification twice.
        watch?.cancel()
        // The ART is watched too, not only the state.
        //
        // It arrives AFTER the track starts - tags are read off the file once it is open, and
        // for a video the picture is a decoded frame - so a notification posted from the state
        // alone is posted before there is a picture to put on it, and nothing ever posts it
        // again. The result was a shade entry that stayed blank for the whole track while the
        // player screen showed the artwork perfectly well.
        watch = scope.launch {
            combine(playback.state, playback.art) { state, art -> state to art }
                .collectLatest { (state, art) ->
                    if (!PlaybackNotice.shouldKeepService(state)) {
                        leave()
                        return@collectLatest
                    }
                    lastState = state
                    lastArt = art
                    runCatching { post(state, art) }
                    runCatching { publish(state, art) }
                }
        }

        drift?.cancel()
        drift = scope.launch { driftColour() }

        // NOT sticky. A restart with a null intent would re-post a notification for a player
        // that no longer holds anything, and the queue does not survive the process anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        drift?.cancel()
        scope.coroutineContext[Job]?.cancel()
        pendingTap?.let { tapHandler.removeCallbacks(it) }
        runCatching { session?.release() }
        session = null
        super.onDestroy()
    }

    /**
     * Playback stopped by the system taking the task away - a swipe off the recents list.
     *
     * Stopped rather than left running. A player with no app behind it is a notification
     * somebody has to go and find the Stop button for, and swiping the app away is a clear
     * enough statement of intent.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { FiletApp.graphOf(applicationContext).playback.stop() }
        leave()
        super.onTaskRemoved(rootIntent)
    }

    private fun leave() {
        watch?.cancel()
        stopForegroundCompat()
        stopSelf()
    }

    // ── the session ──

    private fun openSession(playback: Playback): MediaSession =
        MediaSession(this, "Filet").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playback.resume()
                override fun onPause() = playback.pause()
                override fun onSkipToNext() = playback.next()
                override fun onSkipToPrevious() = playback.previous()
                override fun onSeekTo(pos: Long) = playback.seekTo(pos)
                override fun onStop() {
                    playback.stop()
                    leave()
                }

                /**
                 * Raw key events, intercepted only for the hook.
                 *
                 * Everything else is handed to `super`, which fans it out to the methods above
                 * - so the mapping lives in one place and this override is the exception rather
                 * than a second implementation of it.
                 */
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent
                        .getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event == null || event.action != KeyEvent.ACTION_DOWN) {
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                    if (event.keyCode != MediaButtons.KEYCODE_HEADSETHOOK) {
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                    countTap(playback)
                    return true
                }
            })
            isActive = true
        }

    /**
     * Fold one hook press into a tap count, and act once the window closes.
     *
     * The wait is what makes a double tap distinguishable from two single taps. Acting
     * immediately and correcting later is not an option here: the first action would already
     * have paused the music.
     */
    private fun countTap(playback: Playback) {
        taps++
        pendingTap?.let { tapHandler.removeCallbacks(it) }
        val run = Runnable {
            val action = MediaButtons.actionOf(MediaButtons.KEYCODE_HEADSETHOOK, taps)
            taps = 0
            when (action) {
                MediaButtons.Action.TOGGLE -> playback.toggle()
                MediaButtons.Action.NEXT -> playback.next()
                MediaButtons.Action.PREVIOUS -> playback.previous()
                else -> Unit
            }
        }
        pendingTap = run
        tapHandler.postDelayed(run, MediaButtons.TAP_WINDOW_MS)
    }

    /** Tell the system what is playing, which is what draws the lock screen and a watch face. */
    private fun publish(state: NowPlaying, art: android.graphics.Bitmap?) {
        val s = session ?: return
        val track = state.current ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                // Both keys, deliberately. ALBUM_ART is what a lock screen and a car head unit
                // read; ART is what some watch faces and older launchers read. They are
                // different keys for the same bitmap and handing over only one leaves a
                // surface blank for no reason.
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .build(),
        )
        s.setPlaybackState(
            SessionState.Builder()
                .setActions(
                    SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or
                        SessionState.ACTION_PLAY_PAUSE or SessionState.ACTION_SEEK_TO or
                        SessionState.ACTION_STOP or
                        SessionState.ACTION_SKIP_TO_NEXT or SessionState.ACTION_SKIP_TO_PREVIOUS,
                )
                // The elapsed-realtime stamp is what lets the lock screen run its own clock
                // between updates instead of ticking only when we post. Without it the position
                // jumps forward 200 ms at a time on the lock screen and looks broken.
                .setState(
                    if (state.playing) SessionState.STATE_PLAYING else SessionState.STATE_PAUSED,
                    state.positionMs,
                    if (state.playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    // ── the notification ──

    /**
     * The card's colour, moving.
     *
     * One re-post every [GLOW_TICK_MS] while something is playing and the switch is on. The
     * notification is already built from scratch on every track change and already carries
     * `setOnlyAlertOnce`, so a re-post is silent and costs one builder - but it is a wakeup
     * every few seconds for a visual flourish, which is why the cadence is slow and why it
     * stops dead the moment playback pauses.
     *
     * It also posts once more when the switch is turned OFF, so the card settles back onto
     * the artwork's own colour instead of staying wherever the drift had left it.
     */
    private suspend fun driftColour() {
        var wasOn = false
        while (true) {
            val prefs = runCatching { FiletApp.graphOf(applicationContext).prefs }.getOrNull()
            val on = prefs?.livingGlow?.value == true
            val playing = lastState?.playing == true
            if ((on && playing) || (wasOn && !on)) {
                runCatching { post(lastState, lastArt) }
            }
            wasOn = on && playing
            delay(GLOW_TICK_MS)
        }
    }

    private fun post(state: NowPlaying?, art: android.graphics.Bitmap? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "The track Filet is playing, with its controls."
                    setShowBadge(false)
                    // No sound and no vibration for a notification that updates every time a
                    // track changes. IMPORTANCE_LOW already means silent; this makes it
                    // explicit so a channel edit cannot reintroduce a chirp per track.
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val notification = build(state, art)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.PLAYBACK, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.PLAYBACK, notification)
        }
    }

    /**
     * The shade card.
     *
     * ## What the overhaul changed, and why each piece
     *
     * It was correct and anonymous: the launcher icon, a filename, a bare second line, and
     * five grey buttons on whatever grey the system picked. Asked for directly - colour it
     * from the artwork, make the title better, and sort the buttons out.
     *
     *  - **It is coloured by the track.** [Notification.Builder.setColorized] paints the whole
     *    card, and it is honoured only for a FOREGROUND-SERVICE notification, which this is -
     *    that is the one reason this can look like a music app's card rather than a grey row.
     *    The colour comes from the same [ArtPalette] the player screen uses, so the shade and
     *    the screen are the same hue on the same track rather than two guesses.
     *  - **The title is the title, not the filename.** `PlaybackNotice.title` already reads
     *    the tags; what was missing is the THIRD line. A card has room for a sub-text and the
     *    useful thing to put in it is where this is playing from and where you are in it -
     *    which is what a lock screen is being looked at for.
     *  - **The buttons mean something.** Repeat shows which of its three states it is in with
     *    its own glyph and says so in words, and Stop is last and separate because it is the
     *    one press here that cannot be undone by pressing it again.
     *
     * @param art the embedded cover, or a frame of the video being played as sound - the same
     *   moment the player screen loops, so the two show the same file the same way.
     */
    private fun build(state: NowPlaying?, art: android.graphics.Bitmap?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder
            .setSmallIcon(FiletIconRes.NOTE)
            .setContentTitle(state?.let { PlaybackNotice.title(it) } ?: "Playing")
            .setContentText(state?.let { PlaybackNotice.line2(it) }.orEmpty())
            .setContentIntent(open)
            .setOngoing(state?.playing == true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        state?.let { s ->
            val sub = PlaybackNotice.line3(s)
            if (sub.isNotEmpty()) builder.setSubText(sub)
        }
        art?.let { builder.setLargeIcon(it) }

        // The colour, sampled off the artwork on this thread.
        //
        // It is a 24x24 downscale and a pass over 576 pixels, and `post` is already called
        // from a flow collector rather than from a hot path - so this is cheaper than the
        // bitmap scale the shade is about to do with the large icon anyway.
        val tint = art?.let { ArtPalette.ofBitmap(it) }?.takeIf { it.usable }
        if (tint != null) {
            // The GROUND, not the control colour. `ArtPalette.of` lightens until it clears
            // 4.5:1 against a dark panel, which is right for a 68dp play button and is a slab
            // of neon across a whole card - reported as "too bright". See ArtPalette.ground.
            val ground = ArtPalette.ground(tint)
            // And the living glow's share of it: the hue a little further round the wheel on
            // each re-post, from the clock rather than from a counter, so the phase is right
            // whichever post this is. See ArtPalette.drift and driftColour.
            val glowing = runCatching {
                FiletApp.graphOf(applicationContext).prefs.livingGlow.value
            }.getOrDefault(true) && state?.playing == true
            val shade = if (glowing) ArtPalette.drift(ground, glowPhase()) else ground
            builder.setColor(0xFF000000.toInt() or shade)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Only honoured on a foreground-service notification, and silently ignored
                // otherwise - so this is not a promise, it is an ask the system may refuse.
                builder.setColorized(true)
            }
        }

        val stepping = state?.canStep == true
        // Order is the compact row's order, and the compact row is three. Previous, play/pause
        // and next are the three somebody reaches for without looking, so they come first and
        // repeat and stop live in the expanded view - which is where a decision rather than a
        // reflex belongs anyway.
        if (stepping) builder.addAction(action(FiletIconRes.PREVIOUS, "Previous", ACTION_PREVIOUS))
        builder.addAction(
            action(
                if (state?.playing == true) FiletIconRes.PAUSE else FiletIconRes.PLAY,
                state?.let { PlaybackNotice.toggleLabel(it) } ?: "Play",
                ACTION_TOGGLE,
            ),
        )
        if (stepping) builder.addAction(action(FiletIconRes.NEXT, "Next", ACTION_NEXT))
        val repeat = state?.repeat ?: Repeat.OFF
        builder.addAction(action(FiletIconRes.repeatOf(repeat), repeatLabel(repeat), ACTION_REPEAT))
        builder.addAction(action(FiletIconRes.CLOSE, "Stop", ACTION_STOP))

        val style = Notification.MediaStyle()
        session?.sessionToken?.let { style.setMediaSession(it) }
        // Which actions survive the collapsed row. Play/pause is always one of them; the
        // indices shift when there is no queue to step through, so they are computed rather
        // than written down - a fixed `0, 1, 2` would show Previous on a single-track queue
        // where Previous is not an action at all.
        style.setShowActionsInCompactView(*if (stepping) intArrayOf(0, 1, 2) else intArrayOf(0))
        builder.style = style

        return builder.build()
    }

    /**
     * What pressing repeat would be leaving, said as where it IS.
     *
     * A notification action's label is read as the state on Android's own media notifications,
     * not as the verb - so "Repeat off" has to mean repeat is currently off, the opposite
     * convention to the rest of the buttons here, which say what they will do.
     */
    /** Where in the drift the card is, from the clock. One full out-and-back per cycle. */
    private fun glowPhase(): Double =
        (SystemClock.elapsedRealtime() % GLOW_CYCLE_MS).toDouble() / GLOW_CYCLE_MS

    private fun repeatLabel(repeat: Repeat): String = when (repeat) {
        Repeat.OFF -> "Repeat off"
        Repeat.ALL -> "Repeating the queue"
        Repeat.ONE -> "Repeating this track"
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val intent = Intent(this, PlaybackService::class.java).setAction(act)
        val pending = PendingIntent.getService(
            this, act.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), label, pending,
        ).build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL = "filet.playback"

        /**
         * How often the card is re-posted for the glow.
         *
         * Five seconds. A notification update is a wakeup, and the shade is not something
         * anybody watches for seconds at a time - the point is that a glance five minutes
         * later is a different colour, not that the change can be seen happening.
         */
        private const val GLOW_TICK_MS = 5_000L

        /** One full out-and-back of the hue. Long, for the same reason. */
        private const val GLOW_CYCLE_MS = 240_000L

        const val ACTION_TOGGLE = "dev.niccc2007.filet.playback.TOGGLE"
        const val ACTION_NEXT = "dev.niccc2007.filet.playback.NEXT"
        const val ACTION_PREVIOUS = "dev.niccc2007.filet.playback.PREVIOUS"
        const val ACTION_STOP = "dev.niccc2007.filet.playback.STOP"
        const val ACTION_REPEAT = "dev.niccc2007.filet.playback.REPEAT"

        /**
         * Start or stop the service to match what the player is doing.
         *
         * Called from [Playback] through a lambda, so the player holds no Android service
         * knowledge and this decision lives in one place.
         */
        fun reconcile(context: Context, wanted: Boolean) {
            val intent = Intent(context, PlaybackService::class.java)
            if (wanted) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}

/**
 * The drawables the notification actions use.
 *
 * Android notification actions take a resource id, not a Compose `ImageVector`, so the app's
 * own `FiletIcons` cannot be used here. Named rather than written inline so a missing drawable
 * is one compile error in one place.
 */
private object FiletIconRes {
    /**
     * The status bar's icon, and it may only be a silhouette.
     *
     * Android renders a small icon from its ALPHA channel, so the launcher mipmap this used
     * to be arrived as a white square. A quaver is both correct and legible at 18dp.
     */
    val NOTE = R.drawable.ic_media_note

    val PLAY = R.drawable.ic_media_play
    val PAUSE = R.drawable.ic_media_pause
    val NEXT = R.drawable.ic_media_next
    val PREVIOUS = R.drawable.ic_media_previous
    val CLOSE = R.drawable.ic_media_stop

    fun repeatOf(repeat: Repeat): Int = when (repeat) {
        Repeat.OFF -> R.drawable.ic_media_repeat_off
        Repeat.ALL -> R.drawable.ic_media_repeat
        Repeat.ONE -> R.drawable.ic_media_repeat_one
    }
}
