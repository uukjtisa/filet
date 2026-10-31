package dev.niccc2007.filet.pick

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import java.util.Locale

/**
 * A pick Filet asks of itself: cover art for the metadata writer, a destination to extract
 * into, a settings file to import.
 *
 * ## Why the browser and not a dialogue
 *
 * These used to go through a small folder-list dialogue: no thumbnails, no sort, no search, and
 * a filtered pick listed only the files it accepted - so a picture that did not qualify was
 * simply absent, which reads as "Filet cannot see my file". The app already has a browser with
 * all of that. A pick now drives the ordinary pane, and this file is the whole of what differs:
 * what is wanted, what qualifies, and what comes back.
 *
 * One spec, one result, one entry point (`BrowserViewModel.pick`). Every caller states its pick
 * here rather than carrying its own flavour of it, which is what keeps seven callers from
 * drifting into seven pickers.
 */
data class PickSpec(
    /**
     * The whole sentence, written by the caller.
     *
     * Kept for callers that have nothing better to say, but [verb] and [subject] are the ones
     * to fill in: the card builds its own sentence from those, in one voice, so a new operation
     * reads like the existing ones rather than like whatever its author typed. See
     * [PickSentence].
     */
    val title: String,
    val want: Want,
    val accept: PickFilter = PickFilter.Everything,
    /** The button that takes a folder or a multi-selection: "Extract into this", "Use these". */
    val confirmLabel: String = "",
    /**
     * Where the pick's tab opens. Null - the rule - opens it on Home. See [startIn].
     *
     * Named only by an operation that belongs to a place: saving a file out of an archive
     * starts beside that archive ([besideArchive]).
     */
    val start: VPath? = null,
    /** For a [Want.SAVE]: the name the file is offered under, which the card lets be changed. */
    val name: String = "",
    /** For a [Want.SAVE]: what the file will be. A typed name is given this extension; see [PickName.fitted]. */
    val saveExtension: String = "",
    /**
     * What the operation DOES, in the imperative: "extract", "save", "share".
     *
     * Blank for a plain choose-a-file, where there is no operation to name - picking cover art
     * is "choose an image for song.mp3", not "choose an image to attach song.mp3".
     */
    val verb: String = "",
    /** What it is being done to or with: an archive's name, a song's name. */
    val subject: String = "",
) {
    enum class Want {
        /** One file; tapping an accepted file takes it. */
        FILE,

        /** One or more files, selected and then confirmed. */
        FILES,

        /** The folder that is open, taken by the confirm button. */
        FOLDER,

        /**
         * A file that does not exist yet: the folder that is open, and a name typed on the card.
         *
         * The one pick that was missing. Every save in the app used to write beside the file it
         * came from, under a name it chose itself, because there was no way to ask where or as
         * what - so an edited photo could not be saved into another folder, or under its own
         * name somewhere else, without being saved first and then moved and renamed by hand.
         */
        SAVE;

        /**
         * Whether the answer is a PLACE - the folder being stood in - rather than files found in it.
         * Exhaustive, so a fifth kind of pick cannot be added without deciding where its card goes.
         */
        val isPlace: Boolean get() = when (this) {
            FILE, FILES -> false
            FOLDER, SAVE -> true
        }
    }

    /**
     * Where the pick's tab opens: [start] when the operation names one, and Home - null -
     * for every other pick, whatever it wants.
     *
     * Bug identified here: picks opened on the folder the app was last in, which after a walk
     * through the drives is Internal storage - a folder of folders - and a save or a folder pick
     * opened there even after file picks had moved to Home. Home is set up to be where somebody
     * starts from: the categories, the recent files and folders, the bookmarks and the drives, or
     * the folder chosen to be Home. Every one of those is a tap from wherever the answer is, so
     * one rule for every pick beats a guess per kind about where the answer might be. Until a
     * folder is open, a place pick's card says so rather than confirming.
     *
     * The exception is an operation that comes from a place: a file saved out of an archive
     * starts beside the archive, because that is where whoever opened it was working.
     */
    fun startIn(): VPath? = start

    /** What a row is while this pick is live. Folders are always for walking through. */
    fun rowState(node: VNode): RowState = when {
        node.isDir -> RowState.FOLDER
        want == Want.FOLDER -> RowState.DIMMED
        // A file that could be saved over is lit, and tapping it takes its name - the way a
        // desktop save dialogue works. Anything else is dimmed: saving makes a file of one type.
        accept.accepts(node) -> RowState.ACCEPTED
        else -> RowState.DIMMED
    }

    /** Why a dimmed file cannot be taken, said when it is tapped rather than doing nothing. */
    fun refusal(node: VNode): String = when {
        want == Want.FOLDER -> "Choose a folder - open it, then $confirmLabelOrDefault."
        want == Want.SAVE -> "This saves a new file. Type its name on the card."
        else -> "${node.name} is not ${accept.what}."
    }

    private val confirmLabelOrDefault: String get() = confirmLabel.ifEmpty { "Use this folder" }

    /**
     * The folder somebody is standing in, for a FOLDER pick.
     *
     * Bug identified here: the confirm took the pane's `cwd`, and with the tree view on `cwd`
     * stays at the volume root while walking only opens branches and moves `active`. Every
     * folder pick returned `/storage/emulated/0` wherever the walk had got to. The open branch
     * wins when there is one; `cwd` only when there is not - the same rule as `PaneState.target`,
     * kept here as a function so the choice is tested rather than re-derived.
     */
    fun folderOf(cwd: VPath?, active: VPath?): VPath? = active ?: cwd

    /**
     * What a confirm with this selection returns, or null when it cannot be confirmed yet.
     *
     * Dimmed rows never get into a result even if selected, and a single-file pick keeps the
     * first: the caller asked for one, and quietly handing it several is how a caller ends up
     * acting on the wrong file.
     */
    /**
     * The chosen list after a tap on [node], which has already been accepted.
     *
     * A function rather than three lines in the tap handler because it is the decision that was
     * got wrong: the tap used to write a pane's selection, where "toggle" and "replace" were the
     * pane's own methods and the `want` was read at the call site. Moving the list onto the pick
     * means this rule lives in the one place that knows what kind of pick is running, and an
     * exhaustive `when` means a fourth [Want] cannot be added without deciding it.
     */
    fun chosenAfter(chosen: List<VNode>, node: VNode): List<VNode> = when (want) {
        // Tapping a chosen file again un-chooses it. The only way back out of a tap, now that
        // the row is not a pane selection with a bar offering to clear it.
        Want.FILES ->
            if (chosen.any { it.path == node.path }) chosen.filterNot { it.path == node.path }
            else chosen + node

        Want.FILE -> listOf(node)

        // A folder pick's answer is a place, not a file, and [rowState] dims every file for one -
        // so this is not reached. Returning the list unchanged rather than taking the file is
        // what keeps that true if something ever does reach it.
        Want.FOLDER -> chosen

        // A save chooses nothing: a tap on a file hands its NAME to the card instead.
        Want.SAVE -> chosen
    }

    fun resultFor(cwd: VPath?, selection: List<VNode>, active: VPath? = null, name: String = ""): PickResult.Picked? = when (want) {
        Want.FOLDER -> folderOf(cwd, active)?.let { PickResult.Picked(listOf(it)) }
        Want.SAVE -> folderOf(cwd, active)
            ?.takeIf { PickName.problem(name) == null }
            ?.let { PickResult.Picked(listOf(it.child(PickName.fitted(name, saveExtension)))) }
        Want.FILE, Want.FILES -> {
            val taken = selection.filter { rowState(it) == RowState.ACCEPTED }.map { it.path }
            val kept = if (want == Want.FILE) taken.take(1) else taken
            if (kept.isEmpty()) null else PickResult.Picked(kept)
        }
    }

    enum class RowState { ACCEPTED, DIMMED, FOLDER }

    companion object {
        /**
         * The folder an archive sits in, for a pick that starts beside it: [inside] is a file in
         * a mounted archive (`zip:///a/b.zip!/x/y.txt`), and the answer is `/a` on [hostScheme].
         * Null when [inside] is not inside an archive.
         */
        fun besideArchive(inside: VPath, hostScheme: String = "local"): VPath? =
            dev.niccc2007.filet.vfs.MountBoundary.split(inside)?.first?.let { VPath.of(hostScheme, it).parent }
    }
}

