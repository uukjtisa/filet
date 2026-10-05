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
import kotlinx.coroutines.flow.collectLatest
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
    private var session: MediaSession? = null

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
        watch = scope.launch {
            playback.state.collectLatest { state ->
                if (!PlaybackNotice.shouldKeepService(state)) {
                    leave()
                    return@collectLatest
                }
                runCatching { post(state) }
                runCatching { publish(state) }
            }
        }

        // NOT sticky. A restart with a null intent would re-post a notification for a player
        // that no longer holds anything, and the queue does not survive the process anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
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
    private fun publish(state: NowPlaying) {
        val s = session ?: return
        val track = state.current ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
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

    private fun post(state: NowPlaying?) {
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
        val notification = build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.PLAYBACK, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.PLAYBACK, notification)
        }
    }

    private fun build(state: NowPlaying?): Notification {
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state?.let { PlaybackNotice.title(it) } ?: "Playing")
            .setContentText(state?.let { PlaybackNotice.subtitle(it) }.orEmpty())
            .setContentIntent(open)
            .setOngoing(state?.playing == true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        val stepping = state?.canStep == true
        if (stepping) builder.addAction(action(FiletIconRes.PREVIOUS, "Previous", ACTION_PREVIOUS))
        builder.addAction(
            action(
                if (state?.playing == true) FiletIconRes.PAUSE else FiletIconRes.PLAY,
                state?.let { PlaybackNotice.toggleLabel(it) } ?: "Play",
                ACTION_TOGGLE,
            ),
        )
        if (stepping) builder.addAction(action(FiletIconRes.NEXT, "Next", ACTION_NEXT))
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

        const val ACTION_TOGGLE = "dev.niccc2007.filet.playback.TOGGLE"
        const val ACTION_NEXT = "dev.niccc2007.filet.playback.NEXT"
        const val ACTION_PREVIOUS = "dev.niccc2007.filet.playback.PREVIOUS"
        const val ACTION_STOP = "dev.niccc2007.filet.playback.STOP"

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
    val PLAY = R.drawable.ic_media_play
    val PAUSE = R.drawable.ic_media_pause
    val NEXT = R.drawable.ic_media_next
    val PREVIOUS = R.drawable.ic_media_previous
    val CLOSE = R.drawable.ic_media_stop
}
