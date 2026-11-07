package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.MountBoundary
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * One row of a tree listing: a node, and how deep it sits.
 *
 * @param loading true for a folder that has been opened and whose children have not arrived.
 */
/**
 * @param spines for each ancestor level, whether that ancestor has more siblings BELOW this
 *   row. It is what decides whether a guide line continues past the row or stops at it, and
 *   without it the lines run on under the last child of every folder - which is what made
 *   them look broken rather than like a tree.
 * @param last whether this row is the last of its own siblings.
 */
data class TreeRow(
    val node: VNode,
    val depth: Int,
    val loading: Boolean = false,
    val spines: List<Boolean> = emptyList(),
    val last: Boolean = false,
    /**
     * True for the note under a branch that turned out to hold nothing.
     *
     * Carries its PARENT's node, same as a [loading] row, so the guide geometry and the
     * working-folder frame both already know where it belongs.
     */
    val empty: Boolean = false,
)

/**
 * The folder list, with opened folders showing their contents in place.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is not a second browser. Asked for after using X-plore, and the useful finding from that
 * is that X-plore's tree is not a different screen - it is the same list with a different ROW
 * RENDERER. Same thumbnails, same path bar at the top, same everything; a folder row simply
 * opens underneath itself instead of replacing the screen. That is why it coexists with a
 * path bar at all, and it is why this can be a view mode rather than a redesign.
 *
 * So: the pane still has a current folder, the path bar still means what it meant, tapping a
 * folder's row still navigates into it, and every row is still a `FileRow` - which is what
 * keeps drag-and-drop, selection, thumbnails and the long-press menu working without any of
 * them knowing a tree exists.
 *
 * ## Why flattening is a pure function
 *
 * A tree in a `LazyColumn` has to be a flat list - that is what a lazy list is - so the whole
 * question is which rows, in what order, at what depth. Done inline inside a composable, that
 * arithmetic is untestable and wrong in ways that only show up with a folder open three deep
 * while another is loading. Here it is a fold over two maps and can be stated in a test.
 *
 * A branch that is open but not yet read emits a single [TreeRow] with `loading`, so the row
 * acknowledges the tap immediately. The alternative - nothing until the listing arrives - is
 * indistinguishable from the chevron not working on a slow card.
 */
object TreeRows {

    /**
     * @param roots the pane's own listing, already sorted and filtered.
     * @param open which folders are showing their contents.
     * @param branches children per opened folder, absent while it is still being read.
     * @param depth how deep the tree may go, as a guard rather than as a feature.
     */
    fun flatten(
        roots: List<VNode>,
        open: Set<VPath>,
        branches: Map<VPath, List<VNode>>,
        depth: Int = MAX_DEPTH,
    ): List<TreeRow> {
        val out = ArrayList<TreeRow>(roots.size + open.size * 8)
        val spines = ArrayList<Boolean>()
        // Asked once: only with an archive open can a file row be a branch, and asking every
        // file of a big folder what it is would be work for no answer.
        val mounts = open.any { MountBoundary.isMounted(it) }
        fun walk(nodes: List<VNode>, at: Int) {
            for ((i, node) in nodes.withIndex()) {
                val last = i == nodes.lastIndex
                out.add(TreeRow(node, at, spines = shared(spines), last = last))
                // A folder opens into itself, an archive into its mount. See TreeBranch.
                val key = if (node.isDir) node.path else if (mounts) TreeBranch.keyOf(node) else null
                if (key == null || key !in open) continue
                val kids = branches[key]
                // A level's spine continues only while its folder has siblings left below.
                spines.add(!last)
                when {
                    // Opened, nothing read back yet.
                    kids == null -> out.add(
                        TreeRow(node, at + 1, loading = true, spines = shared(spines), last = true),
                    )
                    // Opened and genuinely empty, which now says so.
                    //
                    // It used to emit nothing, on the reasoning that a row under every empty
                    // folder is noise and the chevron turning is acknowledgement enough. Two
                    // things changed that. A folder known to be empty no longer HAS a chevron,
                    // so the only way to reach this state is a folder whose count was stale or
                    // whose read failed - which is exactly when somebody needs telling. And
                    // "the branch opened and nothing happened" is indistinguishable from the
                    // chevron not working, which is the reading this gets reported as.
                    kids.isEmpty() -> out.add(
                        TreeRow(node, at + 1, empty = true, spines = shared(spines), last = true),
                    )
                    // The depth guard, which now also says so rather than stopping silently.
                    // A tree that simply stops twelve deep looks broken at exactly the moment
                    // somebody is lost enough to be twelve deep.
                    at + 1 >= depth -> out.add(
                        TreeRow(node, at + 1, empty = true, spines = shared(spines), last = true),
                    )
                    else -> walk(kids, at + 1)
                }
                spines.removeAt(spines.lastIndex)
            }
        }
        walk(roots, 0)
        return out
    }

