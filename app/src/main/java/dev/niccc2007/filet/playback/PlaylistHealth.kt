package dev.niccc2007.filet.playback

/**
 * What a playlist does about tracks whose files have gone.
 *
 * ## The defect this exists for
 *
 * A playlist stores paths. Playing one statted every path and dropped the ones that failed, so
 * the queue was SHORTER than the list on screen - while the row that was tapped handed its
 * index in the stored list straight to the queue. Every deleted track above the tap shifted the
 * result down by one, so tapping a row played the one after it, and the further down the list
 * the tap was the further off it got. Deleting the broken row appeared to "fix" everything under
 * it, which is the same fact seen from the other end.
 *
 * Two indices into two lists of different lengths were being treated as one. The repair is to
 * stop carrying an index across the filter at all: a tap identifies a track by its PATH, and the
 * position in the queue is looked up afterwards.
 *
 * It is here, pure and tested, rather than inline at the call site, because it is a decision
 * that can be wrong by one and nothing about a wrong answer looks like an error - it plays a
 * song, just not the one asked for.
 */
object PlaylistHealth {

    /**
     * Where playback starts in [surviving] when the entry [tapped] was asked for.
     *
     * [surviving] is the stored list with the unreachable entries removed, in the same order, so
     * their ids line up by value and never by position.
     *
     * When [tapped] itself is one of the gone ones - its row is still on screen until the list
     * is cleaned up - the nearest surviving track AFTER it plays, because that is the direction
     * a list is read in and the next song is what the tap was reaching for. Only when nothing
     * survives below it does this fall back to looking upwards, and to the start after that.
     *
     * @return an index into [surviving], or -1 when there is nothing to play.
     */
    fun startIndex(stored: List<String>, surviving: List<String>, tapped: String?): Int {
        if (surviving.isEmpty()) return -1
        if (tapped == null) return 0
        val direct = surviving.indexOf(tapped)
        if (direct >= 0) return direct
        val at = stored.indexOf(tapped)
        if (at < 0) return 0
        val present = surviving.toHashSet()
        for (i in at + 1 until stored.size) {
            if (stored[i] in present) return surviving.indexOf(stored[i])
        }
        for (i in at - 1 downTo 0) {
            if (stored[i] in present) return surviving.indexOf(stored[i])
        }
        return 0
    }

    /**
     * The same answer for a caller that only has a position - the playlist row menu's "play from
     * here", and the shortcut that starts a whole list.
     *
     * A position is still accepted because a caller outside the list screen has nothing better;
     * it is converted to a path HERE, against the stored list, so the conversion happens once
     * and in the place that knows both lists.
     */
    fun startIndexAt(stored: List<String>, surviving: List<String>, tappedAt: Int): Int =
        startIndex(stored, surviving, stored.getOrNull(tappedAt))

    /**
     * Whether a stat failure should count as the file being gone rather than out of reach.
     *
     * A local path that does not stat is a deleted file. A path on a share that does not stat
     * may be a deleted file or may be a server that is off, and the two are indistinguishable
     * from here - so a remote entry is marked unreachable and never offered for removal. A
     * playlist is data somebody built by hand, and pruning it because a NAS was asleep would
     * destroy it in a way nothing undoes.
     *
     * Decided on the SCHEME rather than on whether the path reads like a file path, because
     * every provider's paths read like file paths - `peer:///Music/x.mp3` and
     * `local:///storage/emulated/0/Music/x.mp3` differ in exactly one place.
     */
    fun removable(id: String): Boolean = id.substringBefore(":///", "") in ON_DEVICE

    /**
     * The schemes whose files live on this phone.
     *
     * An archive member (`zip`, `apk`) is deliberately absent: the entry vanishing means the
     * archive could not be opened, and the archive is still there.
     */
    val ON_DEVICE = setOf("local", "root", "saf")
}
