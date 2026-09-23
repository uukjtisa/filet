package dev.niccc2007.filet.home

/**
 * The two home feed headings, in one place so they cannot drift apart again.
 *
 * Bug identified: the home screen showed "New files" directly above "Recent", and neither name
 * said what put an entry in it. They are different questions - one list is files that TURNED
 * UP in a folder Filet is watching, the other is files YOU OPENED - and read together, the
 * second looked like a shorter way of saying the first.
 *
 * Both headings now name their own cause. Not a subtitle: a line of explanation under a
 * section header is not read, and if it has to be read the header has already failed.
 */
object HomeSections {

    /**
     * Files that appeared in a tracked folder. The cause is the folder, not the user.
     *
     * "tracked" is in the name because it was never watching all of them - only the folders on
     * the tracked list - and a heading that overstates its reach makes an empty section look
     * like a fault rather than an accurate answer.
     */
    const val ARRIVED = "New in your tracked folders"

    /**
     * The same list, named for a tab strip rather than for a section heading.
     *
     * [ARRIVED] has to carry its own cause because it sits directly above "Recently opened"
     * with nothing else to tell them apart. A tab has its own icon, its own position and no
     * neighbour making the same claim, and "New in your tracked folders" truncates to
     * "New in your tr…" in the space a tab actually gets - which says less than the short
     * name does. So the two differ on purpose, and neither is a rename of the other.
     */
    const val ARRIVED_TAB = "New files"

    /** Files the user opened. The cause is the user, not the folder. */
    const val OPENED = "Recently opened"

    /** Both, for anything that needs to render or check the pair. */
    val ALL = listOf(ARRIVED, OPENED)
}
