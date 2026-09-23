package dev.niccc2007.filet.metadata

/**
 * What metadata Filet can read and write, per format, and how confident that claim is.
 *
 * A metadata *writer* is a different proposition from a viewer, and this table is the honest
 * accounting of the difference. Reading is safe by construction: a parser that misunderstands
 * a file produces a wrong answer on screen. Writing rebuilds somebody's file, and a writer
 * that misunderstands a format produces a file that no longer opens.
 *
 * So every format is placed in a tier, the tiers mean something specific, and a format cannot
 * be written at all unless its tier says so:
 *
 *  - [Tier.FULL] - the container is simple, self-delimiting and round-trips byte-for-byte
 *    outside the part being changed. A write here is an append or a chunk swap with a length
 *    and a checksum, and the result is verified by re-parsing before it replaces anything.
 *  - [Tier.PARTIAL] - the write is well understood but the container has variants this does
 *    not handle, so some files in the format are refused rather than attempted. Refusing is
 *    the feature.
 *  - [Tier.READ_ONLY] - understood well enough to read, not well enough to rebuild. Usually
 *    because the format has internal offsets that every edit invalidates.
 *
 * The tiers are deliberately conservative. A format sitting in READ_ONLY costs a feature; a
 * format wrongly in FULL costs somebody their file.
 */
object MetadataSupport {

    enum class Tier {
        /** Safe to write. Verified by re-parse before the original is touched. */
        FULL,

        /** Writable for the variants named in [Format.caveat]; other variants are refused. */
        PARTIAL,

        /** Readable only. Attempting a write returns a refusal that says why. */
        READ_ONLY,
    }

    /** What kind of thing a field is, which decides how it is edited and how it is stored. */
    enum class FieldKind { TEXT, NUMBER, DATE, BINARY }

    /**
     * @param extensions lowercase, no dot.
     * @param custom whether arbitrary user-named keys can be stored. A format without this can
     *   only hold the fields it defines, so "secret note" has nowhere to go.
     * @param binary whether arbitrary bytes - an embedded image, for instance - can be stored.
     * @param caveat what the tier does not cover. Required on anything below FULL, because a
     *   tier without a reason is a claim nobody can check.
     */
    data class Format(
        val name: String,
        val extensions: List<String>,
        val tier: Tier,
        val fields: List<String>,
        val custom: Boolean,
        val binary: Boolean,
        val caveat: String? = null,
    )

