package dev.niccc2007.filet.shortcuts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import dev.niccc2007.filet.FiletApp

/**
 * The launcher saying a shortcut is now on the home screen.
 *
 * Handed to `requestPinShortcut` as its result, and sent only when the shortcut is placed: Cancel
 * and Back send nothing, which is why a turned-down request is found by [PinReconcile] instead.
 *
 * The intent carries the whole record, not just its id. The launcher's dialog can stay open long
 * enough for the system to end Filet's process, and with it the in-memory request; the record
 * still arrives with the confirmation.
 */
class PinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val record = read(intent) ?: return
        FiletApp.graphOf(context).shortcuts.confirm(record)
    }

    companion object {
        private const val E_ID = "filet.pin.id"
        private const val E_KIND = "filet.pin.kind"
        private const val E_LABEL = "filet.pin.label"
        private const val E_TARGET = "filet.pin.target"
        private const val E_HANDLER = "filet.pin.handler"

        /** The result `requestPinShortcut` sends when [record] is placed. One per id, never shared. */
        fun callback(context: Context, record: ShortcutRecord): IntentSender {
            val intent = Intent(context, PinResultReceiver::class.java).apply {
                // The data makes each id's PendingIntent its own; extras alone do not.
                data = Uri.fromParts("filet-pin", record.id, null)
                putExtra(E_ID, record.id)
                putExtra(E_KIND, record.kind.name)
                putExtra(E_LABEL, record.label)
                putExtra(E_TARGET, record.target)
                record.handler?.let { putExtra(E_HANDLER, it) }
            }
            return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ).intentSender
        }

        private fun read(intent: Intent): ShortcutRecord? {
            val id = intent.getStringExtra(E_ID) ?: return null
            val kind = runCatching { ShortcutKind.valueOf(intent.getStringExtra(E_KIND).orEmpty()) }.getOrNull() ?: return null
            return ShortcutRecord(
                id = id,
                kind = kind,
                label = intent.getStringExtra(E_LABEL).orEmpty(),
                target = intent.getStringExtra(E_TARGET).orEmpty(),
                handler = intent.getStringExtra(E_HANDLER),
            )
        }
    }
}
