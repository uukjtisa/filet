package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * What a pane asks of a live pick, so that while one is live there is ONE selection: the pick's.
 *
 * Bug identified here: the pick took a single file at each of the pane's selection doors (see
 * [PickRoute]), but every gesture still DECIDED against the pane's own selection, which a pick
 * leaves empty. So a hold always meant "start a selection", never "extend it": tapping one file
 * and holding another further down took the second file alone, where the browser selects the
 * run between them. The same gesture did two different things depending on whether a pick was
 * live.
 *
 * So the pane reads its selection through [PaneController.selectionNow], which is the pick's list
 * while one is live, and every range, slide, Select all and Invert runs the browser's own
 * arithmetic against it and hands the difference to [run]. The anchor a range extends from is
 * the pick's as well, because a tap that puts a file into the pick arrives through the view
 * model's tap funnel and never touches a pane.
 */
interface PickPort {
    /** Whether a pick is live anywhere in the app. Asked at the moment a row is selected. */
    fun live(): Boolean

    /** What the pick holds, by path. */
    fun chosen(): Set<VPath>

    /** The last file put into the pick or out of it deliberately, which a range extends from. */
    fun anchor(): VPath?

    /** One file, as a tap on it: into the pick, or out of it if it was in. */
    fun take(node: VNode)

    /**
     * A run of rows: [add] taken where the pick accepts them, [remove] dropped, and [target], the
     * row the gesture ended on, becomes the anchor.
     */
    fun run(add: List<VNode>, remove: Set<VPath>, target: VNode?)

    /** No pick, ever: a pane outside the browser, or a test. */
    object None : PickPort {
        override fun live() = false
        override fun chosen() = emptySet<VPath>()
        override fun anchor(): VPath? = null
        override fun take(node: VNode) = Unit
        override fun run(add: List<VNode>, remove: Set<VPath>, target: VNode?) = Unit
    }
}

/** The arithmetic between a selection gesture and the pick's list. Pure, so it is a test. */
internal object PickRun {

    /**
     * What going from [now] to [next] adds and drops, over the [rows] on screen only.
     *
     * Only those rows: the pick holds files chosen in other folders and other tabs, and a gesture
     * here never saw them. Dropping everything outside [next] would wipe them on the first range.
     */
    fun diff(rows: List<VNode>, now: Set<VPath>, next: Set<VPath>): Pair<List<VNode>, Set<VPath>> {
        val add = rows.filter { it.path in next && it.path !in now }
        val remove = rows.asSequence().map { it.path }.filter { it in now && it !in next }.toSet()
        return add to remove
    }

    /**
     * The pick's list after a run: [remove] dropped, then [add] appended in the order drawn,
     * skipping anything already chosen and anything [accepts] refuses.
     *
     * A refused file in a range is skipped without a word. A run over a folder of mixed files
     * picking up only the pictures a picture pick wants is the range doing its job, and a notice
     * for every file left out would bury the one that matters.
     */
    fun after(
        chosen: List<VNode>,
        add: List<VNode>,
        remove: Set<VPath>,
        accepts: (VNode) -> Boolean,
    ): List<VNode> {
        val kept = chosen.filterNot { it.path in remove }
        val have = kept.mapTo(HashSet()) { it.path }
        val taken = add.filter { !it.isDir && accepts(it) && have.add(it.path) }
        return kept + taken
    }
}
