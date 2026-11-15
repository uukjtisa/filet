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

/**
 * Keeps the inbox open with Filet in the background, so a send from another Filet is heard and
 * asked about over whatever app is open: Share › Incoming files › Ready while Filet is closed.
 *
 * What it costs is a listening socket and the network announcement the system keeps for it -
 * one thread parked in `accept`, no polling, nothing waking the phone. That is the whole of the
 * work; everything a send then does runs only when one arrives.
 *
 * Android allows no listener in the background without a notification, so it shows the quietest
 * one there is: the minimum importance, which puts it folded at the bottom of the shade with no
 * status-bar icon and no sound. On Android 14 and later it can be swiped away and the service
 * carries on.
 *
 * `connectedDevice` is the honest type: it waits for other phones on the same network, which is
 * what that type covers, and unlike `dataSync` it has no daily time limit to run out mid-day.
 */
class InboxService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, before anything that can fail: see NearbyService for the five-second fuse.
        val started = runCatching { startForegroundCompat() }.isSuccess
        val graph = runCatching { FiletApp.graphOf(applicationContext) }.getOrNull()
        if (!started || graph == null || !graph.nearby.incoming.settings.value.ready) {
            stopSelfQuietly()
            return START_NOT_STICKY
        }
        graph.nearby.holdInboxOpen()
        // Sticky: this one was switched on to stay, and coming back after the system reclaimed it
        // is what "ready" means. The start path above has nothing in it that can fail in a loop.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { FiletApp.graphOf(applicationContext).nearby.releaseInbox() }
        super.onDestroy()
    }

    private fun stopSelfQuietly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ready for incoming files", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Filet listens for sends with the app closed. Silent."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ready for incoming files")
            .setContentText("Turn off in Share › Incoming files.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "filet.inbox"
        private val ID = dev.niccc2007.filet.Notifications.INBOX

        /** Guarded: from the background Android 12 and later refuse the start with a throw. */
        fun start(context: Context): Boolean = runCatching {
            val intent = Intent(context, InboxService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, InboxService::class.java)) }
        }
    }
}
