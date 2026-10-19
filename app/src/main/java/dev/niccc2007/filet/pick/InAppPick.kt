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
    /** Said in the notice strip: "Choose a picture for song.mp3". */
    val title: String,
    val want: Want,
    val accept: PickFilter = PickFilter.Everything,
    /** The button that takes a folder or a multi-selection: "Extract into this", "Use these". */
    val confirmLabel: String = "",
    /** Where browsing starts. Null means wherever the pane already is. */
    val start: VPath? = null,
) {
    enum class Want {
        /** One file; tapping an accepted file takes it. */
        FILE,

        /** One or more files, selected and then confirmed. */
        FILES,

        /** The folder that is open, taken by the confirm button. */
        FOLDER,
    }

    /** What a row is while this pick is live. Folders are always for walking through. */
    fun rowState(node: VNode): RowState = when {
        node.isDir -> RowState.FOLDER
        want == Want.FOLDER -> RowState.DIMMED
        accept.accepts(node) -> RowState.ACCEPTED
        else -> RowState.DIMMED
    }

    /** Why a dimmed file cannot be taken, said when it is tapped rather than doing nothing. */
    fun refusal(node: VNode): String = when {
        want == Want.FOLDER -> "Choose a folder - open it, then $confirmLabelOrDefault."
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
    fun resultFor(cwd: VPath?, selection: List<VNode>, active: VPath? = null): PickResult.Picked? = when (want) {
        Want.FOLDER -> folderOf(cwd, active)?.let { PickResult.Picked(listOf(it)) }
        Want.FILE, Want.FILES -> {
            val taken = selection.filter { rowState(it) == RowState.ACCEPTED }.map { it.path }
            val kept = if (want == Want.FILE) taken.take(1) else taken
            if (kept.isEmpty()) null else PickResult.Picked(kept)
        }
    }

    enum class RowState { ACCEPTED, DIMMED, FOLDER }
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
