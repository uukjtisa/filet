package dev.niccc2007.filet.index

/**
 * How much a search result is worth trusting right now. SEARCH.md §14.3.
 *
 * Two states, and deliberately only two. A third one for "this might be gone" was considered
 * and rejected: a row whose check failed is removed rather than labelled, because a row that
 * announces its own unreliability is asking the reader to do the checking the app exists to do.
 *
 * ## What changed, and why
 *
 * Confirming used to mean "an index pass is running and has not re-read this row yet". Every row
 * had in fact already been checked against the filesystem before it was shown, so the label was
 * describing the crawl rather than the row - and with an unbounded crawl on every open, results
 * read Confirming for minutes after they had been verified. It now means one thing about the row
 * itself: **it came from the index and its own check has not come back yet.**
 */
enum class HitState(val label: String) {
    /** Checked against the filesystem, or seen there by a walk a moment ago. Can be acted on. */
    AVAILABLE("Available"),

    /**
     * From the index, shown before its check came back.
     *
     * Shown rather than withheld, because the index is right nearly always and first paint
     * should not wait on the filesystem to say so. Not openable until the check passes - see
     * [actionable] - so a row that turns out to be gone can be seen for a moment and never used.
     */
    CONFIRMING("Confirming"),
}

/**
 * What a row becomes once its check comes back: available if the file is there, or null to
 * remove it. Removed, not relabelled - see [HitState].
 */
fun afterCheck(exists: Boolean): HitState? = if (exists) HitState.AVAILABLE else null

/** Whether a row may be opened, shared or acted on without checking it first. */
fun actionable(state: HitState): Boolean = state == HitState.AVAILABLE

/**
 * Whether a row carries a state badge.
 *
 * Only while its check is outstanding. This used to be "while an index pass is running", which
 * labelled every row Available or Confirming for as long as a crawl ran - so the badge described
 * the crawl, and with an unbounded crawl on every open, rows that had passed their check still
 * read Confirming minutes later. A row whose check passed has nothing left to say; a badge on
 * every row saying so is the noise the label was always meant to avoid.
 */
fun showBadge(state: HitState): Boolean = state == HitState.CONFIRMING