/** What a pick hands back to whoever asked. */
sealed interface PickResult {
    /** In the order chosen. A FILE or FOLDER pick always carries exactly one. */
    data class Picked(val paths: List<VPath>) : PickResult {
        val first: VPath get() = paths.first()
    }

    /** Nothing was chosen. The caller applies nothing. */
    data object Cancelled : PickResult
}

/** Which files qualify. Folders are never filtered: they are how anything is reached. */
sealed interface PickFilter {
    fun accepts(node: VNode): Boolean

    /** "a picture", "a .json file": the end of "x is not ...". */
    val what: String

    data object Everything : PickFilter {
        override fun accepts(node: VNode) = true
        override val what = "a file"
    }

    data class Extensions(val extensions: Set<String>, override val what: String) : PickFilter {
        private val lower = extensions.map { it.lowercase(Locale.US).trimStart('.') }.toSet()
        override fun accepts(node: VNode) = node.extension.lowercase(Locale.US) in lower
    }

    /** By type, through the same matcher the external picker uses. */
    data class Mime(val patterns: List<String>, override val what: String) : PickFilter {
        override fun accepts(node: VNode): Boolean {
            val mime = dev.niccc2007.filet.handlers.knownMime(node.extension) ?: return false
            return patterns.any { MimeMatch.matches(it.lowercase(Locale.US), mime) }
        }
    }

    /**
     * What another app asked for, when Filet is ITS picker.
     *
     * The same row rule as an in-app pick. Bug identified here: the external picker had a tested
     * matcher ([MimeMatch]) and the pane never consulted it, so an app asking for an image got
     * every file at full strength and found out which ones qualified only by being refused.
     */
    data class Request(val request: PickRequest) : PickFilter {
        override fun accepts(node: VNode) =
            MimeMatch.accepts(request, dev.niccc2007.filet.handlers.knownMime(node.extension))
        override val what: String get() = request.what
    }

    companion object {
        /** The pictures a cover can be. The same list the metadata writer can embed. */
        val PICTURES = Extensions(setOf("jpg", "jpeg", "png", "webp", "gif"), "a picture Filet can attach")
    }
}
