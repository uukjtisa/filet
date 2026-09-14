package dev.niccc2007.filet.vfs.provider

/**
 * What each format lets you *choose* when you create one.
 *
 * [Archives] answers "can Filet read and write this at all". This answers the next question,
 * which is the one Nic asked for: *"add strength, optional password and etc.. each tailored
 * for the type"*. Those are not the same question and they do not have the same shape - RAR
 * is readable and has no entry here at all, and a plain `.tar` is creatable and has no
 * strength, because it does not compress anything.
 *
 * ## Why this is a table and not a `when` in the dialog
 *
 * Rule R1, no dead switches. The moment the creation window decides for itself which controls
 * to draw, there are two answers to "can a 7z take a password" - the dialog's and the writer's
 * - and the one that loses is the writer, which finds out after the user has typed a password
 * and pressed Create. Everything the window draws comes from here, and `check-archiveui.mjs`
 * fails a control rendered outside a capability check.
 *
 * ## The scales are not the same scale
 *
 * This is the part that would be quietly wrong if it were one 0-9 slider reused everywhere.
 * Deflate levels, LZMA2 presets and bzip2 *block sizes* are three different things that happen
 * to be small integers, and bzip2's is a memory/window figure rather than an effort figure.
 * Each format carries its own range, its own default, and the unit it is really in.
 */

/**
 * A format's compression control, in the encoder's own units.
 *
 * @param unit what the number actually means, so the UI can say it rather than implying every
 *   format shares a scale.
 * @param default the encoder's own default, not a number picked here. Changing it changes
 *   what every new archive looks like, so it is one place.
 */
data class StrengthScale(
    val min: Int,
    val max: Int,
    val default: Int,
    val unit: String,
    val minLabel: String,
    val maxLabel: String,
) {
    init {
        require(min < max) { "a scale needs room in it: $min..$max" }
        require(default in min..max) { "default $default is outside $min..$max" }
    }

    /** Clamp rather than throw: a stored setting can outlive a change to the range. */
    fun coerce(value: Int): Int = value.coerceIn(min, max)
}

/** How an archive's contents are scrambled. Ordered strongest first, which is the UI order. */
enum class EncryptionMethod(val id: String, val label: String, val weak: Boolean = false) {
    AES_256("aes256", "AES-256"),
    AES_128("aes128", "AES-128"),

    /**
     * PKWARE's original zip cipher. Broken since 1994 by a known-plaintext attack, and kept
     * only because some things still cannot read anything else. Labelled as weak wherever it
     * is shown, because the whole point of offering it is that somebody has a reason.
     */
    ZIP_CRYPTO("zipcrypto", "ZipCrypto (legacy, weak)", weak = true),
}

/**
 * Whether this format can take a password, and if not, *which* reason.
 *
 * There are two different no's and gate C4 exists because merging them is a lie:
 *
 * - **The format has no encryption.** tar and the stream wrappers. Nothing Filet could ever
 *   do would change this.
 * - **The format has encryption and Filet cannot write it.** 7z, unless the native writer is
 *   compiled in. That is a limitation of this app, and saying "not supported" would blame the
 *   format for Filet's gap.
 */
data class PasswordSupport(
    val supported: Boolean,
    val methods: List<EncryptionMethod> = emptyList(),
    /** 7z and RAR can hide the file list itself. Zip cannot: its central directory is plain. */
    val canEncryptNames: Boolean = false,
    /** Why not. Non-null exactly when [supported] is false - the test enforces the "exactly". */
    val refusal: String? = null,
) {
    val default: EncryptionMethod? get() = methods.firstOrNull()
}

/** How a format is cut into parts, which is not the same mechanism in each case. */
enum class SplitStyle {
    NONE,

    /**
     * Zip's own multi-volume layout: `name.z01`, `name.z02`, ... and the LAST part is
     * `name.zip`, carrying the central directory. Not a byte split - the parts are not
     * concatenable - so it needs the writer's cooperation.
     */
    ZIP_VOLUMES,

    /**
     * The finished archive's bytes cut into `name.ext.001`, `.002`, ... Concatenating them in
     * order gives the original file back exactly, which is also how 7-Zip's own `.7z.001`
     * volumes work, so a set made this way opens in other tools.
     */
    NUMBERED_STREAM,
}

/**
 * What happens when one file inside an existing archive is changed.
 *
 * His ask was to save an edit back without recompressing the whole archive, and whether that
 * is possible is a
 * property of the container rather than of how clever the code is.
 */
enum class EditMode {
    /**
     * Rewrite the container, copying every untouched member's *already compressed* bytes
     * across without decoding them. One file changed means one file re-compressed. Zip and
     * plain tar, because each member stands alone in those.
     */
    PATCH,

