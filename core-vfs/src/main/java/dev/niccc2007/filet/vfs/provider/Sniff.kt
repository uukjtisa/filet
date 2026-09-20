package dev.niccc2007.filet.vfs.provider

/**
 * Deciding what a file really is, from its first few bytes.
 *
 * Bug identified: an archive renamed to something else could not be opened as one. Every
 * routing decision in the app was made on the extension, so a zip saved as `.bin`, or a backup
 * a phone had helpfully renamed, was a binary blob with no way to look inside it - even though
 * the reader would have opened it immediately.
 *
 * An extension is a hint somebody typed. The first bytes are what the file is.
 *
 * ## The direction that matters
 *
 * Magic alone is not enough, because the interesting container formats are all the same
 * container. An APK is a zip. So is an XAPK, a JAR, an EPUB, a DOCX and an ODT. Sniffing the
 * zip signature and concluding "archive" would offer the archive viewer for every one of them
 * and bury the APK inspector for an APK, which is worse than the problem being fixed.
 *
 * So this answers two things separately: what the CONTAINER is, and how much that agrees with
 * what the name claims. A name that agrees is certain. A name that claims nothing is a good
 * guess. A name that disagrees is the case worth asking a question about.
 */
object Sniff {

    /** What the leading bytes say the file is. */
    enum class Kind { ZIP, GZIP, BZIP2, XZ, SEVEN_ZIP, RAR, TAR, UNKNOWN }

    /** How much the bytes and the name agree. */
    enum class Confidence {
        /** The bytes say archive and the name agrees. Open it without asking. */
        CERTAIN,

        /** The bytes say archive and the name claims nothing in particular. */
        LIKELY,

        /** The bytes say archive and the name says something else entirely. */
        CONFLICTED,

        /** The bytes say nothing recognisable. */
        NONE,
    }

    /** How many bytes are needed. A tar's magic sits at offset 257, which sets this. */
    const val NEEDED = 264

    private fun startsWith(b: ByteArray, vararg sig: Int): Boolean {
        if (b.size < sig.size) return false
        for (i in sig.indices) if ((b[i].toInt() and 0xFF) != sig[i]) return false
        return true
    }

    /** What the bytes say, ignoring the name entirely. */
    fun kindOf(head: ByteArray): Kind = when {
        // The three zip signatures: a local file header, an empty archive, and a spanned one.
        startsWith(head, 0x50, 0x4B, 0x03, 0x04) ||
            startsWith(head, 0x50, 0x4B, 0x05, 0x06) ||
            startsWith(head, 0x50, 0x4B, 0x07, 0x08) -> Kind.ZIP
        startsWith(head, 0x1F, 0x8B) -> Kind.GZIP
        startsWith(head, 0x42, 0x5A, 0x68) -> Kind.BZIP2
        startsWith(head, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Kind.XZ
        startsWith(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_ZIP
        startsWith(head, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Kind.RAR
        isTar(head) -> Kind.TAR
        else -> Kind.UNKNOWN
    }

    /**
     * A tar has no signature at the start at all.
     *
     * Its magic is the word `ustar` at offset 257, which is why [NEEDED] is not a handful of
     * bytes. Reading fewer would silently classify every tar as unknown.
     */
    private fun isTar(b: ByteArray): Boolean {
        if (b.size < 262) return false
        return String(b, 257, 5, Charsets.US_ASCII) == "ustar"
    }

    /**
     * How much to trust [kind] for a file called something in particular.
     *
     * @param nameIsArchive whether the archive table already claims this name.
     * @param nameIsKnownOther whether the name claims some other specific thing - an app
     *   package, a document, an ebook. Those are zips underneath, and putting the archive
     *   viewer ahead of their real handler helps nobody.
     */
    fun confidence(kind: Kind, nameIsArchive: Boolean, nameIsKnownOther: Boolean): Confidence = when {
        kind == Kind.UNKNOWN -> Confidence.NONE
        nameIsArchive -> Confidence.CERTAIN
        nameIsKnownOther -> Confidence.CONFLICTED
        else -> Confidence.LIKELY
    }

    /**
     * Whether to open this as an archive without asking.
     *
     * Only when nothing else has a better claim. A conflicted file - a document, which is of
     * course a zip - keeps its own handler, and the choice is offered rather than taken.
     */
    fun openAsArchive(confidence: Confidence): Boolean =
        confidence == Confidence.CERTAIN || confidence == Confidence.LIKELY
}
