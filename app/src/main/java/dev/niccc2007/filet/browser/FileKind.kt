package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind

/**
 * What a file *is*, as far as the browser is concerned.
 *
 * Extension-driven and deliberately so: sniffing content means opening every file in a
 * 3,000-entry directory, and the answer is only used to pick an icon and a default handler.
 * The handler registry (L2) is what decides what actually opens it.
 */
enum class FileKind {
    FOLDER, ARCHIVE, APK, DEX, IMAGE, VIDEO, AUDIO, CODE, DOC, BINARY, OTHER;

    val icon: ImageVector
        get() = when (this) {
            FOLDER -> FiletIcons.Folder
            ARCHIVE -> FiletIcons.Zip
            APK -> FiletIcons.Apk
            DEX -> FiletIcons.Dex
            IMAGE -> FiletIcons.Image
            VIDEO -> FiletIcons.Video
            AUDIO -> FiletIcons.Audio
            CODE -> FiletIcons.Code
            DOC -> FiletIcons.Doc
            BINARY -> FiletIcons.Hex
            OTHER -> FiletIcons.File
        }

    /** True when tapping it should walk *into* it rather than open a viewer. */
    val isContainer: Boolean get() = this == FOLDER || this == ARCHIVE || this == APK

    companion object {
        /**
         * The kind, from the file type table.
         *
         * Bug identified here: this held five extension lists of its own, and they drifted from
         * every other list in the app - `.jfif` was a JPEG to the rotation code and nothing at
         * all here, so it drew a blank icon, got no thumbnail and no picture viewer. The table
         * declares each extension once with one kind, so nothing can be a picture in one place
         * and unknown in another. The bundle formats (xapk, apkm, apks) are app packages there,
         * not zips, which is what lets them offer to install - see SplitInstall.
         */
        fun of(node: VNode): FileKind {
            if (node.isDir) return FOLDER
            return when (FileTypes.kindOf(node.extension)) {
                TypeKind.APK -> APK
                TypeKind.DEX -> DEX
                TypeKind.ARCHIVE -> ARCHIVE
                TypeKind.IMAGE -> IMAGE
                TypeKind.VIDEO -> VIDEO
                TypeKind.AUDIO -> AUDIO
                TypeKind.CODE -> CODE
                TypeKind.DOC -> DOC
                TypeKind.BINARY -> BINARY
                TypeKind.OTHER, null -> OTHER
            }
        }
    }
}

/**
 * Order a listing.
 *
 * Dotfiles sort with their name, not after everything else — the leading dot is part of the
 * name and burying `.nomedia` at the bottom of a 400-row folder helps nobody. Comparison is
 * natural-order so `file2` precedes `file10`, which is the single sort bug every file manager
 * has and every user notices.
 */
fun List<VNode>.sortedBy(spec: SortSpec): List<VNode> {
    val forFiles = comparatorFor(spec.key, spec.descending)
    val folderKey = spec.folderKey
    // ── folders on their own key ────────────────────────────────────────────────
    //
    // Only when folders are GROUPED. With them mixed in among the files there is one
    // sequence, and one sequence cannot be in two orders - so the folder key is ignored
    // rather than half-applied, which would produce a list that is sorted in neither order.
    //
    // Partitioned and sorted separately rather than expressed as one comparator, because
    // there is no comparator that does this: a comparison between a folder and a file would
    // have to pick a key, and the whole point is that the two sides use different ones. The
    // grouping already guarantees they never meet.
    if (folderKey != null && spec.foldersFirst) {
        val forFolders = comparatorFor(folderKey, spec.folderDescending ?: spec.descending)
        val folders = ArrayList<VNode>()
        val files = ArrayList<VNode>()
        for (node in this) if (node.isDir) folders.add(node) else files.add(node)
        folders.sortWith(forFolders)
        files.sortWith(forFiles)
        return folders + files
    }
    // Folders-first is applied AFTER the direction flip, so reversing the sort never
    // reverses the folder grouping too - which looks like a bug even when it is consistent.
    val full =
        if (spec.foldersFirst) compareByDescending<VNode> { it.isDir }.then(forFiles) else forFiles
    return sortedWith(full)
}

/**
 * One key and one direction, as a comparator.
 *
 * Each is written so that its UNREVERSED order is the one worth seeing first: A to Z, newest,
 * largest. See `SortOrder` for why that is not the same as "ascending".
 */
private fun comparatorFor(key: SortKey, descending: Boolean): Comparator<VNode> {
    val byKey: Comparator<VNode> = when (key) {
        SortKey.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        SortKey.SIZE -> compareByDescending { it.size }
        SortKey.MODIFIED -> compareByDescending { it.mtime }
        SortKey.TYPE -> Comparator { a, b ->
            val c = a.extension.compareTo(b.extension)
            if (c != 0) c else naturalCompare(a.name, b.name)
        }
    }
    return if (descending) byKey else byKey.reversed()
}

/**
 * Compare two names treating digit runs as numbers.
 *
 * `IMG_2.jpg` before `IMG_10.jpg`. Case-insensitive, with a case-sensitive tiebreak so the
 * order is total and a list never reshuffles between two equal-ignoring-case names.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var i2 = i
            var j2 = j
            while (i2 < a.length && a[i2].isDigit()) i2++
            while (j2 < b.length && b[j2].isDigit()) j2++
            // Compare by value, not by text: skip leading zeros, then length, then digits.
            var si = i
            var sj = j
            while (si < i2 - 1 && a[si] == '0') si++
            while (sj < j2 - 1 && b[sj] == '0') sj++
            val la = i2 - si
            val lb = j2 - sj
            if (la != lb) return la - lb
            for (k in 0 until la) {
                val d = a[si + k] - b[sj + k]
                if (d != 0) return d
            }
            i = i2
            j = j2
            continue
        }
        val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
        if (d != 0) return d
        i++
        j++
    }
    val d = (a.length - i) - (b.length - j)
    if (d != 0) return d
    return a.compareTo(b)
}
