package dev.niccc2007.filet.icons

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FolderPeek
import dev.niccc2007.filet.browser.PeekKind
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.shortcuts.ShortcutIcon
import dev.niccc2007.filet.shortcuts.ShortcutKind
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A folder's home-screen icon: the same drawing as its row in the list, on no tile.
 *
 * Drawn by the same code as the row (through a bitmap rather than a Canvas on screen), so a folder
 * wearing a camera in the list wears it on the home screen too, empty or full as it is. Two things
 * differ, both to stand beside Filet's own icon: nothing is drawn behind it, as nothing is behind
 * Filet's, and its blues are the build's icon colours (amber in the debug build). See [BrandTone].
 *
 * ## Keeping it current
 *
 * A launcher keeps the icon it was given. So the icon is drawn again, and handed over with
 * `updateShortcuts`, when the folder's look is changed in Filet, and when Filet comes to the front
 * and a folder has gone from empty to full or back. Only icons whose drawing changed are sent: the
 * last one sent is remembered by what went into it.
 *
 * A shortcut given a look of its own on the shortcut sheet keeps that look whatever the folder
 * wears later; it is still redrawn as the folder fills or empties.
 */
object FolderShortcutIcon {

    /** The bitmap's side. A launcher draws an adaptive icon at 108dp; this is about 2.7 pixels to a dp. */
    private const val SIDE = 288

    /**
     * How much of the side the folder's own box takes: the folder must sit in the 66dp circle
     * every launcher's mask keeps, and its corners reach out about 0.56 of its box from the centre.
     */
    private const val ART = 0.56f

    private const val PICTURE_PX = 120

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What each pinned shortcut was last drawn from, by its id. */
    private val sent = ConcurrentHashMap<String, String>()

    /**
     * The icon for [node] as it is drawn now, from what is already known: the folder's last peek and
     * the thumbnails already decoded. For the moment of pinning, which must not wait on a disk.
     * Anything not known yet comes in with the next [refresh].
     */
    fun now(context: Context, node: VNode, own: FolderLook? = null): Icon =
        dev.niccc2007.filet.shortcuts.ShortcutArt.bitmapIcon(bitmapNow(context, node, own))

    /** [now]'s drawing as a bitmap, for a preview of the icon before it is placed. */
    fun bitmapNow(context: Context, node: VNode, own: FolderLook? = null): Bitmap {
        val graph = FiletApp.graphOf(context)
        val look = own ?: lookFor(FolderStyles.of(context).byPath.value, node.path)
        val peek = graph.folderPeek.cached(node.path, node.mtime)
        val inside = if (peek == null) Inside.Pages else insideOf(peek) { n -> Thumbnails.peek(n.path, n.size, n.mtime, PICTURE_PX)?.bitmap }
        return draw(context, inside, look)
    }

    /**
     * The icon for [node] read from the disk where it must be: its contents and their pictures. For
     * a preview that can wait a moment, so it shows what the home screen will.
     */
    suspend fun bitmap(context: Context, node: VNode, own: FolderLook? = null): Bitmap {
        val graph = FiletApp.graphOf(context)
        val look = own ?: lookFor(FolderStyles.of(context).byPath.value, node.path)
        val peek = runCatching { graph.folderPeek.of(node.path, node.mtime) }.getOrNull()
            ?: return bitmapNow(context, node, own)
        val inside = insideOf(peek) { n -> runCatching { Thumbnails.get(context, graph.vfs, n.path, n.size, n.mtime, PICTURE_PX) }.getOrNull() }
        return draw(context, inside, look)
    }

    /** Draw again every pinned folder shortcut among [paths], or all of them when null. */
    fun refresh(context: Context, paths: Collection<VPath>? = null) {
        val app = context.applicationContext
        scope.launch { runCatching { refreshNow(app, paths?.map { it.toString() }?.toSet()) } }
    }

    private suspend fun refreshNow(context: Context, only: Set<String>?) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val graph = FiletApp.graphOf(context)
        val pinned = runCatching { sm.pinnedShortcuts.map { it.id }.toSet() }.getOrNull() ?: return
        val folders = graph.shortcuts.all.value.filter {
            it.kind == ShortcutKind.FILE && it.dir == true && it.id in pinned && (only == null || it.target in only)
        }
        if (folders.isEmpty()) return
        val styles = FolderStyles.of(context).byPath.value
        val updates = mutableListOf<ShortcutInfo>()
        for (record in folders) {
            val path = runCatching { VPath.parse(record.target) }.getOrNull() ?: continue
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()?.takeIf { it.isDir } ?: continue
            // A look of its own, set on the shortcut sheet, rather than the folder's.
            val own = (ShortcutIcon.parse(record.icon) as? ShortcutIcon.Folder)?.own
            val look = own ?: lookFor(styles, path)
            val peek = runCatching { graph.folderPeek.of(path, node.mtime) }.getOrNull() ?: continue
            val inside = insideOf(peek) { n -> runCatching { Thumbnails.get(context, graph.vfs, n.path, n.size, n.mtime, PICTURE_PX) }.getOrNull() }
            // Pictures by name: a decoded bitmap is a new object each time and would never match.
            val drawnFrom = "$look|${peek.empty}|" + peek.items.joinToString { "${it.node.name}:${it.kind}:${it.full}" }
            if (sent[record.id] == drawnFrom) continue
            val icon = dev.niccc2007.filet.shortcuts.ShortcutArt.bitmapIcon(draw(context, inside, look))
            updates += ShortcutInfo.Builder(context, record.id).setIcon(icon).build()
            sent[record.id] = drawnFrom
        }
        if (updates.isNotEmpty()) runCatching { sm.updateShortcuts(updates) }
    }

    /** What stands in the folder, as the list draws it: a picture with no preview stands as its type. */
    private fun insideOf(peek: FolderPeek.Peek, picture: (VNode) -> Bitmap?): Inside {
        if (peek.empty) return Inside.Empty
        if (peek.items.isEmpty()) return Inside.Pages
        return Inside.Things(
            peek.items.map { item ->
                when (item.kind) {
                    PeekKind.MEDIA -> picture(item.node)
                        ?.let { Thing.Picture(it.asImageBitmap(), video = FileKind.of(item.node) == FileKind.VIDEO) }
                        ?: Thing.File(FileTypes.icon(item.node.extension))
                    PeekKind.FILE -> Thing.File(FileTypes.icon(item.node.extension))
                    PeekKind.FOLDER -> Thing.Folder(item.full ?: true)
                }
            },
        )
    }

    /** The folder on a clear square, in the build's colours, with the launcher's mask left to cut the edges. */
    internal fun draw(context: Context, inside: Inside, look: FolderLook): Bitmap {
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val margin = SIDE * (1f - ART) / 2f
        BrandTone.drawing(BrandTone.of(context)) {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out.asImageBitmap()), Size(SIDE.toFloat(), SIDE.toFloat())) {
                // Colour all over from the folder's own paint, else the lists' Folders choice.
                val whole = wholeFor(LegacyLooks.of(context).styles.value.files.folders, look)
                inset(margin, margin, margin, margin) { drawFolder(inside, look, whole = whole) }
            }
        }
        return out
    }
}
