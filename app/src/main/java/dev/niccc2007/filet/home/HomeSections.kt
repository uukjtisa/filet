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

    /** Files that appeared in a tracked folder. The cause is the folder, not the user. */
    const val ARRIVED = "New in your folders"

    /** Files the user opened. The cause is the user, not the folder. */
    const val OPENED = "Recently opened"

    /** Both, for anything that needs to render or check the pair. */
    val ALL = listOf(ARRIVED, OPENED)
}
