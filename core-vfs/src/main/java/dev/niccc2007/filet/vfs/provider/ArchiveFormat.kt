package dev.niccc2007.filet.vfs.provider

/**
 * What Filet can do with each archive format, in one place.
 *
 * Before this there were four answers to "is this an archive" in four files - the provider's
 * extension set, the handler registry's, the file-kind classifier's and the index's fact
 * extractor - and they disagreed. A `.tar.gz` was a file to three of them and an archive to
 * none. One table, and everything else asks it.
 *
 * The reason it is a table rather than a `when` is that the answer has three parts and they
 * come apart: a format can be listable and not creatable ([RAR]), creatable and not the
 * default for its extension, or readable only when there is a real path behind it.
 */
enum class ArchiveKind {
    /** A zip container: central directory at the end, random access, instant listing. */
    ZIP,

    /** A tar stream, possibly through a compressor. No index; listing means reading it. */
    TAR,

    /** 7z. Indexed like a zip, but the reader needs a seekable file rather than a stream. */
    SEVEN_ZIP,

    /** One compressed file with no container: `notes.txt.gz`. Exactly one member. */
    STREAM,

    /** Recognised, refused, and the refusal says why. */
    RAR,
}

/**
 * @param extensions matched against the END of the name, longest first, so `backup.tar.gz` is
 *   a gzipped tar and not a gzipped something-called-backup.tar.
 * @param canCreate whether Filet can write this format, which is a separate question from
 *   whether it can read it.
 * @param needsRealPath true when the reader cannot work from a stream, so the archive has to
 *   live somewhere with an OS path. A zip inside a zip is the case this rules out.
 * @param refusal why this format cannot be opened, or null when it can. Non-null exactly when
 *   [canList] is false, which the test enforces.
 */
data class ArchiveFormat(
    val id: String,
    val label: String,
    val kind: ArchiveKind,
    val extensions: List<String>,
    val canList: Boolean = true,
    val canCreate: Boolean = false,
    val needsRealPath: Boolean = false,
    val refusal: String? = null,
) {
    /** The suffix a new archive of this format gets, including the dot. */
    val suffix: String get() = "." + extensions.first()
}

object Archives {

    /**
     * Every format, in the order the picker offers them.
     *
     * Zip first because it is what everything else on earth opens. The tar family after,
     * because it is what keeps unix permissions and symlinks. 7z last of the creatable ones:
     * it compresses best and is the slowest by a wide margin on a phone.
     */
    val ALL: List<ArchiveFormat> = listOf(
        ArchiveFormat(
            id = "zip", label = "Zip", kind = ArchiveKind.ZIP,
            // apk, jar, aar and the rest are zips with a different name on the tin. Listing
            // them here rather than special-casing them is why walking into an APK works.
            extensions = listOf("zip", "apk", "jar", "aar", "apks", "xapk", "apkm", "epub", "cbz", "ipa", "war"),
            canCreate = true, needsRealPath = true,
        ),
        ArchiveFormat(
            id = "tar", label = "Tar", kind = ArchiveKind.TAR,
            extensions = listOf("tar"),
            canCreate = true,
        ),
        ArchiveFormat(
            id = "tar.gz", label = "Tar + gzip", kind = ArchiveKind.TAR,
            extensions = listOf("tar.gz", "tgz"),
            canCreate = true,
        ),
        ArchiveFormat(
            id = "tar.bz2", label = "Tar + bzip2", kind = ArchiveKind.TAR,
            extensions = listOf("tar.bz2", "tbz2", "tbz"),
            canCreate = true,
        ),
        ArchiveFormat(
            id = "tar.xz", label = "Tar + xz", kind = ArchiveKind.TAR,
            extensions = listOf("tar.xz", "txz"),
            canCreate = true,
        ),
        ArchiveFormat(
            id = "7z", label = "7-Zip", kind = ArchiveKind.SEVEN_ZIP,
            extensions = listOf("7z"),
            canCreate = true, needsRealPath = true,
        ),
        ArchiveFormat(
            id = "gz", label = "Gzip", kind = ArchiveKind.STREAM,
            extensions = listOf("gz"),
        ),
        ArchiveFormat(
            id = "bz2", label = "Bzip2", kind = ArchiveKind.STREAM,
            extensions = listOf("bz2"),
        ),
        ArchiveFormat(
            id = "xz", label = "XZ", kind = ArchiveKind.STREAM,
            extensions = listOf("xz"),
        ),
        ArchiveFormat(
            id = "rar", label = "RAR", kind = ArchiveKind.RAR,
            extensions = listOf("rar", "cbr"),
            canList = false,
            refusal = "Filet cannot open RAR. Every Java decoder for it is derived from the " +
                "UnRAR source, whose licence forbids using it to build an archiver - so it " +
                "cannot ship in a GPL-3 app. Hand the file to another app instead.",
        ),
    )

    /**
     * Which format a name belongs to, or null when it is not an archive.
     *
     * Matched on the longest suffix, which is the whole reason this is not a map lookup on
     * `substringAfterLast('.')`: that reads `backup.tar.gz` as a gzip and then shows one
     * member called `backup.tar` instead of the tree inside it.
     */
    fun of(name: String): ArchiveFormat? {
        val lower = name.lowercase()
        var best: ArchiveFormat? = null
        var bestLength = 0
        for (format in ALL) {
            for (ext in format.extensions) {
                if (ext.length > bestLength && lower.endsWith(".$ext")) {
                    best = format
                    bestLength = ext.length
                }
            }
        }
        return best
    }

    /** True when tapping this should walk into it rather than open a viewer. */
    fun canList(name: String): Boolean = of(name)?.canList == true

    /** Formats offered by "Compress", in the order above. */
    val creatable: List<ArchiveFormat> = ALL.filter { it.canCreate }

    /** Every extension any format claims. Used by the classifier and the index. */
    val allExtensions: Set<String> = ALL.flatMapTo(HashSet()) { it.extensions }

    /**
     * Strip the archive suffix from a name, for "extract here" naming a folder.
     *
     * `photos.tar.gz` gives `photos`, not `photos.tar` - which is what makes the extracted
     * folder read as the archive's contents rather than as a step in unwrapping it.
     */
    fun baseName(name: String): String {
        val format = of(name) ?: return name.substringBeforeLast('.', name)
        val lower = name.lowercase()
        val ext = format.extensions.filter { lower.endsWith(".$it") }.maxByOrNull { it.length }
            ?: return name
        return name.dropLast(ext.length + 1)
    }
}