    /**
     * One list per distinct spine pattern, the same instance every time it is asked for.
     *
     * Bug identified here: every flatten built a fresh list for every row, and a list is
     * compared by IDENTITY when Compose decides whether a row can skip - so every row on screen
     * looked changed on every flatten, and a branch streaming in, which flattens once per
     * published chunk, recomposed the whole visible tree each time. The patterns are few - one
     * per depth and shape actually drawn - so sharing them costs a small map.
     */
    private fun shared(spines: List<Boolean>): List<Boolean> {
        if (spines.isEmpty()) return emptyList()
        if (spines.size > 31) return spines.toList()
        var bits = 0L
        for ((i, b) in spines.withIndex()) if (b) bits = bits or (1L shl i)
        val key = (spines.size.toLong() shl 32) or bits
        return spineLists.getOrPut(key) { spines.toList() }
    }

    private val spineLists = java.util.concurrent.ConcurrentHashMap<Long, List<Boolean>>()

    /**
     * Opening or closing one folder.
     *
     * Closing drops everything under it as well, so a branch reopened later is read fresh
     * rather than redrawn from a listing that may be hours old - and so a thousand-row tree
     * does not stay in memory after it has been folded away.
     */
    fun toggle(open: Set<VPath>, path: VPath): Set<VPath> =
        // TreeBranch.under, so closing a folder also closes an archive open inside it.
        if (path in open) open.filterNot { TreeBranch.under(path, it) }.toSet()
        else open + path

    /**
     * Which fold a back press should close, or null when there is none to close.
     *
     * ## Why the deepest, and why not the last one opened
     *
     * In a flat list, back goes up a level. In a tree the equivalent is closing a branch - and
     * issue #12 asks for exactly that, because a tree four deep otherwise needs four taps on
     * four chevrons to put away, and the back gesture, which is the one thing a thumb reaches
     * for without looking, instead leaves the folder entirely.
     *
     * The DEEPEST open fold rather than the most recently opened one. Those are usually the
     * same, and where they differ the deepest is the one that matches what back means
     * everywhere else: it undoes the innermost thing first. Closing the last-opened would mean
     * a back press could collapse a branch near the top of the screen while a deeper one stayed
     * open below it, which reads as the press having hit the wrong row.
     *
     * Ties - two folds at the same depth - go to the one whose path sorts last, which is
     * arbitrary but stable. An arbitrary-but-stable answer is fine here; an unstable one would
     * mean two identical back presses doing different things.
     */
    fun deepestFold(open: Set<VPath>): VPath? =
        open.maxWithOrNull(compareBy({ it.path.count { c -> c == '/' } }, { it.path }))

    /**
     * The folds after Back leaves [left] for [landing], with the setting that folds the folder
     * left behind (#12): [left] closed, and everything under it, unless it is one of [keep] - the
     * folders down to where Back lands, which have to stay open for that to be seen.
     */
    fun foldLeft(open: Set<VPath>, left: VPath?, landing: VPath?, keep: Set<VPath>): Set<VPath> {
        if (left == null || left == landing || left in keep || left !in open) return open
        return open.filterNot { TreeBranch.under(left, it) }.toSet()
    }

    /**
     * Every folder between [root] and [target], so a jump can land with the tree already open
     * down to where it went.
     *
     * ## Why a shortcut needs this
     *
     * Opening a shortcut to Downloads used to make Downloads the tree's own root - the pane
     * navigated there, and the tree draws whatever the pane is showing. So the tree arrived
     * with no trunk: no internal storage above it, no siblings beside it, and no way to see
     * where in the device you had landed. Which is the one thing a tree is for.
     *
     * The fix is to navigate to the VOLUME and open the path down to the target instead. This
     * is the part worth testing: the ancestors are derived by walking parents up from the
     * target and stopping at the root, which gets the boundary cases - target IS the root, or
     * target is not under it at all - decided once rather than at three call sites.
     *
     * [target] itself is included, so the folder somebody asked for is open and showing its
     * contents rather than merely visible.
     */
    fun pathTo(root: VPath, target: VPath): Set<VPath> {
        if (root == target) return setOf(target)
        if (!TreeBranch.under(root, target)) return setOf(target)
        val out = LinkedHashSet<VPath>()
        var at: VPath? = target
        // Guarded against a path that is somehow not under the root after all: without the
        // depth bound a parent chain that never reaches it is an infinite loop on the main
        // thread.
        var guard = 0
        while (at != null && guard++ < MAX_DEPTH * 2) {
            out.add(at)
            if (at == root) break
            // MountBoundary, so the way down to a file inside a zip passes through the zip.
            at = MountBoundary.up(at)
        }
        return out
    }