    /**
     * The table.
     *
     * Ordered by tier so the list reads as what it is: what is safe, what is conditional, and
     * what is off limits.
     */
    val FORMATS: List<Format> = listOf(
        // ── writable ──────────────────────────────────────────────────────────────────
        Format(
            name = "PNG",
            extensions = listOf("png"),
            tier = Tier.FULL,
            // tEXt and iTXt keys are arbitrary by specification, which is why this is the one
            // format where a custom key and an embedded image both work without a trick.
            fields = listOf("Title", "Author", "Description", "Copyright", "Creation Time", "Software"),
            custom = true,
            binary = true,
        ),
        Format(
            name = "JPEG comment",
            extensions = listOf("jpg", "jpeg"),
            tier = Tier.FULL,
            // The COM segment, not EXIF. A comment is a length-prefixed segment that can be
            // replaced without touching anything else in the file.
            fields = listOf("Comment"),
            custom = false,
            binary = false,
        ),
        Format(
            name = "ZIP",
            extensions = listOf("zip", "apk", "jar", "xapk", "apkm"),
            tier = Tier.FULL,
            // The archive comment lives after the central directory and is length-prefixed in
            // the end-of-central-directory record. Nothing inside the archive moves.
            fields = listOf("Comment"),
            custom = false,
            binary = false,
            caveat = "Changes the archive comment only. Signed APKs keep their signature, " +
                "because the comment is outside the signed region.",
        ),
        Format(
            name = "GIF",
            extensions = listOf("gif"),
            tier = Tier.FULL,
            fields = listOf("Comment"),
            custom = false,
            binary = false,
        ),

        // ── conditional ───────────────────────────────────────────────────────────────
        Format(
            name = "WebP",
            extensions = listOf("webp"),
            tier = Tier.PARTIAL,
            fields = listOf("Comment"),
            custom = false,
            binary = false,
            caveat = "Extended (VP8X) files only. A simple lossy or lossless WebP has no " +
                "chunk to put metadata in and would have to be rewritten into the extended " +
                "form, which re-encodes the image.",
        ),
        Format(
            name = "MP3 (ID3v2)",
            extensions = listOf("mp3"),
            tier = Tier.PARTIAL,
            fields = listOf("Title", "Artist", "Album", "Year", "Track", "Genre", "Comment"),
            custom = true,
            binary = true,
            caveat = "ID3v2.3 and 2.4 only. A file with an ID3v1 tag at the end keeps it, and " +
                "the two can then disagree - players differ on which they believe.",
        ),

        // ── read only ─────────────────────────────────────────────────────────────────
        Format(
            name = "JPEG EXIF",
            extensions = listOf("jpg", "jpeg"),
            tier = Tier.READ_ONLY,
            fields = listOf(
                "Camera", "Lens", "Exposure", "Aperture", "ISO", "Focal length",
                "Date taken", "Orientation", "GPS",
            ),
            custom = false,
            binary = false,
            caveat = "EXIF is a TIFF structure of internal offsets. Changing the length of any " +
                "value moves everything after it, and a wrong offset makes the whole block " +
                "unreadable - including the thumbnail some galleries show instead of the photo.",
        ),
        Format(
            name = "MP4 / M4A",
            extensions = listOf("mp4", "m4v", "m4a", "mov"),
            tier = Tier.READ_ONLY,
            fields = listOf("Title", "Artist", "Album", "Duration", "Resolution", "Date"),
            custom = false,
            binary = false,
            caveat = "Tags live in a moov atom whose size is recorded in its own header and " +
                "whose position the sample tables point at. Growing it moves every offset in " +
                "the file, and a video that plays until it silently stops halfway is worse " +
                "than one that will not open at all.",
        ),
        Format(
            name = "PDF",
            extensions = listOf("pdf"),
            tier = Tier.READ_ONLY,
            fields = listOf("Title", "Author", "Subject", "Keywords", "Producer", "Created"),
            custom = false,
            binary = false,
            caveat = "The Info dictionary is reachable only through the cross-reference table, " +
                "which records a byte offset for every object. Rewriting it correctly means " +
                "rebuilding the xref, and getting that wrong produces a file that opens in one " +
                "reader and not another.",
        ),
        Format(
            name = "FLAC / OGG",
            extensions = listOf("flac", "ogg", "opus", "oga"),
            tier = Tier.READ_ONLY,
            fields = listOf("Title", "Artist", "Album", "Date", "Comment"),
            custom = false,
            binary = false,
            caveat = "Vorbis comments are writable in principle, but in Ogg they sit inside a " +
                "page stream whose CRCs and granule positions have to be recomputed across " +
                "every page that shifts.",
        ),
    )

    /** Every format that claims a given extension, most capable first. */
    fun forExtension(ext: String): List<Format> {
        val e = ext.lowercase().removePrefix(".")
        return FORMATS.filter { e in it.extensions }.sortedBy { it.tier.ordinal }
    }

    /** The best writable format for [ext], or null if nothing here may write it. */
    fun writerFor(ext: String): Format? =
        forExtension(ext).firstOrNull { it.tier != Tier.READ_ONLY }

    /** Whether anything at all can be written to a file with this extension. */
    fun canWrite(ext: String): Boolean = writerFor(ext) != null

    /**
     * Why a write was refused, in words that name the format's problem rather than the app's.
     *
     * A refusal that says "not supported" teaches nothing and reads as an unfinished feature.
     */
    fun refusalFor(ext: String): String {
        val known = forExtension(ext)
        if (known.isEmpty()) return "Filet does not recognise this file type well enough to change its metadata."
        val read = known.first()
        return read.caveat ?: "This format can be read but not safely rewritten."
    }

    /** A short count for the tier list on screen. */
    fun countByTier(): Map<Tier, Int> = Tier.entries.associateWith { t -> FORMATS.count { it.tier == t } }
}
