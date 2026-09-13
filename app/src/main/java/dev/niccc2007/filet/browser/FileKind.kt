package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.vfs.VNode

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
        private val ARCHIVE_EXT = setOf("zip", "jar", "aar", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "epub", "apks", "xapk", "apkm")
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "flv", "wmv")
        private val AUDIO_EXT = setOf("mp3", "opus", "ogg", "m4a", "aac", "flac", "wav", "wma", "amr", "mid")
        private val CODE_EXT = setOf(
            "kt", "kts", "java", "smali", "xml", "json", "gradle", "py", "lua", "js", "ts", "tsx", "jsx",
            "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "yml", "yaml", "toml",
            "ini", "cfg", "conf", "properties", "css", "scss", "html", "htm", "sql", "gradlew", "pro",
        )
        private val DOC_EXT = setOf("txt", "md", "pdf", "doc", "docx", "rtf", "odt", "csv", "log", "srt", "vtt")
        private val BINARY_EXT = setOf("so", "bin", "dat", "img", "iso", "arsc", "o", "a", "dll", "exe")

        fun of(node: VNode): FileKind {
            if (node.isDir) return FOLDER
            return when (node.extension) {
                "apk" -> APK
                "dex", "odex", "vdex" -> DEX
                in ARCHIVE_EXT -> ARCHIVE
                in IMAGE_EXT -> IMAGE
                in VIDEO_EXT -> VIDEO
                in AUDIO_EXT -> AUDIO
                in CODE_EXT -> CODE
                in DOC_EXT -> DOC
                in BINARY_EXT -> BINARY
                else -> OTHER
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
    val byKey: Comparator<VNode> = when (spec.key) {
        SortKey.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        SortKey.SIZE -> compareBy { it.size }
        SortKey.MODIFIED -> compareBy { it.mtime }
        SortKey.TYPE -> Comparator { a, b ->
            val c = a.extension.compareTo(b.extension)
            if (c != 0) c else naturalCompare(a.name, b.name)
        }
    }
    val directed = if (spec.descending) byKey.reversed() else byKey
    // Folders-first is applied AFTER the direction flip, so reversing the sort never
    // reverses the folder grouping too - which looks like a bug even when it is consistent.
    val full = if (spec.foldersFirst) compareByDescending<VNode> { it.isDir }.then(directed) else directed
    return sortedWith(full)
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
