package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * What tapping a saved place should do.
 *
 * A bookmark and a recent entry are both "a path somebody kept", and both can be a folder or
 * a file. Until now the bookmark list assumed folder and called `navigateTo` on everything,
 * so bookmarking a `.pptx` and tapping it tried to list a presentation as a directory.
 *
 * The decision lives here rather than inside the row's `onClick` for one reason: that is where
 * it was, and nothing could reach it to prove it wrong.
 */
sealed interface PlaceAction {
    /** A directory. Walk into it. */
    data class Navigate(val path: VPath) : PlaceAction

    /** A file. Hand it to whatever opens that type. */
    data class OpenFile(val path: VPath) : PlaceAction

    /**
     * Nobody recorded which it is.
     *
     * Bookmarks saved before this existed have no flag, and deleting them to fix a bug would
     * be a worse bug. The caller stats the path and asks again with the answer.
     */
    data class Resolve(val path: VPath) : PlaceAction
}

/**
 * @param isDir what the stored record says, or null when the record predates the field.
 */
fun placeAction(path: VPath, isDir: Boolean?): PlaceAction = when (isDir) {
    true -> PlaceAction.Navigate(path)
    false -> PlaceAction.OpenFile(path)
    null -> PlaceAction.Resolve(path)
}

/** The same decision once a stat has answered. A missing file is a file, and opening it reports. */
fun placeActionResolved(path: VPath, statSaysDir: Boolean): PlaceAction =
    if (statSaysDir) PlaceAction.Navigate(path) else PlaceAction.OpenFile(path)
