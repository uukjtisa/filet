package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath

/**
 * Which volumes a crawl is allowed to walk. SEARCH.md §13.6 and §13.9.
 *
 * ## The defect this is the fix for
 *
 * Measured: this app at **46% of a day's battery**, four times the next application. One line
 * did it - the browser called `crawlFully(roots)` unconditionally on every open, with no budget,
 * and `roots` is every volume `vfs.roots()` returns. On a phone with two shares mounted and root
 * granted that is 340 GB over Wi-Fi plus `/data`, walked from the beginning, several times a day,
 * because `start()` runs in any activity hosting the browser - including the file picker another
 * app opens.
 *
 * The gate was not missing. `IndexCoordinator.onStart` has always decided properly, on a
 * six-hour staleness window. There were simply two launch paths and the careless one won.
 *
 * ## Why this is a function and not a condition at each call site
 *
 * Because there are five call sites - the quiet pass on open, the startup decision, Index now,
 * the maintenance worker, and the walk-into-a-folder pass - and a rule spread over five `if`s is
 * a rule that holds until somebody adds a sixth. The scheme list below is the only place that
 * decides, every crawl entry point reads it, and `check-reach.mjs` fails the build when a remote
 * provider appears that this file has not been told about.
 */
object Indexable {

    /**
     * The subset of [roots] a crawl may walk.
     *
     * @param indexedShares the `volume.uuid` of each share that has been opted in by hand -
     *   `smb:///n1a2b3c`. Empty by default, which is the state every share is in until somebody
     *   turns it on, and the reason a network drive costs nothing unless it was asked for.
     */
    fun volumes(roots: List<VPath>, indexedShares: Set<String> = emptySet()): List<VPath> =
        roots.filter { allowed(it, indexedShares) }

    /** Whether one volume may be crawled, and the whole decision in one `when`. */
    fun allowed(root: VPath, indexedShares: Set<String> = emptySet()): Boolean = when {
        // The ordinary case, and the only one that is free: a local filesystem with inotify on
        // its hot directories and a `stat` that costs microseconds.
        root.scheme == "local" -> true

        // A granted tree is on a local volume too. It has no inotify (SEARCH.md §4.6), which
        // costs it freshness rather than battery.
        root.scheme == "saf" -> true

        // NEVER. Reachable by being ticked in the volume sheet, which walks it live; crawling it
        // would put tens of thousands of churning system files into an index nobody searches by
        // name, and would do it on the battery that this whole round is about.
        root.scheme == "root" -> false

        // A share, and only when somebody turned it on for that share. The default is off
        // forever: walking 222 GB over Wi-Fi to answer a search that may never be typed is the
        // same defect as the unbounded local crawl with a worse constant.
        root.scheme in VolumeKey.REMOTE_SCHEMES -> root.toString() in indexedShares

        // An archive or a package is not a volume a crawl walks. What is inside one is indexed
        // by fact extraction when the container itself is reached - SEARCH.md §3 - which is
        // budgeted separately and evictable.
        else -> false
    }
}
