package dev.niccc2007.filet.index

/**
 * What one crawl pass is allowed to conclude. SEARCH.md §14.6 and §14.7.
 *
 * ## The defect this is the fix for
 *
 * A pass was handed a list of roots and treated every one of them as a whole volume: it
 * registered the root as a volume, walked it, and - when it finished - deleted every row in the
 * database older than its own generation. Two callers hand it a FOLDER rather than a volume: the
 * pass that runs when a folder is walked into, and the hot-folder watcher when a folder on screen
 * changes. Both finish in well under their budget. So walking into `Download` registered
 * `Download` as a volume of its own, indexed it, and then swept away every other row the index
 * held, on every drive, because none of them carried the folder pass's generation.
 *
 * The index then presented as fast, strict and inexplicably incomplete - files the bounded pass
 * had indexed an hour earlier were missing, and the next pass rebuilt them only to lose them
 * again the next time a folder was opened.
 *
 * So a root is classified before it is walked, and a pass may only delete what it walked:
 *
 * | Root | Walked as | Sweep | Marks the volume complete |
 * |---|---|---|---|
 * | a volume's own root | the volume | rows of that volume | yes |
 * | a folder inside a held volume | a subtree of it | rows under that folder | no |
 */
object CrawlScope {

    /**
     * The volume that holds [root] as a folder, or null when [root] is a volume in its own right.
     *
     * A root equal to a volume's root is that volume, not a folder in it. A root no volume owns is
     * a volume being seen for the first time.
     */
    fun subtreeOf(volumes: List<Pair<Long, String>>, root: String): Long? {
        val owner = VolumeOwner.of(volumes, root) ?: return null
        val ownerRoot = volumes.firstOrNull { it.first == owner }?.second ?: return null
        return if (ownerRoot == root) null else owner
    }

    /**
     * Local volumes the folder-pass defect registered, to be dropped.
     *
     * A local volume nested inside another one, and not a root this pass was given. The second
     * condition is what keeps a real mount inside another - which [VolumeOwner] deliberately
     * supports - from being mistaken for a stray: the volume pass is handed every local root, so a
     * real one is always in [passRoots]. Remote volumes are never this function's to judge; a
     * share has its own scheme and cannot be nested in a local drive.
     */
    fun strays(volumes: List<Pair<Long, String>>, passRoots: Set<String>): List<Long> =
        volumes.filter { (id, root) ->
            root.startsWith("local:") &&
                root !in passRoots &&
                volumes.any { (other, otherRoot) -> other != id && otherRoot != root && VolumeOwner.owns(otherRoot, root) }
        }.map { it.first }

    /**
     * The generation a volume was last completely walked at.
     *
     * Per volume, because a share's complete pass and the phone's are separate passes now and one
     * finishing says nothing about the other. Kept in `meta` under `complete:<uuid>`.
     *
     * A database written before this existed has no such key. Its one global generation still
     * says something: a volume whose root row carries the last complete generation was in the
     * pass that completed. Anything else is unknown, and unknown is 0 - nothing is skipped and
     * nothing is claimed to be covered.
     */
    fun completeGen(stored: String?, metaGen: Long, rootGen: Long?, lastRunAt: Long): Long {
        stored?.toLongOrNull()?.let { return it }
        return if (lastRunAt > 0 && metaGen > 0 && rootGen == metaGen) metaGen else 0L
    }

    /**
     * Which generation a pass over one share should write.
     *
     * The unfinished half of an earlier pass is continued under the generation it began with, so
     * every row the pass touches - before the interruption and after - carries one number, which
     * is what the sweep at the end needs to be correct. That holds until the volume completes a
     * pass of its own: a frontier older than that belongs to a walk that has already been swept.
     *
     * Not [CrawlFrontier.canResume]'s global test. Local passes complete every few launches and
     * advance the global generation, so a share's frontier keyed to it would almost never survive
     * long enough to be resumed - which on a share is the whole case for having one.
     */
    fun resumeGen(storedGen: Long?, storedRows: Int, volumeCompleteGen: Long, fresh: Long): Long =
        if (storedGen != null && storedRows > 0 && storedGen > volumeCompleteGen) storedGen else fresh

    /** What to do with a directory that could not be listed. */
    enum class Unreadable {
        /** Keep what the index already holds under it, and carry on. */
        CARRY,

        /** End the pass here, resumable, with the directory still on the frontier. */
        STOP,
    }

    /**
     * How many listings in a row may fail on a share before the share is taken to be gone.
     *
     * One is a folder this account cannot read. Three in a row, on a pass that was listing
     * happily a moment ago, is a dropped connection - and carrying on would carry every folder
     * still on the frontier forward unread and call the result complete.
     */
    const val SHARE_FAILURES_BEFORE_STOP = 3

    /**
     * A local folder that cannot be listed is a permission (`Android/data`) and will never be
     * readable; ending the pass on one would mean no pass ever completes. A share's root that
     * cannot be listed is a share that is not answering, and a pass that never reached it has not
     * walked it.
     */
    fun onUnreadable(remote: Boolean, isRoot: Boolean, consecutiveFailures: Int): Unreadable = when {
        !remote -> Unreadable.CARRY
        isRoot -> Unreadable.STOP
        consecutiveFailures >= SHARE_FAILURES_BEFORE_STOP -> Unreadable.STOP
        else -> Unreadable.CARRY
    }

    /**
     * The rows a completed pass may delete, as a SQL condition over `node`, or null for none.
     *
     * Ids are inlined rather than bound: they are longs read from this database, never text from
     * anywhere else, and the same fragment is used by three statements.
     */
    fun sweepSql(volumeIds: Collection<Long>, subtreeRootIds: Collection<Long>): String? {
        val parts = ArrayList<String>(2)
        if (volumeIds.isNotEmpty()) parts += "vol_id IN (" + volumeIds.distinct().joinToString(",") + ")"
        for (id in subtreeRootIds.distinct()) {
            parts += "id IN (WITH RECURSIVE sub(id) AS (SELECT $id UNION ALL " +
                "SELECT c.id FROM node c JOIN sub ON c.parent_id = sub.id) SELECT id FROM sub)"
        }
        return if (parts.isEmpty()) null else parts.joinToString(" OR ", "(", ")")
    }
}
