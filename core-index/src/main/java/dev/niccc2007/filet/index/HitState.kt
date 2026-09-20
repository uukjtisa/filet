package dev.niccc2007.filet.index

/**
 * How much a search result is worth trusting right now.
 *
 * Two states, and deliberately only two. A third one for "this might be gone" was considered
 * and rejected: an entry the index can no longer justify is dropped from the list rather than
 * labelled, because a row that announces its own unreliability is asking the reader to do the
 * checking that the app exists to do.
 *
 * So the rule is: if it is on screen, it is either known to be there or being checked.
 */
enum class HitState(val label: String) {
    /**
     * Known to be there.
     *
     * Either no pass is running - in which case the last complete pass is the authority - or
     * one is running and has already reached this entry.
     */
    AVAILABLE("Available"),

    /**
     * A pass is running and has not reached this entry yet.
     *
     * The row is served anyway, because an entry written on an earlier pass is a fact until
     * something disproves it and withholding it is choosing to know less than is known. The
     * label is there so the difference is visible rather than silently assumed.
     */
    CONFIRMING("Confirming"),
}

/**
 * Which state a row is in.
 *
 * @param crawlRunning whether an index pass is in flight.
 * @param rowGen the generation stamped on this row.
 * @param currentGen the generation the running pass is writing, or the last complete one.
 *
 * A generation is bumped once per pass and stamped on every row the pass re-reads, so
 * `rowGen == currentGen` is exactly "this pass has been here". Between passes every surviving
 * row carries the last complete generation, which is why nothing is ever left confirming
 * when nothing is running.
 */
fun hitState(crawlRunning: Boolean, rowGen: Long, currentGen: Long): HitState =
    if (crawlRunning && rowGen < currentGen) HitState.CONFIRMING else HitState.AVAILABLE

/**
 * Whether a result may be shown at all.
 *
 * The third state, refused. A row the index holds whose file is no longer on disk is not a
 * degraded result, it is a wrong one - so it is dropped here rather than labelled and left for
 * the reader to discover by tapping it.
 *
 * Cheap because it only ever runs on the handful of rows that survived ranking: the index is
 * narrowing thousands of rows to a page, and a stat on a page is nothing.
 */
fun showable(existsOnDisk: Boolean): Boolean = existsOnDisk

/**
 * Whether the state is worth drawing at all.
 *
 * Reported as there being no indicator on screen. Two things produced that, and only one was
 * the obvious one.
 *
 * The first: the badge was drawn only for [HitState.CONFIRMING], on the argument that a label
 * on every row saying Available is noise. That argument is right between passes and wrong
 * during one - while a pass is running, "this one has been checked" and "this one has not yet"
 * are both facts worth having, and showing only half of the pair makes the other half
 * indistinguishable from a feature that is not there.
 *
 * The second is the one that actually made the screen blank, and it is a case the first design
 * did not have: on a FIRST index there is nothing to confirm. The database was empty when the
 * pass began, so every row it holds was written by that pass and is Available by definition.
 * Nothing could ever be labelled, which reads as nothing working.
 *
 * So: while a pass is running, every row says which it is. Between passes, nothing does -
 * everything is available then and a badge on every row would be the noise the first argument
 * was worried about.
 */
fun showBadge(crawlRunning: Boolean): Boolean = crawlRunning
