package dev.niccc2007.filet.home

/**
 * The two home feed headings, in one place so they cannot drift apart again.
 *
 * Bug identified: the home screen showed "New files" directly above "Recent", and neither name
 * said what put an entry in it. They are different questions - one list is files that TURNED
 * UP on the phone, the other is files YOU OPENED - and read together, the second looked like a
 * shorter way of saying the first.
 *
 * Both headings now name their own cause. Not a subtitle: a line of explanation under a
 * section header is not read, and if it has to be read the header has already failed.
 */
object HomeSections {

    /**
     * Files that appeared on the phone's own storage. The cause is the file arriving, not the
     * user. Device-wide since the tracked-folder list was retired - see `HomeFeed` - so the
     * heading no longer has to apologise for a narrow reach.
     */
    const val ARRIVED = "Added to this phone"

    /**
     * The same list, named for a tab strip rather than for a section heading.
     *
     * [ARRIVED] has to carry its own cause because it sits directly above "Recently opened"
     * with nothing else to tell them apart. A tab has its own icon, its own position and no
     * neighbour making the same claim, so the short name says enough there.
     */
    const val ARRIVED_TAB = "New files"

    /** Files the user opened. The cause is the user, not the file. */
    const val OPENED = "Recently opened"

    /** Both, for anything that needs to render or check the pair. */
    val ALL = listOf(ARRIVED, OPENED)
}

/**
 * The ids of the Home sections that can be folded away.
 *
 * Ids, not labels. A section's heading is copy and has been rewritten several times - see
 * the note on ARRIVED - and a fold keyed on the words would forget itself every time somebody
 * improved them.
 */
object HomeFolds {
    const val ARRIVED = "arrived"
    const val OPENED = "opened"

    /** The whole-phone categories. Issue #4, and see `MediaCategory`. */
    const val CATEGORIES = "categories"

    /**
     * Folded until somebody opens them. Empty: the one section that started shut was the
     * tracked-folder list, which no longer exists. A stored fold id for it is simply never
     * matched again.
     */
    val SHUT: Set<String> = emptySet()
}
