package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.R
import dev.niccc2007.filet.index.FileIndex
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
     * @param handlerOverride a handler id from the registry, or null to follow the per-type
     *   default. The default is a *routing* concern; a shortcut merely inherits it.
     * @return false when the launcher refused, so the caller can say so rather than pretending.
     */
    suspend fun pin(
        context: Context,
        node: VNode,
        index: FileIndex,
        label: String = node.name,
        handlerOverride: String? = null,
    ): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val id = index.idFor(node.path)
        val info = build(context, node, id, label, handlerOverride) ?: return false
        return runCatching { sm.requestPinShortcut(info, null) }.getOrDefault(false)
    }

    private fun build(
        context: Context,
        node: VNode,
        fileId: Long?,
        label: String,
        handlerOverride: String?,
    ): ShortcutInfo? {
        val intent = Intent(context, ShortcutRouterActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // The ID is the address. The path rides along ONLY as a fallback for a file the
            // index has never seen, and is re-resolved to an ID the first time it is used.
            fileId?.let { putExtra(EXTRA_FILE_ID, it) }
            putExtra(EXTRA_FALLBACK_PATH, node.path.toString())
            handlerOverride?.let { putExtra(EXTRA_HANDLER, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return runCatching {
            ShortcutInfo.Builder(context, shortcutId(fileId, node.path))
                .setShortLabel(label.take(24))
                .setLongLabel(label.take(48))
                .setIcon(iconFor(context, node))
                .setIntent(intent)
                .build()
        }.getOrNull()
    }

    fun shortcutId(fileId: Long?, path: VPath): String =
        if (fileId != null) "id:$fileId" else "path:${path}"

    /**
     * A home-screen shortcut for something that is not a file: a script, or an app action.
     *
     * Same router, different payload. The router already resolves an id to a path; these
     * carry an action name instead, and it dispatches on which extra is present.
     */
    fun pinAction(
        context: Context,
        id: String,
        label: String,
        action: String,
        iconRes: Int = R.mipmap.ic_launcher,
    ): Boolean {
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
                .setIcon(Icon.createWithResource(context, iconRes))
                .setIntent(intent)
                .build()
        }.getOrNull() ?: return false
        return runCatching { sm.requestPinShortcut(info, null) }.getOrDefault(false)
    }

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

    /**
     * A thumbnail for images, a tinted glyph for everything else.
     *
     * Decoded small on purpose: a launcher icon is about 108dp, and handing it a 12 MP bitmap
     * throws `TransactionTooLargeException` across the binder.
     */
    private fun iconFor(context: Context, node: VNode): Icon {
        val graph = FiletApp.graphOf(context)
        // One preview engine for the whole app.
        //
        // This used to handle images only, by hand - so a shortcut to a video got the generic
        // Filet icon and there was no way to tell three of them apart on a home screen.
        // Thumbnails covers images, video frames and APK icons, and caches, so a shortcut and
        // the list row it came from now show the same picture.
        val bmp = runCatching {
            dev.niccc2007.filet.media.Thumbnails.get(
                context, graph.vfs, node.path, node.size, node.mtime, px = 216,
            )
        }.getOrNull()
        if (bmp != null) return Icon.createWithAdaptiveBitmap(square(bmp))
        return Icon.createWithResource(context, R.mipmap.ic_launcher)
    }

    /** Adaptive icons are square with a safe zone; a 16:9 crop would be cut to a sliver. */
    private fun square(src: Bitmap): Bitmap {
        val size = 216
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = size.toFloat() / min(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        canvas.drawBitmap(
            src,
            null,
            RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }
}
