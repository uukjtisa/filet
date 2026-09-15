package dev.niccc2007.filet.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps the share alive.
 *
 * This is the case SEARCH.md §7.2 rules **in**, not out: the user asked for it, it is visible,
 * it has a Stop action in the notification, and it stops itself when idle. The rule that
 * forbids a persistent indexing service is the same rule that permits this one - the
 * distinction is whether the user is watching.
 *
 * `dataSync` is the honest type. Android 15 caps `dataSync` at six hours in twenty-four,
 * which is far beyond a share session and is not a constraint worth designing around.
 */
class NearbyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground FIRST, before anything that can fail or take time - including the
        // stop path.
        //
        // `startForegroundService` starts a five-second fuse, and if `startForeground` has not
        // been called when it burns out the platform kills the process with
        // ForegroundServiceDidNotStartInTimeException. The stop branch used to return without
        // ever calling it, and so did any path where building the notification threw. Both
        // presented to the user as "the app crashed when I turned sharing on".
        runCatching { startForegroundCompat(null) }

        val graph = runCatching { FiletApp.graphOf(applicationContext) }.getOrNull()
        if (graph == null) { stopSelf(); return START_NOT_STICKY }

        when (intent?.action) {
            ACTION_STOP -> {
                graph.nearby.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!graph.nearby.isRunning()) {
            // Nothing to keep alive. Do not sit in the notification shade claiming otherwise.
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Guarded like the one at the top, and for the reason the one at the top is guarded.
        // This call was bare, so a notification that failed to build took the service down -
        // and with START_STICKY the platform brought it straight back into the same branch,
        // with the server still running in the app process, to fail again. That is the crash
        // loop Nic could only escape by force-stopping Filet.
        if (runCatching { startForegroundCompat(graph.nearby.state.value) }.isFailure) {
            graph.nearby.stop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        idleWatch?.cancel()
        idleWatch = scope.launch {
            while (true) {
                delay(30_000)
                if (!graph.nearby.isRunning()) { stopSelf(); break }
                graph.nearby.grants.sweep()
                if (graph.nearby.idleExpired()) {
                    // Stopping itself is the default, not something to remember. An open
                    // server you forgot about is the actual risk here.
                    graph.nearby.stop()
                    stopSelf()
                    break
                }
                startForegroundCompat(graph.nearby.state.value)
            }
        }
        // NOT sticky.
        //
        // START_STICKY asks the platform to restart this service after it dies, which is the
        // right answer for something that should survive being killed for memory - and the
        // wrong one here, because the most likely reason it died is that starting it failed.
        // Sharing is something he switched on deliberately and can switch on again; silently
        // resurrecting it is how a single failure became an unbreakable cycle.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        idleWatch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    /** @param state null while the fuse is being put out and nothing is known yet. */
    private fun startForegroundCompat(state: ServerState?) {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NearbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sharing over this network")
            .setContentText(
                when {
                    state == null || state.url.isEmpty() -> "Starting…"
                    else -> "${state.url}  ·  ${state.clients.size} connected"
                }
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.niccc2007.filet.nearby.STOP"
        private const val CHANNEL = "filet.nearby"
        // Declared in dev.niccc2007.filet.Notifications: an id is a key across the whole
        // app, and declaring one locally is what let the updater collide with this.
        private val ID = dev.niccc2007.filet.Notifications.NEARBY

        fun start(context: Context) {
            val intent = Intent(context, NearbyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NearbyService::class.java).setAction(ACTION_STOP))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nearby sharing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Filet is serving files on your network."
                    setShowBadge(false)
                }
            )
        }
    }
}

private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