    /**
     * The whole archive is one compression stream, so changing a byte in the middle means
     * redoing all of it. `tar.gz` and friends by construction, 7z because it is solid by
     * default. Not slow code - the format.
     */
    REBUILD,

    /** Filet cannot write this format. The only offer is saving the file somewhere else. */
    COPY_ONLY,
}

/**
 * Everything the creation window and the editor need to know about one format.
 *
 * @param strength null when the format does not compress. A `.tar` with a strength slider
 *   would be a control that does nothing, which is the rule this project enforces hardest.
 * @param minSplitBytes the smallest part a split can produce. Below it the format's own
 *   headers no longer fit, and finding that out at part 1 of 400 is the bug C6 exists for.
 */
data class ArchiveCapability(
    val formatId: String,
    val strength: StrengthScale?,
    val password: PasswordSupport,
    val split: SplitStyle,
    val minSplitBytes: Long,
    val edit: EditMode,
    /** Why this format cannot be split, when it cannot. Non-null exactly when [split] is NONE. */
    val splitRefusal: String? = null,
) {
    val canCompress: Boolean get() = strength != null
    val canSplit: Boolean get() = split != SplitStyle.NONE
}

object ArchiveCapabilities {

    // ── the scales, each in its own encoder's units ──

    /** Deflate, as `java.util.zip.Deflater` and zip4j both take it. 0 stores without deflating. */
    val DEFLATE = StrengthScale(0, 9, 6, "deflate level", "Store", "Maximum")

    /** LZMA2's preset, used by xz and by 7z. 6 is the encoder's own default. */
    val LZMA2_PRESET = StrengthScale(0, 9, 6, "LZMA2 preset", "Fastest", "Maximum")

    /** gzip. Level 0 exists and means "do not compress", which is what plain tar is for. */
    val GZIP = StrengthScale(1, 9, 6, "gzip level", "Fastest", "Maximum")

    /**
     * bzip2's **block size**, in hundreds of kilobytes - not an effort setting.
     *
     * 9 is commons-compress's default and is right nearly always; the reason to lower it is
     * memory, because the encoder holds the whole block. Calling this "strength" in the UI is
     * a simplification, so [StrengthScale.unit] says what it really is.
     */
    val BZIP2_BLOCK = StrengthScale(1, 9, 9, "block size (x100 KB)", "Least memory", "Smallest")

    // ── the two reasons a format cannot take a password ──

    private const val NO_ENCRYPTION_IN_FORMAT =
        "This format has no encryption in it. Compress to zip if the archive needs a password."

    /**
     * The 7z password refusal, and it names the real cause rather than shrugging.
     *
     * 7z supports AES-256 perfectly well - the format is not the problem, so a sentence saying
     * "not supported" would be false and would send somebody looking for a setting. What is
     * missing is a writer: no GPL-3-compatible library writes an encrypted 7z. libarchive's 7z
     * writer contains no encryption at all (checked against v3.7.7 - zero occurrences of
     * `passphrase`, `aes`, `encrypt` or `crypt` in 2,356 lines), commons-compress writes 7z
     * without it, and the one encoder that does is 7-Zip's own C++ tree, which is a vendoring
     * project rather than a feature. The whole measurement is in `core-native/README.md`.
     */
    private const val NO_7Z_WRITER =
        "7z supports AES-256, but nothing Filet can legally ship writes an encrypted one. " +
            "Compress to zip instead - Filet's zip does AES-256."

    private const val CANNOT_CREATE =
        "Filet reads this format and cannot create one, so there is nothing to set."

    private val ZIP_PASSWORD = PasswordSupport(
        supported = true,
        // Strongest first, and the weak one labelled as weak rather than left for the reader
        // to know. It is offered at all because some firmware and some very old tools cannot
        // read AES zips, and a password nobody can open is not security.
        methods = listOf(EncryptionMethod.AES_256, EncryptionMethod.AES_128, EncryptionMethod.ZIP_CRYPTO),
        // A zip's central directory is never encrypted: the NAMES are always readable, even
        // when every file's contents are not. Claiming otherwise would be the worst kind of
        // wrong, so it is false here and the UI says so.
        canEncryptNames = false,
    )

    /** zip4j refuses a split smaller than this, and so does the zip format. */
    const val ZIP_MIN_SPLIT: Long = 64 * 1024

    /** A stream split has no format floor; this is a sanity bound on part count. */
    const val STREAM_MIN_SPLIT: Long = 32 * 1024

