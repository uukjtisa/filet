package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.sortedBy
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.vfs.VNode

/**
 * What plays next.
 *
 * A music player that can only play the one file you tapped is a preview, not a player, so
 * opening a track makes a queue out of the folder it lives in. The rules are here rather than
 * in the player composable because they are all edge cases - a folder of one, a track that has
 * been deleted between the listing and the tap, a wrap from the last track back to the first -
 * and edge cases in a composable are edge cases nothing can test.
 */
data class PlayQueue(val items: List<VNode>, val index: Int) {

    init {
        require(items.isNotEmpty()) { "a queue with nothing in it is not a queue" }
        require(index in items.indices) { "index $index is outside ${items.size} items" }
    }

    val current: VNode get() = items[index]
    val size: Int get() = items.size

    /** Human position, one-based, for "3 of 12". */
    val position: Int get() = index + 1

    /** Jump to a row the user tapped in the queue list. Out of range is ignored, not a crash. */
    fun at(i: Int): PlayQueue = if (i in items.indices) copy(index = i) else this

    /**
     * Move by [delta] tracks, wrapping at both ends.
     *
     * Wrapping rather than stopping: the last track's Next button being dead is a common and
     * irritating behaviour, and a queue built from a folder has no natural end anyway.
     */
    fun stepped(delta: Int): PlayQueue {
        if (items.size == 1) return this
        val next = ((index + delta) % items.size + items.size) % items.size
        return copy(index = next)
    }

    /** Drop a track that turned out not to be there, keeping the cursor on something real. */
    fun without(node: VNode): PlayQueue? {
        val remaining = items.filterNot { it.path == node.path }
        if (remaining.isEmpty()) return null
        val removedBefore = items.take(index).count { it.path == node.path }
        return PlayQueue(remaining, (index - removedBefore).coerceIn(remaining.indices))
    }
}

/**
 * Build a queue from a folder listing.
 *
 * @param siblings everything in the folder, in any order - this sorts.
 * @param opened the track that was tapped, which is where playback starts.
 * @param spec the browser's current sort, so the queue runs in the order that is on screen.
 *   Playing a folder in a different order than the one the user is looking at is disorienting
 *   even when the order itself is reasonable.
 */
fun audioQueue(siblings: List<VNode>, opened: VNode, spec: SortSpec = SortSpec()): PlayQueue {
    val playable = siblings
        .filter { !it.isDir && FileKind.of(it) == FileKind.AUDIO }
        .sortedBy(spec)
    val start = playable.indexOfFirst { it.path == opened.path }
    // The opened file is not always in the listing: it can arrive from search, from a
    // shortcut, or from another app, and it can be an extension this build does not class as
    // audio while still being something MediaPlayer opens. Playing it alone beats refusing.
    if (start < 0) return PlayQueue(listOf(opened), 0)
    return PlayQueue(playable, start)
}
