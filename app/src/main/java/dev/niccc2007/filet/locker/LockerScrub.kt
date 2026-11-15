package dev.niccc2007.filet.locker

import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.browser.TabRecord

/**
 * What the saved tabs keep of the Locker: that a tab was in it, and nothing about where.
 *
 * The tab list is written to settings on every walk, so a tab left open inside the Locker would
 * put its folder names in a file any backup or root shell can read. A tab in the Locker is saved
 * as the Locker's top - it comes back locked, behind the unlock card, which is where it would be
 * anyway - and every other tab drops whatever Locker places its Back, Forward and scroll memory
 * hold.
 */
object LockerScrub {

    fun tab(r: TabRecord): TabRecord {
        if (r.path?.let(LockerPaths::isPrivate) == true) return TabRecord(kind = PaneKind.FOLDER, path = LockerPaths.ROOT)
        val back = r.back.filterNot { it.path?.let(LockerPaths::isPrivate) == true }.map(::tab)
        val forward = r.forward.filterNot { it.path?.let(LockerPaths::isPrivate) == true }.map(::tab)
        return r.copy(
            folds = r.folds.filterNot(LockerPaths::isPrivate),
            focus = r.focus?.takeUnless(LockerPaths::isPrivate),
            places = r.places.filterNot { LockerPaths.isPrivate(it.first) },
            kept = r.kept.filter { (k, v) -> !LockerPaths.isPrivate(k) && !LockerPaths.isPrivate(v) },
            back = back,
            forward = forward,
        )
    }
}
