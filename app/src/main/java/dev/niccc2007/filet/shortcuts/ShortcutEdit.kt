package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.script.Script
import dev.niccc2007.filet.vfs.VPath

/**
 * The file an edited file shortcut is for, found the way a tap on its icon finds it: by its file id
 * first, then where it was pinned.
 *
 * A file moved or renamed since it was pinned still opens from the home screen, because the icon
 * carries its id. Looking only where it was pinned called it gone, so the one icon that still
 * worked could not be edited.
 *
 * @param byId the file's path now, from the index.
 * @param stat the file at a path, or null when nothing is there.
 */
internal suspend fun <N : Any> findForEdit(
    record: ShortcutRecord,
    byId: suspend (Long) -> VPath?,
    stat: suspend (VPath) -> N?,
): N? {
    val now = record.fileId?.let { byId(it) }
    val pinnedAt = runCatching { VPath.parse(record.target) }.getOrNull()
    for (path in listOfNotNull(now, pinnedAt).distinct()) stat(path)?.let { return it }
    return null
}

/**
 * What the shortcut sheet edits for [record], or null when what it opens is gone.
 *
 * Every kind, and every record however old: one kept before the sheet existed has no icon and,
 * for a file, no `dir`, and neither is needed here. The file is looked at afresh, and the sheet
 * opens on the kind's own icon when none was chosen.
 *
 * @param file what [findForEdit] found, as the sheet's subject.
 * @param script a script by its id.
 */
internal fun editSubject(record: ShortcutRecord, file: PinSubject.File?, script: (String) -> Script?): PinSubject? =
    when (record.kind) {
        ShortcutKind.FILE -> file
        ShortcutKind.TAB -> TabTarget.byId(record.target)?.let { PinSubject.Tab(it) }
        ShortcutKind.ACTION -> AppAction.parse(record.target)?.let { PinSubject.Action(it) }
        ShortcutKind.SCRIPT -> script(record.target)?.let { PinSubject.Scripted(it) }
    }