    /**
     * What [format] supports.
     *
     * There is no "if the native 7z writer is present" branch here, and that is deliberate.
     * One existed while the native writer was still an open question; it was removed when the
     * answer came back no, because a branch nothing can reach is a claim that the feature might
     * arrive, and the next person to find it would have to re-derive why it never fires. Rule
     * R1 applied to a table rather than a button: the capability table says what this build can
     * do, not what some build might.
     */
    fun of(format: ArchiveFormat): ArchiveCapability {
        if (!format.canCreate) {
            return ArchiveCapability(
                formatId = format.id,
                strength = null,
                password = PasswordSupport(supported = false, refusal = CANNOT_CREATE),
                split = SplitStyle.NONE,
                minSplitBytes = 0,
                edit = if (format.canList) EditMode.COPY_ONLY else EditMode.COPY_ONLY,
                splitRefusal = CANNOT_CREATE,
            )
        }
        return when (format.id) {
            "zip" -> ArchiveCapability(
                formatId = "zip",
                strength = DEFLATE,
                password = ZIP_PASSWORD,
                split = SplitStyle.ZIP_VOLUMES,
                minSplitBytes = ZIP_MIN_SPLIT,
                edit = EditMode.PATCH,
            )

            "tar" -> ArchiveCapability(
                formatId = "tar",
                // Deliberately null. A tar is a container, not a compressor.
                strength = null,
                password = PasswordSupport(supported = false, refusal = NO_ENCRYPTION_IN_FORMAT),
                split = SplitStyle.NUMBERED_STREAM,
                minSplitBytes = STREAM_MIN_SPLIT,
                // Uncompressed, so a member can be replaced by copying the rest across
                // verbatim - the same cheap path zip gets, for a different reason.
                edit = EditMode.PATCH,
            )

            "tar.gz" -> tarPlus("tar.gz", GZIP)
            "tar.bz2" -> tarPlus("tar.bz2", BZIP2_BLOCK)
            "tar.xz" -> tarPlus("tar.xz", LZMA2_PRESET)

            "7z" -> ArchiveCapability(
                formatId = "7z",
                strength = LZMA2_PRESET,
                password = PasswordSupport(supported = false, refusal = NO_7Z_WRITER),
                // 7-Zip's own volumes are a plain byte split of the finished archive, so this
                // is the same mechanism as the tar family and produces sets 7-Zip opens.
                split = SplitStyle.NUMBERED_STREAM,
                minSplitBytes = STREAM_MIN_SPLIT,
                edit = EditMode.REBUILD,
            )

            else -> error("no capability entry for creatable format ${format.id}")
        }
    }

    /**
     * A tar through a compressor: the container never compresses, the wrapper always does, and
     * the wrapper is one stream over the whole thing.
     */
    private fun tarPlus(id: String, scale: StrengthScale) = ArchiveCapability(
        formatId = id,
        strength = scale,
        password = PasswordSupport(supported = false, refusal = NO_ENCRYPTION_IN_FORMAT),
        split = SplitStyle.NUMBERED_STREAM,
        minSplitBytes = STREAM_MIN_SPLIT,
        // One compression window over the whole tar, so changing one member re-encodes all of
        // it. This is why the editor has to say so before it starts rather than after.
        edit = EditMode.REBUILD,
    )

    /** Convenience for the creation window, in the picker's order. */
    fun creatable(): List<ArchiveCapability> = Archives.creatable.map { of(it) }

    /** What editing a member of [name] can offer, without the caller re-deriving the format. */
    fun editModeFor(name: String): EditMode {
        val format = Archives.of(name) ?: return EditMode.COPY_ONLY
        // A stream format holds exactly one member; replacing it IS rewriting the file, and
        // there is nothing to copy across, so it is a rebuild rather than a patch.
        if (format.kind == ArchiveKind.STREAM) return EditMode.REBUILD
        return of(format).edit
    }

    /**
     * Whether a part size can work for this capability, and why not when it cannot.
     *
     * Returns null when the size is fine. Checked before a byte is written, because the
     * alternative is finding out at part 1 of 400.
     */
    fun splitProblem(capability: ArchiveCapability, partBytes: Long, totalBytes: Long): String? {
        if (!capability.canSplit) return capability.splitRefusal
        if (partBytes < capability.minSplitBytes) {
            return "Parts must be at least ${capability.minSplitBytes / 1024} KB."
        }
        if (partBytes >= totalBytes) return null // one part; harmless, and the UI says so
        val parts = (totalBytes + partBytes - 1) / partBytes
        val limit = when (capability.split) {
            // .z01 .. .z99 then the final .zip. Past that the names collide.
            SplitStyle.ZIP_VOLUMES -> 99L
            // .001 .. .999. Beyond that the numbering needs a fourth digit and other tools
            // stop recognising the set.
            SplitStyle.NUMBERED_STREAM -> 999L
            SplitStyle.NONE -> 0L
        }
        if (parts > limit) {
            return "That size needs $parts parts and this format allows $limit. Use a bigger part size."
        }
        return null
    }
}
