package dev.niccc2007.filet.browser

/**
 * Everything Filet is doing that a full stop would end, in words.
 *
 * ## Why this exists
 *
 * Filet did not stop. Swiping it out of the task switcher leaves the music service, a share
 * being hosted, a crawl, the maintenance sweep and any copy in flight all running, because each
 * of them is a foreground service or scheduled work that outlives the activity by design. The
 * only thing that reliably ended all of it was reinstalling the app. A stop control is the
 * answer, and it is only honest if it says first what it is about to end - so the list is the
 * feature, and the button is the easy half.
 *
 * Pure, so what the sheet claims is running is a test rather than a hope, and so the same list
 * can drive a badge on the row as well as the sheet behind it.
 */
object RunningWork {

    /** One thing that would end. */
    data class Item(
        val label: String,
        /**
         * Work that would be cut off partway and leave something half-done behind.
         *
         * Named apart from everything else because it is the one kind of item where stopping
         * has a cost: a partial file at a copy's destination, half an archive, a delete that
         * took some of the selection and not the rest. Everything else on the list just ends.
         */
        val interrupts: Boolean = false,
    )

    /**
     * @param playing the title of what is playing, or null when nothing is.
     * @param crawling whether an index pass is running.
     * @param jobs the titles of jobs still running, with whether each would leave work half-done.
     * @param hosting how many shares are being served to other devices.
     * @param nearby whether the nearby-sharing server is up.
     */
    fun of(
        playing: String?,
        crawling: Boolean,
        jobs: List<Pair<String, Boolean>>,
        hosting: Int,
        nearby: Boolean,
    ): List<Item> = buildList {
        // Interrupted work first. It is the only kind with a cost, and a list that buries the
        // cost under the music has put the least important line where the eye lands.
        for ((title, partial) in jobs) if (partial) add(Item(title, interrupts = true))
        if (playing != null) add(Item("Playing $playing"))
        if (crawling) add(Item(INDEX_JOB))
        for ((title, partial) in jobs) {
            // The index pass already has its own line; listing its ledger entry as well would
            // count one thing twice.
            if (!partial && !(crawling && title == INDEX_JOB)) add(Item(title))
        }
        if (hosting > 0) add(Item(if (hosting == 1) "Sharing 1 folder" else "Sharing $hosting folders"))
        if (nearby) add(Item("Nearby sharing"))
    }

    /**
     * Whether stopping this job partway would leave something half-done.
     *
     * Decided by elimination rather than by listing the dangerous ones: a job is assumed to be
     * writing something unless it is known to only read. Copying and moving are the obvious
     * cases, but compressing leaves half an archive, extracting half a tree, deleting part of a
     * selection, signing an unsigned package - and a job published over the bridge by another
     * app carries no type at all. An unknown job named as safe to cut off is the one mistake
     * this sheet must not make, so unknown means it is named.
     *
     * Read from the title because that is the only thing every job carries. The read-only words
     * are the ones the index, the folder measure and the remote cache write, and
     * `RunningWorkTest` pins them against the operations layer's own titles.
     */
    fun leavesPartial(title: String): Boolean {
        val t = title.lowercase()
        return READ_ONLY.none { t.startsWith(it) }
    }

    /** What the confirm button says, which changes when it would cut work off. */
    fun confirmLabel(items: List<Item>): String =
        if (items.any { it.interrupts }) "Stop anyway" else "Stop Filet"

    /** The ledger title of an index pass. */
    const val INDEX_JOB = "Indexing storage"

    /**
     * Jobs that only read. "Fetching" is a remote file being cached for opening, and a cache
     * entry cut off partway is discarded rather than used, so it leaves nothing behind.
     */
    private val READ_ONLY = listOf("indexing", "measuring", "fetching")
}
