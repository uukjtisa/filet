package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * What several selected items come to and have in common, for one Details card over all of
 * them: how many of each, the files' own bytes, and the type, folder and dates they share.
 *
 * Details used to refuse more than one item. Asked of a handful of photos, the useful answers
 * are how big they are together and whether they are all from one place and one day, so those
 * are what the card leads with. The folders' sizes are not here: they are measured while the
 * card is open, as a single folder's are, and added in by [SelectionSize].
 */
data class SelectionSummary(
    val files: Int,
    val folders: Int,
    /** The files' own sizes, added up. A file whose size is unknown is left out, and counted in [unknown]. */
    val fileBytes: Long,
    val unknown: Int,
    /** Every type there is, most common first; one entry when they all share it. */
    val kinds: List<FileKind>,
    /** The one folder they are all in, or null when they are in several. */
    val parent: VPath?,
    val parents: Int,
    /** The most and least recently changed, among those with a date. */
    val newest: VNode?,
    val oldest: VNode?,
) {
    /** `3 files, 2 folders`, leaving out a kind there are none of. */
    val countLine: String
        get() = listOfNotNull(
            files.takeIf { it > 0 }?.let { FolderSizes.plural(it.toLong(), "file") },
            folders.takeIf { it > 0 }?.let { FolderSizes.plural(it.toLong(), "folder") },
        ).joinToString(", ").ifEmpty { "nothing" }

    /** The Type row: the shared type, or the mix, the commonest three named. */
    val typeLine: String
        get() = when {
            kinds.isEmpty() -> "unknown"
            kinds.size == 1 -> kinds[0].name.lowercase()
            else -> "mixed: " + kinds.take(3).joinToString(", ") { it.name.lowercase() } + if (kinds.size > 3) ", …" else ""
        }

    companion object {
        fun of(nodes: List<VNode>): SelectionSummary {
            val files = nodes.filterNot { it.isDir }
            val known = files.filter { it.size >= 0 }
            val kinds = nodes
                .groupingBy { if (it.isDir) FileKind.FOLDER else FileKind.of(it) }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<FileKind, Int>> { it.value }.thenBy { it.key.ordinal })
                .map { it.key }
            val parents = nodes.map { it.path.parent }.distinct()
            val dated = nodes.filter { it.mtime > 0 }
            return SelectionSummary(
                files = files.size,
                folders = nodes.size - files.size,
                fileBytes = known.sumOf { it.size },
                unknown = files.size - known.size,
                kinds = kinds,
                parent = parents.singleOrNull(),
                parents = parents.size,
                newest = dated.maxByOrNull { it.mtime },
                oldest = dated.minByOrNull { it.mtime },
            )
        }
    }
}

/**
 * The Size row of [SelectionSummary]'s card while its folders are measured: the files' bytes
 * plus whatever each folder's walk has counted so far.
 */
object SelectionSize {
    data class Line(val text: String, val measuring: Boolean)

    fun of(fileBytes: Long, unknown: Int, walks: List<WalkState>): Line {
        var bytes = fileBytes
        var running = 0
        var missing = 0
        var partial = unknown > 0
        for (w in walks) {
            when (w) {
                is WalkState.Running -> {
                    bytes += w.soFar.bytes
                    running++
                }
                is WalkState.Done -> {
                    bytes += w.tally.bytes
                    if (w.tally.partial) partial = true
                }
                is WalkState.Failed, WalkState.OnRequest -> missing++
            }
        }
        if (running > 0) return Line("${humanSize(bytes)} so far…", measuring = true)
        val text = buildString {
            if (partial || missing > 0) append("at least ")
            append(humanSize(bytes))
            append("  (${FolderSizes.grouped(bytes)} bytes)")
            if (missing > 0) append(" · ${FolderSizes.plural(missing.toLong(), "folder")} not measured")
        }
        return Line(text, measuring = false)
    }
}
