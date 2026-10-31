package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VNode

/**
 * Search results split by drive, once a share is in reach. SEARCH.md §13.5.
 *
 * ## Why grouping is conditional
 *
 * A flat ranked list mixes a file on internal storage with one four seconds away over Wi-Fi,
 * and nothing on the row says which. The cost of a hit is a property of its drive, so the drive
 * is the grouping - but only where costs actually differ. Internal, a card, a stick and the
 * root mounts open at the same speed and never fail differently, so splitting them into short
 * lists would cost the ranking and buy nothing; the location line under each name already says
 * which card a file is on.
 *
 * So the headers appear exactly when a share is in reach, which makes their presence the signal:
 * seeing them means part of this answer is slower and can fail.
 */
object ResultGroups {

    /** What a header says in place of a count, when a count is not the honest answer. */
    enum class State {
        /** Answered, with [Group.nodes] as the answer. */
        FOUND,

        /** Still being asked, and nothing back yet. */
        SEARCHING,

        /** Could not be reached at all. Never rendered as "no matches" - see [of]. */
        UNREACHABLE,

        /** Asked, answered, and the answer was nothing. */
        NONE,
    }

    data class Group(
        /** A stable key for the list: the drive's uuid, or [PHONE] for the merged local group. */
        val key: String,
        val label: String,
        val remote: Boolean,
        val nodes: List<VNode>,
        val state: State,
        /** Folders this drive's walker has read so far, or 0 when nothing is walking it. */
        val walked: Int = 0,
    )

    const val PHONE = "phone"

    /**
     * The groups to draw, or null when the results should stay one flat list.
     *
     * @param hits the results in their ranked order. Kept in that order inside each group: the
     *   ranking is still right within a drive, it just should not be mixed across them.
     * @param running whether a search is still producing results.
     * @param unreachable shares that failed to answer for this search.
     */
    fun of(
        hits: List<VNode>,
        drives: List<ReachDrive>,
        reach: Reach,
        running: Boolean,
        unreachable: Set<VolumeKey>,
        walked: Map<VolumeKey, Int> = emptyMap(),
    ): List<Group>? {
        val shares = sharesIn(reach, drives)
        if (shares.isEmpty()) return null

        val byShare = LinkedHashMap<VolumeKey, MutableList<VNode>>()
        for (s in shares) byShare[s.key] = ArrayList()
        val local = ArrayList<VNode>()
        for (node in hits) {
            val drive = ReachLadder.driveAt(node.path, drives)
            val bucket = drive?.takeIf { it.remote }?.let { byShare[it.key] }
            if (bucket != null) bucket += node else local += node
        }

        return buildList {
            // A reach of shares alone - the sheet with one NAS ticked - has no phone group at
            // all. A header announcing that the phone found nothing would be answering a
            // question nobody asked.
            if (reachesPhone(reach, drives)) {
                add(
                    Group(
                        key = PHONE,
                        label = phoneLabel(drives),
                        remote = false,
                        nodes = local,
                        state = stateOf(local.size, running, unreachable = false),
                    ),
                )
            }
            for (s in shares) {
                val nodes = byShare[s.key].orEmpty()
                add(
                    Group(
                        key = s.key.uuid,
                        label = s.label,
                        remote = true,
                        nodes = nodes,
                        state = stateOf(nodes.size, running, unreachable = s.key in unreachable),
                        walked = walked[s.key] ?: 0,
                    ),
                )
            }
        }
    }

    /**
     * The words a header shows after its label.
     *
     * Plain, and the same four everywhere, so a reader learns them once: a bare number, or one
     * of three short states. "Unreachable" is deliberately not softened into "offline" - offline
     * is a guess about why, and the share may be asleep, on another network, or gone.
     */
    fun caption(group: Group): String = when (group.state) {
        State.FOUND -> "%,d".format(group.nodes.size)
        // A count once there is one: "searching…" for a minute reads as stuck, and a number
        // that keeps climbing is the difference between slow and broken.
        State.SEARCHING -> if (group.walked > 0) "reading, ${"%,d".format(group.walked)} folders" else "searching…"
        State.UNREACHABLE -> "unreachable"
        State.NONE -> "no matches"
    }

    /** The shares a reach covers, in the order the drives are listed. */
    fun sharesIn(reach: Reach, drives: List<ReachDrive>): List<ReachDrive> = when (reach) {
        Reach.Everywhere -> drives.filter { it.remote }
        is Reach.Drives -> drives.filter { it.remote && it.key in reach.keys }
        // Tree-shaped reaches sit on one drive. Grouping a search that can only ever answer
        // from one place would draw a single header over the whole list - noise.
        Reach.Folder, Reach.Subtree -> emptyList()
    }

    private fun reachesPhone(reach: Reach, drives: List<ReachDrive>): Boolean = when (reach) {
        Reach.Everywhere -> drives.any { !it.remote }
        is Reach.Drives -> drives.any { !it.remote && it.key in reach.keys }
        Reach.Folder, Reach.Subtree -> false
    }

    /**
     * One local drive is named; several are "This phone".
     *
     * The merged group should read as the same set the ladder's rung 4 names, so the word is the
     * same word - and on a phone with only internal storage, the drive's own label is the more
     * exact of the two.
     */
    private fun phoneLabel(drives: List<ReachDrive>): String {
        val local = drives.filter { !it.remote && !it.system }
        return local.singleOrNull()?.label ?: "This phone"
    }

    private fun stateOf(found: Int, running: Boolean, unreachable: Boolean): State = when {
        // Only with nothing to show. An opted-in share can have answered from the index while
        // the live walk failed, and those rows are real - drawn as still being confirmed, which
        // is the row saying what the header would. With no rows at all, "no matches" would be a
        // lie about a drive that was never actually asked.
        unreachable && found == 0 -> State.UNREACHABLE
        found > 0 -> State.FOUND
        running -> State.SEARCHING
        else -> State.NONE
    }
}
