package dev.niccc2007.filet.browser

import dev.niccc2007.filet.ui.Labels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a tap on an item does, for every view that draws items: one declaration that the
 * settings, the folder list, the grid, the tree and the category gallery all read.
 *
 * Before this, each view decided in its own handler: a list row opened on one tap and opened
 * the menu on two, a tree row stood in its folder, a gallery tile had no second tap at all.
 * Making those choices configurable one handler at a time would have meant five copies of one
 * table, drifting apart. They are one table now: a [TapSet] says what each count of taps does
 * to a file and to a folder, a view looks its set up, and the handler only counts.
 *
 * While a selection is running none of this applies: a tap toggles and two taps open the menu
 * on the selection, in every view, so no set can make choosing things harder.
 */
enum class TapView(val label: String) { LISTS("Lists"), GALLERY("Gallery"), TREE("Tree view") }

enum class Tap(val label: String) { ONE("One tap"), TWO("Two taps"), THREE("Three taps"), HOLD("Hold") }

/** Everything a tap can be set to. Which of them a given row offers is [TapPlan.choices]. */
enum class TapDo(val label: String) {
    /** A file opens; a folder is gone into - in the tree, unfolded and stood in. */
    OPEN("Open"),

    /** Tree folders: stand in it without unfolding; the same tap again folds or unfolds it. */
    FOCUS("Focus"),
    SELECT("Select"),

    /** Tree folders: fold or unfold in place, leaving the focus where it is. */
    EXPAND("Expand or collapse"),
    MENU("Menu"),
    PEEK("Peek"),
    OPEN_LOCATION(Labels.OPEN_LOCATION),
    OPEN_WITH("Open with…"),
    DETAILS("Details"),
    NOTHING("Nothing"),
}

data class TapSet(val file: Map<Tap, TapDo>, val folder: Map<Tap, TapDo>) {
    fun of(isDir: Boolean, tap: Tap): TapDo = (if (isDir) folder else file)[tap] ?: TapDo.NOTHING

    fun with(isDir: Boolean, tap: Tap, act: TapDo): TapSet =
        if (isDir) copy(folder = folder + (tap to act)) else copy(file = file + (tap to act))

    /**
     * How many taps a row waits for before acting.
     *
     * The cost of a second tap meaning something is that the first has to wait to see whether a
     * second is coming. So a count set to Nothing is not listened for, and with Two and Three
     * both Nothing a single tap acts the moment the finger lifts.
     */
    fun listensFor(isDir: Boolean): Int = when {
        of(isDir, Tap.THREE) != TapDo.NOTHING -> 3
        of(isDir, Tap.TWO) != TapDo.NOTHING -> 2
        else -> 1
    }
}

enum class TapPreset(val label: String) { FILET("Filet"), WINDOWS("Windows-like"), XPLORER("Xplorer"), CUSTOM("Custom") }

object TapPlan {
    private fun row(one: TapDo, two: TapDo, three: TapDo, hold: TapDo) =
        mapOf(Tap.ONE to one, Tap.TWO to two, Tap.THREE to three, Tap.HOLD to hold)

    private fun both(one: TapDo, two: TapDo, three: TapDo, hold: TapDo) =
        row(one, two, three, hold).let { TapSet(it, it) }

    /** The presets a view offers. Xplorer is a tree behaviour and means nothing elsewhere. */
    fun presets(view: TapView): List<TapPreset> =
        if (view == TapView.TREE) {
            listOf(TapPreset.FILET, TapPreset.WINDOWS, TapPreset.XPLORER)
        } else {
            listOf(TapPreset.FILET, TapPreset.WINDOWS)
        }

    /** A preset's set, or null for Custom and for a preset the view does not offer. */
    fun preset(view: TapView, p: TapPreset): TapSet? = when (p) {
        // What Filet did before any of this was configurable.
        TapPreset.FILET -> when (view) {
            TapView.LISTS -> both(TapDo.OPEN, TapDo.MENU, TapDo.NOTHING, TapDo.SELECT)
            TapView.GALLERY -> both(TapDo.OPEN, TapDo.NOTHING, TapDo.NOTHING, TapDo.PEEK)
            TapView.TREE -> TapSet(
                file = row(TapDo.OPEN, TapDo.MENU, TapDo.NOTHING, TapDo.SELECT),
                folder = row(TapDo.FOCUS, TapDo.MENU, TapDo.NOTHING, TapDo.SELECT),
            )
        }
        // A desktop's mouse: one click picks, two open, the other button is the menu.
        TapPreset.WINDOWS -> both(TapDo.SELECT, TapDo.OPEN, TapDo.NOTHING, TapDo.MENU)
        // One tap opens; going back up a branch folds what was open below it. See [foldOnTheWay].
        TapPreset.XPLORER ->
            if (view == TapView.TREE) both(TapDo.OPEN, TapDo.MENU, TapDo.NOTHING, TapDo.SELECT) else null
        TapPreset.CUSTOM -> null
    }

