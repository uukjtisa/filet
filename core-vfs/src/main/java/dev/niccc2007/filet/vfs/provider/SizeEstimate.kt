package dev.niccc2007.filet.vfs.provider

/**
 * Roughly how big the archive will come out, before anybody waits for it.
 *
 * A number here is a guess and saying otherwise would be a lie, so this returns a RANGE and a
 * reason rather than a figure. The reason is the part that is actually useful: zipping a folder
 * of mp4s at maximum will take minutes and save nothing, and the only moment that is worth
 * knowing is before pressing Compress.
 *
 * The already-compressed judgement comes from [ArchiveWriter.ALREADY_COMPRESSED] - the same set
 * the zip writer uses to decide what to store instead of deflate - rather than a second opinion
 * living in the dialog. Two opinions about what compresses is how an estimate and a result end
 * up disagreeing for reasons nobody can trace.
 */
data class SizeEstimate(
    /** Best case, in bytes. */
    val low: Long,
    /** Worst case, in bytes. */
    val high: Long,
    /** Of [knownBytes], how much will not shrink whatever the setting. */
    val incompressibleBytes: Long,
    /** The input this was computed from. */
    val knownBytes: Long,
    /**
     * True when something in the selection could not be measured - a folder, or a provider that
     * does not report a size cheaply.
     *
     * The range is then a floor rather than a range, and the UI must say "at least".
     */
    val partial: Boolean,
) {
    /** Nothing measurable went in, so there is nothing honest to show. */
    val empty: Boolean get() = knownBytes <= 0L && !partial

    /** Most of what is going in is already compressed, so the setting will not matter much. */
    val mostlyIncompressible: Boolean
        get() = knownBytes > 0 && incompressibleBytes * 2 > knownBytes
}

/**
 * The estimator.
 *
 * Pure, and tested, because it is a decision that can be wrong: every ratio here is a claim
 * about an encoder, and a claim with no test is a number somebody made up once.
 */
object CompressEstimate {

    /**
     * How far a compressible byte shrinks, per format family.
     *
     * `worst` is the ratio at the bottom of the format's own strength scale and `best` the
     * ratio at the top; a setting in between is interpolated. The bands are deliberately wide -
     * text goes to a fifth, an already-packed database barely moves, and pretending to know
     * which is in the selection would be the dishonest version of this feature.
     *
     * Deflate's bottom really is "store", which is why its worst case is 1.0 and gzip's - whose
     * scale starts at level 1 - is not.
     */
    private data class Band(val worst: Double, val best: Double)

    private val NONE = Band(1.0, 1.0)
    private val DEFLATE = Band(1.0, 0.42)
    private val GZIP = Band(0.62, 0.42)
    private val BZIP2 = Band(0.46, 0.38)
    private val LZMA2 = Band(0.52, 0.32)

    /**
     * How uncertain the SAVING is, as a fraction of it.
     *
     * Deliberately a fraction of the saving and not of the output. At "store", and for plain
     * tar, there is no saving and therefore no uncertainty - the archive is the sum of its
     * parts, and a range around that number would be inventing doubt where there is none.
     * Caught by `plain_tar_never_promises_a_saving`, which failed on the first version of this.
     */
    private const val SPREAD = 0.45

    /**
     * Per-entry overhead. A zip local header plus central directory entry is about this much,
     * and a tar header is 512 bytes exactly - so an archive of ten thousand empty files is
     * bigger than the files, which is a real thing people hit.
     */
    private const val ENTRY_OVERHEAD = 160L
    private const val TAR_ENTRY_OVERHEAD = 512L

    /**
     * @param items name-and-size for everything going in. A size below zero means unknown,
     *   which sets [SizeEstimate.partial] rather than being guessed at.
     */
    fun of(items: List<Pair<String, Long>>, format: ArchiveFormat, strength: Int?): SizeEstimate {
        var known = 0L
        var incompressible = 0L
        var partial = false
        var counted = 0
        for ((name, size) in items) {
            if (size < 0) { partial = true; continue }
            counted++
            known += size
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in ArchiveWriter.ALREADY_COMPRESSED) incompressible += size
        }

        val band = bandFor(format)
        val ratio = ratioAt(band, ArchiveCapabilities.of(format).strength, strength)
        val compressible = known - incompressible

        // Already-compressed bytes are carried, not shrunk. A stored zip entry is its own size;
        // a tar wrapped in xz will usually add a fraction of a percent to them rather than
        // remove any, and rounding that to "unchanged" is inside the width of the range anyway.
        val mid = incompressible + (compressible * ratio).toLong()
        val overhead = counted * if (format.kind == ArchiveKind.TAR) TAR_ENTRY_OVERHEAD else ENTRY_OVERHEAD

        val spread = (compressible * (1.0 - ratio) * SPREAD).toLong()
        // Never below the bytes that cannot shrink: whatever the encoder does, they are going
        // in, so a floor under them is a fact rather than an estimate.
        val low = (mid - spread + overhead).coerceAtLeast(incompressible)
        val high = (mid + spread + overhead).coerceAtLeast(low)
        return SizeEstimate(low, high, incompressible, known, partial)
    }

    private fun bandFor(format: ArchiveFormat): Band = when (format.id) {
        "zip" -> DEFLATE
        "tar" -> NONE
        "tar.gz", "gz" -> GZIP
        "tar.bz2", "bz2" -> BZIP2
        "tar.xz", "xz", "7z" -> LZMA2
        // Anything not listed is not creatable, so this is reached only by a format added to
        // the table and not to this one. Claiming no compression is the safe direction: the
        // estimate comes out too big rather than promising a saving that does not arrive.
        else -> NONE
    }

    /**
     * Interpolate the band at the chosen setting.
     *
     * bzip2 is the odd one: its scale is a block size rather than an effort level, so the two
     * ends of it are much closer together than deflate's. That is expressed in the band, not
     * with a special case here.
     */
    private fun ratioAt(band: Band, scale: StrengthScale?, strength: Int?): Double {
        if (scale == null) return band.worst
        val level = scale.coerce(strength ?: scale.default)
        val f = (level - scale.min).toDouble() / (scale.max - scale.min).toDouble()
        return band.worst + (band.best - band.worst) * f
    }
}