    /**
     * The open folds that actually draw under [root], shallowest first.
     *
     * ## Why `open` is not already this
     *
     * Folds outlive navigation on purpose: walk out of a folder and back in, and the branches
     * you had open are still open. So `open` routinely holds folds belonging to folders the
     * pane is not showing, and folds under a parent that has since been closed by something
     * other than [toggle]. A fold draws only if its parent is [root] itself or a fold that
     * draws - which is the rule [flatten] applies implicitly, written down once here so the
     * three things that need it agree with the renderer:
     *
     *  - which branches to read again after a refresh or a re-list, since reading folds that
     *    cannot be seen costs a directory read each for nothing;
     *  - which branches to read after a tab comes back from disk, which arrives with folds and
     *    no listings for them;
     *  - which folds are worth saving at all.
     *
     * Shallowest first, so any prefix of the answer is itself a tree that draws - a capped save
     * never keeps a child whose parent was cut.
     */
    fun visibleFolds(root: VPath, open: Set<VPath>): List<VPath> {
        val out = LinkedHashSet<VPath>()
        val byDepth = open
            .filter { it != root && TreeBranch.under(root, it) }
            .sortedWith(compareBy({ it.segments.size }, { it.path }))
        for (fold in byDepth) {
            val parent = MountBoundary.up(fold) ?: continue
            if (parent == root || parent in out) out.add(fold)
        }
        return out.toList()
    }

    /**
     * The folds a tab writes to disk, so it comes back from a restart as it was left.
     *
     * Bug identified here: the tab record held a kind and a path and nothing else, so a tab
     * restored after the process was killed came back as a flat folder with every fold shut -
     * which is the one state a tree used across several tabs most needs to survive, since the
     * process is killed behind the user's back rather than closed by them.
     *
     * Only the folds that draw, and at most [cap] of them, shallowest first. Writing every fold
     * ever opened in the tab would grow the record without bound and restore reads for
     * branches nobody can see.
     */
    fun foldsToSave(root: VPath?, open: Set<VPath>, cap: Int = MAX_SAVED_FOLDS): List<VPath> =
        if (root == null) emptyList() else visibleFolds(root, open).take(cap)

    /**
     * Whether every fold that draws under [root] has had something read back for it.
     *
     * A reveal in a tree cannot use "the listing has finished" as its signal for giving up: the
     * target lives inside a branch, and the pane's own listing finishes long before the branch
     * that holds the target has been read. Giving up at that moment is how a jump to a folder
     * deep in a volume opened the tree down to it and then left it scrolled out of sight.
     */
    fun foldsRead(root: VPath, open: Set<VPath>, branches: Map<VPath, List<VNode>>): Boolean =
        visibleFolds(root, open).all { it in branches }

    /**
     * Where [target] sits in a flattened tree, or -1.
     *
     * A placeholder row carries its parent's node, so a plain search for the path lands on the
     * "Reading…" or "Nothing here" row under the folder rather than on the folder's own row
     * whenever the placeholder happens to be found first. It never is today, because the folder
     * row always comes before its placeholder - but the reveal scrolls to whatever this returns,
     * and a rule that is right only because of the order rows are emitted in is one reorder away
     * from scrolling to the wrong row.
     */
    fun revealIndex(rows: List<TreeRow>, target: VPath): Int =
        rows.indexOfFirst { it.isRowOf(target) && !it.loading && !it.empty }

    /** Whether this row stands for [branch]: its own path, or the mount an archive opens into. */
    fun TreeRow.isRowOf(branch: VPath): Boolean =
        node.path == branch || !node.isDir && TreeBranch.keyOf(node) == branch

    /** How many folds a tab keeps across a restart. See [foldsToSave]. */
    const val MAX_SAVED_FOLDS = 64

    /**
     * How deep the tree will go: a guard against a branch that contains itself, and nothing else.
     *
     * Bug identified here: this was twelve, on the theory that nobody navigates further on
     * purpose. People do. A chain of nested folders opened one level at a time reached the
     * guard, and the guard's note reads "Nothing in here", under a folder full of files. That
     * was reported as the tree giving up once the structure went off the screen. The same
     * number also bounded [pathTo], so a folder more than twenty-four levels below the root
     * could not be arrived at with its trunk drawn.
     *
     * The rows were never what twelve protected. [flatten] only descends into folders in
     * `open`, and every one of those was opened by somebody, or is an ancestor of where they
     * arrived. So the row count is already bounded by what was asked for. The one
     * thing that can run away is a listing that names a folder inside itself, which the guard
     * still stops, now at a depth no real tree reaches. Width is [TreeScale]'s job.
     */
    const val MAX_DEPTH = 256
}
