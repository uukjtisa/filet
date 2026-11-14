package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlin.math.min

/**
 * Pinned shortcuts, done the way the problem actually requires.
 *
 * > **THE SHORTCUT RULE:** a shortcut never stores a path. It stores a Filet file ID, and
 * > Filet resolves ID to the current path at launch (PLAN.md L5).
 *
 * Paths break on every move and rename, which is why every other file manager's shortcuts
 * quietly rot. The index assigns the ID and maintains the mapping as the crawler observes
 * changes, so moving the target file just works and deleting it produces a real message
 * instead of a dead tile.
 *
 * This is also exactly why M4 cannot precede M3: without stable IDs there is nothing to store.
 */
object Shortcuts {

    const val EXTRA_FILE_ID = "filet.fileId"
    const val EXTRA_FALLBACK_PATH = "filet.fallbackPath"
    const val EXTRA_HANDLER = "filet.handler"

    /** A script id or an [dev.niccc2007.filet.shortcuts.AppAction] name, for non-file shortcuts. */
    const val EXTRA_ACTION = "filet.action"

    fun isSupported(context: Context): Boolean =
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    /**
     * Ask the launcher to place a shortcut to [node].
     *
     * @param record what the Shortcuts tab lists once the launcher confirms it. Its label and
     *   handler are the shortcut's; its id must be [shortcutId] of [fileId]. The handler is a
     *   handler id from the registry, or null to follow the per-type default - the default is a
     *   *routing* concern, and a shortcut merely inherits it.
     * @param icon what the shortcut sheet drew for it. See [ShortcutArt].
     * @return false when the launcher refused, so the caller can say so rather than pretending.
     */
    fun pin(context: Context, node: VNode, fileId: Long?, record: ShortcutRecord, icon: Icon): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val info = build(context, node.path, fileId, record.label, record.handler, icon) ?: return false
        return request(context, sm, info, record)
    }

    /**
     * The launcher reports back through [PinResultReceiver] when the shortcut is placed. One
     * turned off by [remove] is turned back on first: a disabled id cannot be pinned again.
     */
    private fun request(context: Context, sm: ShortcutManager, info: ShortcutInfo, record: ShortcutRecord): Boolean {
        runCatching { sm.enableShortcuts(listOf(info.id)) }
        return runCatching { sm.requestPinShortcut(info, PinResultReceiver.callback(context, record)) }.getOrDefault(false)
    }

    private fun build(
        context: Context,
        path: VPath,
        fileId: Long?,
        label: String,
        handlerOverride: String?,
        icon: Icon,
    ): ShortcutInfo? = runCatching {
        ShortcutInfo.Builder(context, shortcutId(fileId, path))
            .setShortLabel(label.take(24))
            .setLongLabel(label.take(48))
            .setIcon(icon)
            .setIntent(fileIntent(context, path, fileId, handlerOverride))
            .build()
    }.getOrNull()

    private fun fileIntent(context: Context, path: VPath, fileId: Long?, handler: String?): Intent =
        Intent(context, ShortcutRouterActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // The ID is the address. The path rides along ONLY as a fallback for a file the
            // index has never seen, and is re-resolved to an ID the first time it is used.
            fileId?.let { putExtra(EXTRA_FILE_ID, it) }
            putExtra(EXTRA_FALLBACK_PATH, path.toString())
            handler?.let { putExtra(EXTRA_HANDLER, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    fun shortcutId(fileId: Long?, path: VPath): String =
        if (fileId != null) "id:$fileId" else "path:${path}"

    /**
     * A home-screen shortcut for something that is not a file: a script, or an app action.
     *
     * Same router, different payload. The router already resolves an id to a path; these
     * carry an action name instead, and it dispatches on which extra is present.
     *
     * @param record the shortcut's id and label, and what the Shortcuts tab lists once placed.
     */
    fun pinAction(
        context: Context,
        record: ShortcutRecord,
        action: String,
        icon: Icon,
    ): Boolean {
        val id = record.id
        val label = record.label
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val intent = Intent(context, ShortcutRouterActivity::class.java).apply {
            this.action = Intent.ACTION_VIEW
            putExtra(EXTRA_ACTION, action)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val info = runCatching {
            ShortcutInfo.Builder(context, id)
                .setShortLabel(label.take(24))
                .setLongLabel(label.take(48))
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }.getOrNull() ?: return false
        return request(context, sm, info, record)
    }

    /**
     * A placed shortcut's new name, icon and, for a file, opener, handed to the launcher in place.
     * Only reaches a launcher that still has it; one taken off the home screen has nothing to change.
     */
    fun update(context: Context, record: ShortcutRecord, icon: Icon) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val b = ShortcutInfo.Builder(context, record.id)
                .setShortLabel(record.label.take(24))
                .setLongLabel(record.label.take(48))
                .setIcon(icon)
            if (record.kind == ShortcutKind.FILE) {
                b.setIntent(fileIntent(context, VPath.parse(record.target), record.fileId, record.handler))
            }
            sm.updateShortcuts(listOf(b.build()))
        }
    }

    /**
     * Turn a placed shortcut off. Android gives no app a way to take an icon off the home screen:
     * there is no inverse of `requestPinShortcut`. The launcher greys this one, and a tap on it
     * shows [REMOVED], which says how to clear it.
     */
    fun remove(context: Context, id: String) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching { sm.disableShortcuts(listOf(id), REMOVED) }
    }

    /** What a removed shortcut says when it is tapped. The launcher shows it as it is. */
    const val REMOVED = "Removed in Filet. Hold it and drag it off the home screen."

    /** Rename a live shortcut. `updateShortcuts` only touches ones this app still owns. */
    fun rename(context: Context, fileId: Long, label: String) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val existing = sm.pinnedShortcuts.firstOrNull { it.id == "id:$fileId" } ?: return
        runCatching {
            sm.updateShortcuts(
                listOf(
                    ShortcutInfo.Builder(context, existing.id)
                        .setShortLabel(label.take(24))
                        .setLongLabel(label.take(48))
                        .build()
                )
            )
        }
    }

    /**
     * The target is gone. Disable with a real message rather than leaving a tile that does
     * nothing when tapped.
     */
    fun disable(context: Context, fileId: Long, reason: String) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching { sm.disableShortcuts(listOf("id:$fileId"), reason) }
    }
}

/** Where a picture goes on a launcher icon's square. Pure, so the sums can be checked off-device. */
internal object ShortcutFit {

    /** The middle of an adaptive icon a launcher never cuts: 66 of its 108 units. */
    const val SAFE = 66f / 108f

    /**
     * left, top, right, bottom for a [w] by [h] picture on a [side] square: an opaque one cropped to
     * fill it, centred; a [seeThrough] one whole, centred, inside the safe middle.
     */
    fun rect(w: Int, h: Int, seeThrough: Boolean, side: Float): FloatArray {
        if (w <= 0 || h <= 0) return floatArrayOf(0f, 0f, side, side)
        val scale = if (seeThrough) side * SAFE / maxOf(w, h) else side / min(w, h)
        val dw = w * scale
        val dh = h * scale
        return floatArrayOf((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
    }
}
