package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath

/**
 * Which sources answer a search, and why more than one of them has to.
 *
 * Bug identified: the pane picked `searchSources.firstOrNull { it.handles(req) }` and ran that
 * one alone. The index claims `SUBFOLDERS`, so a search under a folder the index had not
 * crawled yet was answered by the index, found nothing, and stopped. The walk - which would
 * have found it by reading the folders - never ran at all.
 *
 * Reported exactly that way: a file in a subfolder of Download, with Subfolders selected, not
 * found; the same search from inside the subfolder found it immediately. That second case
 * worked only because the index *declines* a single-folder search, which let the walk run.
 *
 * **An empty answer from a cold index is the worst answer available.** It is indistinguishable
 * from "there is no such file", and the user has no way to tell which they were told.
 *
 * So the index no longer gets to answer alone. It answers first because it is fast, and the
 * walk runs beside it and fills in whatever the index has not reached. They are deduplicated
 * by path where the results are published, so the same file found twice is shown once.
 */
enum class SourceKind {
    /** Fast, complete only where the crawl has reached, and able to answer provenance. */
    INDEX,

    /** Slow, and certain about whatever it is pointed at. */
    WALK,
}

/**
 * Whether a search may be answered by the index, the walk, or both.
 *
 * @param indexUsable the index exists, is enabled, and has a database to read.
 * @param nativeOnly the user has asked for a native search explicitly. A control rather than a
 *   guess: when a result is missing, "look again properly" should be something that can be
 *   pressed, not something to be inferred from how long the app waited.
 * @return the sources to run, in the order they should be started. Never empty for a scope a
 *   walk can serve.
 */
fun sourcePlan(
    indexUsable: Boolean,
    reach: Reach,
    nativeOnly: Boolean,
    provenance: Boolean = false,
): List<SourceKind> {
    // Provenance is a question about where a file came from, which is recorded in the index
    // and is not written on the filesystem anywhere. A walk cannot answer it at all, so this
    // is the one query the toggle cannot override. It is a property of the QUERY and not of
    // the reach - `from:youtube` narrows a column, not a set of drives (SEARCH.md §13.4).
    if (provenance) {
        return if (indexUsable) listOf(SourceKind.INDEX) else emptyList()
    }
    if (nativeOnly) return listOf(SourceKind.WALK)
    // Index first: it is the one that can answer before the finger leaves the key. The walk
    // runs beside it rather than after it, because a user who is going to be shown a result
    // should not wait for a crawl to fail first.
    if (indexUsable) return listOf(SourceKind.INDEX, SourceKind.WALK)
    return listOf(SourceKind.WALK)
}

/**
 * Whether the index alone would have been trusted for this scope, before the fix.
 *
 * Kept as a named thing because it is exactly the set of cases that were broken, and a test
 * asserting they now also walk is more honest than a test asserting a list literal.
 */
fun wasIndexOnly(reach: Reach, provenance: Boolean = false): Boolean =
    provenance || reach != Reach.Folder

/**
 * Whether turning on a native-only search could change the answer at all.
 *
 * Bug identified, reported twice: the toggle appeared to do nothing. Part of it was a real
 * fault in how a source was chosen, and part of it is this - for a single-folder scope the
 * index never answers anyway, so removing it removes nothing and the list correctly does not
 * move. A control that cannot change the outcome is a dead switch (PLAN.md R1), and the honest
 * fix is not to hide the control but to say when it is already the only thing running.
 */
fun nativeToggleMatters(indexUsable: Boolean, reach: Reach, provenance: Boolean = false): Boolean {
    if (!indexUsable) return false
    if (provenance) return false
    // Folder is declined by the index, so the walk is already the only source.
    return reach != Reach.Folder
}

/**
 * What to tell the reader about where the answer came from.
 *
 * Without this, a toggle that re-runs a search and gets the same rows is indistinguishable
 * from a toggle that is not wired to anything. The line names the sources, so pressing it
 * always changes something visible even when it does not change the rows.
 */
fun originLine(sources: List<SourceKind>): String = when {
    sources.isEmpty() -> "Nothing here can answer that"
    sources.size > 1 -> "Index, and reading the folders"
    sources.first() == SourceKind.INDEX -> "Index only"
    else -> "Reading the folders"
}

/**
 * The roots a walk still has to read while the index answers beside it. SEARCH.md §14.2,
 * decision 15.
 *
 * Bug identified here: with the index usable, the walk ran beside it over every drive in reach,
 * so a whole-phone search re-read all of internal storage on every keystroke to find what the
 * index had already answered in a millisecond. That walk is where the scattered-letter junk came
 * from, and it is what kept the progress line up for seconds after the answer was on screen.
 *
 * A drive the index has walked completely is the index's to answer. The walk keeps the drives
 * it has not - a card never crawled, a share with no complete index - and every tree-shaped
 * reach, which is one folder or one subtree and cheap to read.
 *
 * @param covered the `volume.uuid` of each drive with a complete pass. See
 *   `FileIndex.completeVolumes`.
 */
fun walkGaps(roots: List<VPath>, covered: Set<String>, reach: Reach): List<VPath> = when (reach) {
    Reach.Folder, Reach.Subtree -> roots
    Reach.Everywhere, is Reach.Drives -> roots.filter { it.toString() !in covered }
}

/** Whether [reach] is answered from the drives list rather than from the folder being stood in. */
fun spansDrives(reach: Reach): Boolean = when (reach) {
    Reach.Folder, Reach.Subtree -> false
    Reach.Everywhere, is Reach.Drives -> true
}
