package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.vfs.VNode

/**
 * The pictures and videos a viewer can swipe through: the ones in the list it was opened from,
 * in that list's order, and where in them it is now.
 *
 * "The list it was opened from" is whatever the tap came out of - a folder as sorted, a search's
 * results, a category, New files - so a swipe goes where the eye expects, to the tile beside the
 * one that was tapped. Folders, documents and sound are left out; a swipe from a photo that
 * landed on a PDF would be a different screen arriving where a picture was expected.
 *
 * Pure, so which files a swipe can reach and what the counter says are a test.
 */
data class ViewerRun(
    val items: List<VNode>,
    val index: Int,
    /**
     * Whether a video standing at [index] plays at once. True for a run that was opened - a tap
     * on a video always played it - and false for one rebuilt around a delete, where the file
     * that slid into place was arrived at, not chosen.
     */
    val playOnOpen: Boolean = true,
) {

    val current: VNode get() = items[index]

    /** "12 / 240", for the viewer's subtitle. */
    val label: String get() = "${index + 1} / ${items.size}"

    /** The run moved to [to], or this run if [to] is outside it. */
    fun at(to: Int): ViewerRun = if (to in items.indices) copy(index = to) else this

    /**
     * The run with the file at [at] taken out, after it was deleted from inside the viewer, or
     * null when it was the only one.
     *
     * Standing on the file that took its place - the next one, or the previous one when the last
     * went - because a gallery that closes on every delete makes clearing out a burst of photos
     * one open per photo.
     */
    fun without(at: Int): ViewerRun? {
        if (at !in items.indices) return this
        val rest = items.filterIndexed { i, _ -> i != at }
        if (rest.isEmpty()) return null
        val stand = when {
            index > at -> index - 1
            index == at -> at.coerceAtMost(rest.lastIndex)
            else -> index
        }
        return ViewerRun(rest, stand, playOnOpen = false)
    }

    companion object {
        /**
         * The run [at] belongs to in [list], or null when there is nothing to swipe to.
         *
         * @param inside whether a file opens in Filet's own picture or video viewer. A file
         *   whose type is sent to another app is not a page here - swiping onto it would have to
         *   leave Filet.
         */
        fun of(list: List<VNode>?, at: VNode, inside: (VNode) -> Boolean): ViewerRun? {
            if (list.isNullOrEmpty() || !inside(at)) return null
            val items = list.filter { !it.isDir && inside(it) }.distinctBy { it.path }
            val index = items.indexOfFirst { it.path == at.path }
            if (index < 0 || items.size < 2) return null
            return ViewerRun(items, index)
        }
    }
}