    fun default(view: TapView): TapSet = preset(view, TapPreset.FILET)!!

    /** The preset a set is, or Custom once any row has been changed away from all of them. */
    fun presetOf(view: TapView, set: TapSet): TapPreset =
        presets(view).firstOrNull { preset(view, it) == set } ?: TapPreset.CUSTOM

    /**
     * What a row in the settings may be set to. Focus and Expand fold folders, so they are the
     * tree's and only for folders; Peek and Open with are about a file's contents. The gallery
     * has no folders, so it is never asked for one.
     */
    fun choices(view: TapView, isDir: Boolean): List<TapDo> = TapDo.entries.filter {
        when (it) {
            TapDo.FOCUS, TapDo.EXPAND -> view == TapView.TREE && isDir
            TapDo.PEEK, TapDo.OPEN_WITH -> !isDir
            else -> true
        }
    }

    /** Stored as `f:ONE=OPEN,TWO=MENU,...;d:...` - readable in an exported config. */
    fun encode(set: TapSet): String {
        fun side(m: Map<Tap, TapDo>) = Tap.entries.joinToString(",") { "${it.name}=${(m[it] ?: TapDo.NOTHING).name}" }
        return "f:${side(set.file)};d:${side(set.folder)}"
    }

    /**
     * Read back, forgiving: anything missing or unknown falls back to the Filet preset's row, so
     * a value written by a later version, or typed into a config by hand, can never leave a
     * gesture doing something the view does not offer.
     */
    fun decode(view: TapView, stored: String?): TapSet {
        val base = default(view)
        if (stored.isNullOrBlank()) return base
        var set = base
        for (part in stored.split(';')) {
            val isDir = when (part.substringBefore(':', "")) {
                "f" -> false
                "d" -> true
                else -> continue
            }
            val allowed = choices(view, isDir)
            for (pair in part.substringAfter(':').split(',')) {
                val tap = Tap.entries.firstOrNull { it.name == pair.substringBefore('=') } ?: continue
                val act = TapDo.entries.firstOrNull { it.name == pair.substringAfter('=') } ?: continue
                if (act in allowed) set = set.with(isDir, tap, act)
            }
        }
        return set
    }

    /**
     * Xplorer's way back up a tree: tapping a folder ABOVE the one you are in keeps it open and
     * folds the branch you came up out of, and everything under that, while other open branches
     * stay as they were. From Down/trawl, tapping Down leaves Down open and folds trawl.
     *
     * @return the open set after the tap, unchanged when [at] is not above [was].
     */
    fun foldOnTheWay(open: Set<dev.niccc2007.filet.vfs.VPath>, at: dev.niccc2007.filet.vfs.VPath, was: dev.niccc2007.filet.vfs.VPath?): Set<dev.niccc2007.filet.vfs.VPath> {
        if (was == null || was == at || !TreeBranch.under(at, was)) return open
        // The child of [at] on the way down to [was].
        var child: dev.niccc2007.filet.vfs.VPath = was
        while (true) {
            // Out of an archive at its root, as everywhere else in the tree.
            val up = dev.niccc2007.filet.vfs.MountBoundary.up(child) ?: return open
            if (up == at) break
            child = up
        }
        return open.filterNot { TreeBranch.under(child, it) }.toSet()
    }
}

/**
 * Counts taps on one item and acts once the count is settled: at once when it reaches the most
 * the item listens for, otherwise when [window] passes with no further tap. A tap on a different
 * key settles the last one first, so a quick tap on one row then another never merges them.
 */
class TapCounter(private val scope: CoroutineScope, private val window: Long) {
    private var key: Any? = null
    private var count = 0
    private var job: Job? = null
    private var fire: ((Int) -> Unit)? = null

    fun tap(key: Any, max: Int, onSettled: (Int) -> Unit) {
        if (this.key != null && this.key != key) settle()
        this.key = key
        count++
        fire = onSettled
        job?.cancel()
        if (count >= max) {
            settle()
        } else {
            job = scope.launch {
                delay(window)
                settle()
            }
        }
    }

    private fun settle() {
        val f = fire
        val n = count
        job?.cancel()
        job = null
        fire = null
        key = null
        count = 0
        if (f != null && n > 0) f(n)
    }
}
