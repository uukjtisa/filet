package dev.niccc2007.filet.index

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
    scope: SearchScope,
    nativeOnly: Boolean,
): List<SourceKind> {
    // Provenance is a question about where a file came from, which is recorded in the index
    // and is not written on the filesystem anywhere. A walk cannot answer it at all, so this
    // is the one scope the toggle cannot override.
    if (scope == SearchScope.PROVENANCE) {
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
fun wasIndexOnly(scope: SearchScope): Boolean =
    scope == SearchScope.SUBFOLDERS || scope == SearchScope.DEVICE || scope == SearchScope.PROVENANCE
