package dev.niccc2007.filet.index

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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tier 4 of the scheduler: a foreground service, only while the user asked for a full crawl.
 *
 * SEARCH.md §7.2 forbids a *standing* indexing service and this does not weaken that. The
 * distinction the rule draws is whether the user is watching, and here they are: they pressed
 * Index now, the notification says how far it has got, and it carries a Stop.
 *
 * Without it the crawl is just a coroutine in the app's scope, and Android will freeze it the
 * moment the screen goes off — which is precisely the "why did it stop?" this whole gate is
 * about. It goes away the instant the run ends.
 */
class IndexService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground FIRST — before the graph, before the stop branch, before anything
        // that can throw. `startForegroundService` lights a five-second fuse and the platform
        // kills the process if it burns out (this is the C3 crash, and it is not being
        // reintroduced here).
        runCatching { showNotification(null) }

        val graph = runCatching { FiletApp.graphOf(applicationContext) }.getOrNull()
        if (graph == null) { stopSelf(); return START_NOT_STICKY }

        // Two ways to end, and they are not the same thing. ACTION_STOP is the user pressing
        // Stop in the shade and must cancel the crawl; ACTION_DONE is the crawl telling the
        // service it is over and must NOT, or it would stamp "stopped by you" on a run that
        // finished by itself.
        if (intent?.action == ACTION_STOP) {
            graph.indexCoordinator.stopCrawl()
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DONE) {
            stopEverything()
            return START_NOT_STICKY
        }

        if (!graph.indexCoordinator.isCrawling()) {
            // The run beat us here, or never started. Do not sit in the shade claiming to be
            // indexing when nothing is.
            stopEverything()
            return START_NOT_STICKY
        }

        watch?.cancel()
        watch = scope.launch {
            graph.indexCoordinator.run.collectLatest { run ->
                if (!run.active) { stopEverything(); return@collectLatest }
                runCatching { showNotification(run) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun stopEverything() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    /** @param run null while the fuse is being put out and nothing is known yet. */
    private fun showNotification(run: IndexRun?) {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IndexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Indexing storage")
            .setContentText(
                when {
                    run == null || run.seen == 0L -> "Walking the tree…"
                    else -> "${run.seen} files so far"
                }
            )
            // Indeterminate on purpose. The total is not known until the walk is over, and a
            // percentage that is really a guess is worse than an honest spinner.
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.niccc2007.filet.index.STOP"
        const val ACTION_DONE = "dev.niccc2007.filet.index.DONE"
        private const val CHANNEL = "filet.index"
        private const val ID = 4301

        fun start(context: Context) {
            val intent = Intent(context, IndexService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        /** The run ended on its own. Tears the notification down without cancelling anything. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, IndexService::class.java).setAction(ACTION_DONE))
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Indexing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown only while a full index you started is running."
                    setShowBadge(false)
                }
            )
        }
    }
}
