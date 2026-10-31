package dev.niccc2007.filet.browser

import androidx.compose.runtime.staticCompositionLocalOf
import dev.niccc2007.filet.vfs.VPath

/**
 * What a row knows about everything inside a folder, and how firm it is.
 *
 * ## Why four states and not a nullable Long
 *
 * This figure arrives late, can move, and - the part that was got wrong once already - can be a
 * FLOOR rather than an answer. A `Long?` could say none of that. Null meant two completely
 * different things, "nothing has ever measured this" and "a figure exists but it is provisional",
 * and a row that cannot tell them apart has to pick one lie.
 *
 * Bug identified here: the search index was treated as a source of totals. It is not one. It
 * holds only what its crawl has reached, so summing its rows under a folder gives the size of the
 * part that happens to be indexed - on a real phone, `Download` came out at 703 MB against an
 * actual 39 GB, and a folder whose files the crawl had never opened came out at `0 B`, which
 * reads as empty. `FolderMeasure` says so in its own words: the index "cannot say which of its
 * folders refused a listing - it simply has no rows under them - so its figure carries no 'at
 * least'". The details sheet is safe with that figure because it labels it and then walks to
 * confirm it; a row that printed the same number bare had neither protection.
 *
 * So the floor is a state of its own, and it is spelled on screen. The states a row can be in:
 *
 * | State | What the row shows |
 * |---|---|
 * | [Unknown] | a dim dash, breathing, no spinner - there is no figure and none to show yet |
 * | [AtLeast] | `703 MB+`, steady - a floor from the index, with nothing running |
 * | [Confirming] | `703 MB+` beside a small turning arc - a floor, and a walk running now |
 * | [Settled] | `39 GB`, steady, no marker - a walk finished and this is what is there |
 *
 * Only a completed walk can produce [Settled]. That is the invariant the whole type exists to
 * carry, and making it a sealed interface rather than a figure plus two flags is what stops the
 * incoherent combinations - a spinner over a number nobody has, a floor presented as final -
 * from being expressible at all.
 */
sealed interface FolderTotal {

    /** Nothing has measured this folder. No figure to show, and none from here. */
    data object Unknown : FolderTotal

    /**
     * At least this much, and nothing is running to find out the rest.
     *
     * What the index can honestly support: the crawl reached these bytes, and says nothing about
     * what it has not reached. Rendered with a `+` so it never reads as a total.
     */
    data class AtLeast(val bytes: Long) : FolderTotal

    /**
     * A floor, and a walk is running right now that will replace it with a real total.
     *
     * [bytes] can be a figure from the index or the running walk's own progress, so the row has
     * something to show while it works instead of blanking a number somebody is reading.
     */
    data class Confirming(val bytes: Long) : FolderTotal

    /** A completed walk. This is what is actually there. */
    data class Settled(val bytes: Long) : FolderTotal
}

/**
 * The figure, or null when nothing knows one.
 *
 * An extension with an exhaustive `when` rather than a property on the interface, for the usual
 * reason: a fifth state cannot be added without this refusing to compile until somebody has
 * decided what it prints. A default getter on the interface would have answered null for it in
 * silence, which is the same quiet wrong number this whole type exists to avoid.
 */
fun FolderTotal.figure(): Long? = when (this) {
    FolderTotal.Unknown -> null
    is FolderTotal.AtLeast -> bytes
    is FolderTotal.Confirming -> bytes
    is FolderTotal.Settled -> bytes
}

/** Whether the row draws the turning arc. Only [FolderTotal.Confirming] does. */
fun FolderTotal.busy(): Boolean = when (this) {
    FolderTotal.Unknown -> false
    is FolderTotal.AtLeast -> false
    is FolderTotal.Confirming -> true
    is FolderTotal.Settled -> false
}

/**
 * Whether the figure is a floor rather than a total, so it is printed with a `+`.
 *
 * The distinction the row got wrong. Everything except a finished walk is a floor.
 */
fun FolderTotal.isFloor(): Boolean = when (this) {
    FolderTotal.Unknown -> false
    is FolderTotal.AtLeast -> true
    is FolderTotal.Confirming -> true
    is FolderTotal.Settled -> false
}

/** Whether there is a figure at all, so the row knows to draw a dash instead of one. */
fun FolderTotal.known(): Boolean = figure() != null

/** Whether a real total is still worth asking for. A finished walk is not. */
fun FolderTotal.wantsWalk(): Boolean = when (this) {
    FolderTotal.Unknown -> true
    is FolderTotal.AtLeast -> true
    is FolderTotal.Confirming -> false
    is FolderTotal.Settled -> false
}

/**
 * A folder's total size: what is known now, and a way to ask for the real thing.
 *
 * ## Why reading and measuring are separate
 *
 * [of] never touches the filesystem. It reads what is already known - a walk that has finished,
 * or the index's floor - and returns immediately, which is what a list row scrolling past can
 * afford.
 *
 * [measure] is the request for an actual answer, and it is deliberately not something [of] does
 * on its own: a read that silently started work would mean a fling down a folder of five hundred
 * subfolders kicked off five hundred tree walks. What it does instead is put the folder in a
 * queue that runs ONE walk at a time - see `FolderMeasure.measure` - so the cost of having real
 * folder sizes is one background walk, not one per row.
 *
 * ## Why a composition local
 *
 * The row is drawn several layers below anything holding the object graph, and threading this
 * down through every list, grid and pane signature would touch a dozen call sites to deliver one
 * figure. The same reasoning as `LocalPickInset`.
 *
 * A null local means "nobody provided one", which is the correct state in a preview or a test,
 * and renders as no total rather than as a crash.
 */
interface FolderBytes {
    /** What is known about [path] right now. Never walks, never blocks on the filesystem. */
    suspend fun of(path: VPath): FolderTotal

    /** Ask for [path] to be walked for real, behind the one-at-a-time queue. */
    fun measure(path: VPath)
}

val LocalFolderBytes = staticCompositionLocalOf<FolderBytes?> { null }
