package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A glance at what is inside a folder, for drawing on its icon.
 *
 * ## Why a folder needed this
 *
 * Every folder in the app looked identical, which is most of what made long lists feel
 * uniform and lifeless - reported in exactly those words. A folder of photographs and a
 * folder of invoices are not the same object and should not be the same picture. Windows
 * has done this for twenty years for the same reason.
 *
 * ## What it costs, and the three things keeping that down
 *
 * It is a directory read per folder row, which is exactly the cost `rememberFolderCount`
 * exists to be careful about, so:
 *
 *  1. **Cached on path AND mtime.** A folder that has not changed is read once, ever, for as
 *     long as the app is alive. Scrolling back up a list costs nothing.
 *  2. **Two at a time.** A screen of twelve folders would otherwise open twelve directories
 *     at once on a card that is already handing out thumbnails.
 *  3. **It stops early.** [SCAN] entries are enough to find three things worth showing in any
 *     folder, and a folder of ten thousand files must not be walked to draw a 30dp icon.
 *
 * What comes back is child NODES rather than bitmaps: the thumbnail pipeline already decodes,
 * caches and sizes those, and a second one here would be a second cache to keep in step.
 */
class FolderPeek(private val vfs: Vfs) {

    /**
     * What a glance found: what to draw, and whether there is anything in there at all.
     *
     * The second is free - the folder has just been listed - and it is what lets a tree stop
     * putting an expand arrow on a folder that cannot expand. An arrow that does nothing is
     * the thing R1 forbids, and in a tree it is worse than usual: you tap it, nothing moves,
     * and you cannot tell whether the folder is empty or the app is broken.
     */
    data class Peek(val items: List<Item>, val empty: Boolean)

    /**
     * One thing standing in the folder, and how it is drawn.
     *
     * @param full for a [PeekKind.FOLDER], whether it has anything in it, which puts a sheet in
     *   its drawing. Null when that could not be read.
     */
    data class Item(val node: VNode, val kind: PeekKind, val full: Boolean? = null)

    private val cache = ConcurrentHashMap<String, Peek>()
    private val gate = Semaphore(AT_ONCE)

    fun cached(path: VPath, mtime: Long): Peek? = cache[key(path, mtime)]

    suspend fun of(path: VPath, mtime: Long): Peek {
        val k = key(path, mtime)
        cache[k]?.let { return it }
        val found = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val listing = vfs.list(path)
                    // Hidden entries count towards "not empty" but are never drawn: a .nomedia
                    // beside the photos would turn a folder of pictures into a mixed one.
                    val shown = listing.asSequence().take(SCAN).filterNot { it.hidden || it.name.startsWith(".") }.toList()
                    val picked = pickPeek(shown, ::kindOf, ::typeOf, SHOW).map { child ->
                        val kind = kindOf(child)
                        // A folder inside is drawn with a sheet in it when it has anything. One
                        // count per picked folder, at most three, and a count is one readdir.
                        val full = if (kind == PeekKind.FOLDER) vfs.countChildren(child.path)?.let { it > 0 } else null
                        Item(child, kind, full)
                    }
                    Peek(picked, empty = listing.isEmpty())
                }.getOrDefault(Peek(emptyList(), empty = false))
            }
        }
        cache[k] = found
        return found
    }

    fun clear() = cache.clear()

    private fun key(path: VPath, mtime: Long) = "$path@$mtime"

    private companion object {
        /** A child with a picture of its own is drawn as that picture; any other file as its type. */
        fun kindOf(node: VNode): PeekKind = when {
            node.isDir -> PeekKind.FOLDER
            Thumbnails.canPreview(node.extension) -> PeekKind.MEDIA
            else -> PeekKind.FILE
        }

        /** "The same type", for varying the pick: pictures against videos, a PDF against a sheet. */
        fun typeOf(node: VNode): String = when (kindOf(node)) {
            PeekKind.FOLDER -> "folder"
            PeekKind.MEDIA -> FileKind.of(node).name
            PeekKind.FILE -> node.extension.lowercase(java.util.Locale.US)
        }

        /** How many things stand in one folder. Three fit; four is a mess. */
        const val SHOW = 3

        /** How far into a folder to look when choosing what stands in it. */
        const val SCAN = 60

        /** How many folders are read at once. */
        const val AT_ONCE = 2
    }
}

/** What stands in this folder's icon, read off the main thread and remembered. */
@Composable
fun rememberFolderPeek(
    peek: FolderPeek,
    path: VPath,
    mtime: Long,
    enabled: Boolean,
): State<FolderPeek.Peek> =
    produceState(peek.cached(path, mtime) ?: FolderPeek.Peek(emptyList(), empty = false), path, mtime, enabled) {
        if (!enabled) return@produceState
        value = peek.of(path, mtime)
    }
