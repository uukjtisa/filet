package dev.niccc2007.filet.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.R
import dev.niccc2007.filet.browser.humanSize

/**
 * A send from another Filet, asked about in a notification: when "How a send asks" is set to
 * Notification, or when Filet may not draw over other apps. Accept and Decline answer from the
 * shade; tapping it opens Filet on the same question.
 *
 * Heads-up importance, because a send that waits silently in the shade is the problem the card
 * exists to solve.
 */
object IncomingNotice {

    fun show(context: Context, offer: PushOffer) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${offer.name} ${IncomingRules.wants(offer.files.size)}")
            .setContentText(IncomingRules.summary(offer.files) { humanSize(it) })
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action(context, offer, ACTION_DECLINE, "Decline", 2))
            .addAction(action(context, offer, ACTION_ACCEPT, "Accept", 3))
            .build()
        runCatching { manager.notify(ID, n) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }

    private fun action(context: Context, offer: PushOffer, action: String, label: String, code: Int): Notification.Action {
        val intent = Intent(context, IncomingAnswer::class.java).setAction(action).putExtra(EXTRA_ID, offer.id)
        val pending = PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pending).build()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Another Filet asking to send you files."
                setShowBadge(true)
            },
        )
    }

    const val ACTION_ACCEPT = "dev.niccc2007.filet.nearby.INCOMING_ACCEPT"
    const val ACTION_DECLINE = "dev.niccc2007.filet.nearby.INCOMING_DECLINE"
    const val EXTRA_ID = "offer"
    private const val CHANNEL = "filet.incoming"
    private val ID = dev.niccc2007.filet.Notifications.INCOMING
}

/**
 * The notification's Accept and Decline. Not exported: only Filet's own notification reaches it,
 * and an offer is answered only while it is still waiting.
 */
class IncomingAnswer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(IncomingNotice.EXTRA_ID) ?: return
        val inbox = runCatching { FiletApp.graphOf(context.applicationContext).nearby.inbox }.getOrNull() ?: return
        val offer = inbox.offers.value.firstOrNull { it.id == id }
        IncomingNotice.cancel(context)
        if (offer == null) return
        when (intent.action) {
            IncomingNotice.ACTION_ACCEPT -> inbox.accept(offer, always = false)
            IncomingNotice.ACTION_DECLINE -> inbox.decline(offer)
        }
    }
}
