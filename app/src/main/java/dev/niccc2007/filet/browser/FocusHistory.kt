package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * Which history entries are focus steps rather than moves.
 *
 * A tree's focus is a place too: Back after tapping from one branch to another should put the
 * rail back where it was, without leaving the folder or re-reading it. A focus step is an entry
 * for the folder the pane is already in, which is the only way two neighbouring entries can
 * share a folder - walking always changes it.
 */
object FocusHistory {

    fun isFocusStep(nowIsFolder: Boolean, nowCwd: VPath?, entryIsFolder: Boolean, entryCwd: VPath?): Boolean =
        nowIsFolder && entryIsFolder && nowCwd != null && nowCwd == entryCwd
}
