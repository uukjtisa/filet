package dev.niccc2007.filet.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.R

/**
 * Telling somebody a new version exists, without becoming the reason they mute the app.
 *
 * Nic asked for *"a notification for updates"* with the choice of when to be asked again. The
 * choosing happens in the sheet, because a notification with six actions on it is unreadable on
 * a lock screen; the notification's job is only to get somebody there. Its own dismissal is
 * treated as "Later" - swiping a notification away is an answer, and an update nag that
 * reappears after being swiped is the exact thing that gets a channel turned off.
 *
 * Everything about *whether* to post is in `UpdatePrompt.kt`. This file only posts.
 */
object UpdateNotifier {

    private const val CHANNEL = "filet.updates"
    // Was 4_201, which is the same number as NearbyService's 4201 written differently -
    // so cancelling this cancelled a running foreground service's notification and the
    // service restarted in a loop. See dev.niccc2007.filet.Notifications.
    private val ID = dev.niccc2007.filet.Notifications.UPDATE

    /** The sheet opens straight to this release rather than re-checking. */
    const val EXTRA_UPDATE = "dev.niccc2007.filet.extra.UPDATE"

    fun notify(context: Context, release: Release) {
        if (!canPost(context)) return
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java).apply {
            // Reuse the running task rather than stacking a second copy of the browser on top
            // of itself: the user is being invited back into the app they already have open.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_UPDATE, release.tag)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = buildString {
            append("You are on ")
            append(Updater.installed)
            if (release.apkBytes > 0) {
                append(" · ")
                append(release.apkBytes / (1024 * 1024))
                append(" MB")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Filet ${release.version} is out")
            .setContentText(summary)
            // The first line of the notes, which is usually the one sentence saying what the
            // release is. Better than repeating the version number in a second style.
            .setStyle(NotificationCompat.BigTextStyle().bigText(lede(release) ?: summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            // DEFAULT, not HIGH: this is never urgent. Nothing is broken if it is read tomorrow.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    /**
     * The first sentence of the release notes, if there is one worth showing.
     *
     * Parsed rather than substringed so a body that opens with an image strip or a heading does
     * not put `<div align="center">` on somebody's lock screen - which is exactly the failure
     * the renderer exists to fix, and a notification is the worst place for it.
     */
    private fun lede(release: Release): String? =
        ReleaseNotes.parse(release.notes)
            .filterIsInstance<NoteBlock.Paragraph>()
            .firstOrNull()
            ?.spans
            ?.joinToString("") { it.text }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > 240) it.take(237) + "…" else it }

    private fun canPost(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new version of Filet is published."
                setShowBadge(false)
            },
        )
    }
}
