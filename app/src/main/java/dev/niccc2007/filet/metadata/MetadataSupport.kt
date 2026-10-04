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
 *
 * ## Every row points at the code that keeps its promise
 *
 * Three rows of this table were once pure fiction - MP3 claimed an attached picture, EXIF claimed
 * nine fields, WebP claimed a comment slot, and not one of the three had any code behind it at
 * all. They were not spotted by review; they were spotted by somebody opening a file and finding
 * nothing there.
 *
 * The fix is structural rather than a round of corrections. Every [Format] now names its
 * [MetadataContainer], its field list is read **out of** that container rather than typed again
 * here, and `MetadataSupportTest` asserts that a row cannot claim a tier or a capability its
 * container does not implement. A fictional row is now a failing test rather than a disappointed
 * user.
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
     * @param engine the code that actually does it. Not optional, and checked against the claims.
     * @param custom whether arbitrary user-named keys can be stored. A format without this can
     *   only hold the fields it defines, so "secret note" has nowhere to go.
     * @param binary whether an attached picture can be stored.
     * @param caveat what the tier does not cover. Required on anything below FULL, because a
     *   tier without a reason is a claim nobody can check.
     */
    data class Format(
        val name: String,
        val extensions: List<String>,
        val tier: Tier,
        val engine: MetadataContainer,
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
            engine = PngContainer,
            // tEXt and iTXt keys are arbitrary by specification, which is why this is the one
            // image format where a field of your own naming works without a trick.
            fields = listOf("Title", "Author", "Description", "Copyright", "Creation Time", "Software"),
            custom = true,
            // A PNG is itself the picture. There is no second image to attach to it, and the
            // table used to say there was.
            binary = false,
        ),
        Format(
            name = "JPEG comment",
            extensions = listOf("jpg", "jpeg"),
            tier = Tier.FULL,
            engine = JpegCommentContainer,
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
            engine = ZipContainer,
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
            engine = GifContainer,
            fields = listOf("Comment"),
            custom = false,
            binary = false,
        ),
        Format(
            name = "FLAC",
            extensions = listOf("flac"),
            tier = Tier.FULL,
            engine = FlacContainer,
            // The one lossless format that is genuinely easy: the comment block is at the front
            // and length-prefixed, and SEEKTABLE offsets are measured from the first audio frame
            // rather than from the start of the file - so resizing the header moves nothing that
            // anything points at.
            fields = VorbisComment.FIELDS.map { it.first },
            custom = true,
            binary = true,
        ),

        // ── conditional ───────────────────────────────────────────────────────────────
        Format(
            name = "MP3 (ID3v2)",
            extensions = listOf("mp3"),
            tier = Tier.PARTIAL,
            engine = Id3Container,
            fields = Id3.FIELDS.map { it.second },
            // An ID3 tag has a fixed set of frames. TXXX would allow a named one, and inventing
            // a frame for a typo is worse than refusing the field.
            custom = false,
            binary = true,
            caveat = "ID3v2.3 and 2.4 only, and always written back as 2.3. A file with an " +
                "ID3v1 tag at the end keeps it, and the two can then disagree - players differ " +
                "on which they believe.",
        ),
        Format(
            name = "Ogg / Opus",
            extensions = listOf("ogg", "opus", "oga", "spx"),
            tier = Tier.PARTIAL,
            engine = OggContainer,
            fields = VorbisComment.FIELDS.map { it.first },
            custom = true,
            binary = true,
            caveat = "One logical stream per file. Changing the tag means re-paginating from " +
                "the comment packet and recomputing every page checksum after it, which is done " +
                "- but a chained or multiplexed file has several streams and no way to tell " +
                "which one you meant.",
        ),
        Format(
            name = "MP4 / M4A",
            extensions = listOf("mp4", "m4v", "m4a", "m4b", "mov"),
            tier = Tier.PARTIAL,
            engine = Mp4Container,
            fields = Mp4Tags.FIELDS.map { it.first },
            custom = false,
            binary = true,
            caveat = "Tags live in a moov atom, and resizing it moves the media after it - so " +
                "every chunk offset in stco and co64 is corrected by the same amount. " +
                "Fragmented files keep offsets outside moov as well and are refused.",
        ),
        Format(
            name = "PDF",
            extensions = listOf("pdf"),
            tier = Tier.PARTIAL,
            engine = PdfContainer,
            fields = PdfInfo.FIELDS.map { it.first },
            custom = false,
            binary = false,
            caveat = "Written as an incremental update: the new properties are appended and not " +
                "one original byte is touched, so even a signed PDF keeps its signature. A file " +
                "whose cross-reference table is a compressed stream is read but not extended.",
        ),
        Format(
            name = "Office document",
            extensions = listOf(
                "docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm",
                "pptx", "pptm", "potx", "ppsx",
            ),
            tier = Tier.PARTIAL,
            engine = OoxmlContainer,
            fields = Ooxml.FIELDS.map { it.first },
            custom = false,
            binary = false,
            caveat = "The properties are an XML file inside the zip, and only that one entry is " +
                "rewritten - everything else is copied still compressed, in its original order. " +
                "A package that has no properties file yet is refused rather than given one.",
        ),

        // ── read only ─────────────────────────────────────────────────────────────────
        Format(
            name = "JPEG EXIF",
            extensions = listOf("jpg", "jpeg"),
            tier = Tier.READ_ONLY,
            engine = JpegExifContainer,
            fields = Exif.FIELDS,
            custom = false,
            binary = false,
            caveat = "EXIF is a TIFF structure of internal offsets. Changing the length of any " +
                "value moves everything after it, and a wrong offset makes the whole block " +
                "unreadable - including the thumbnail some galleries show instead of the photo.",
        ),
        Format(
            name = "WebP EXIF",
            extensions = listOf("webp"),
            tier = Tier.READ_ONLY,
            engine = WebpExifContainer,
            fields = Exif.FIELDS,
            custom = false,
            binary = false,
            caveat = "The same camera record a JPEG carries, in a RIFF chunk instead. Writing " +
                "it has the same offset problem, and a simple WebP has no chunk to add metadata " +
                "to at all without re-encoding the image.",
        ),
    )

    /** Every format that claims a given extension, most capable first. */
    fun forExtension(ext: String): List<Format> {
        val e = ext.lowercase().removePrefix(".")
        return FORMATS.filter { e in it.extensions }.sortedBy { it.tier.ordinal }
    }

    /**
     * Every format whose container also recognises these bytes, most capable first.
     *
     * The extension alone is a hint - a `.m4a` that is really an MP3 is a thing that happens -
     * and asking the container settles it.
     */
    fun forFile(ext: String, bytes: ByteArray): List<Format> =
        forExtension(ext).filter { it.engine.matches(ext, bytes) }

    /** The best writable format for [ext], or null if nothing here may write it. */
    fun writerFor(ext: String): Format? =
        forExtension(ext).firstOrNull { it.tier != Tier.READ_ONLY }

    /** The same, confirmed against the bytes rather than trusting the name. */
    fun writerFor(ext: String, bytes: ByteArray): Format? =
        forFile(ext, bytes).firstOrNull { it.tier != Tier.READ_ONLY }

    /** Whether anything at all can be written to a file with this extension. */
    fun canWrite(ext: String): Boolean = writerFor(ext) != null

    /** Every format that can carry an attached picture. */
    fun withPictures(): List<Format> = FORMATS.filter { it.binary }

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
