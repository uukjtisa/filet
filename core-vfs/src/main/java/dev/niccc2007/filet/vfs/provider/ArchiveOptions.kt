package dev.niccc2007.filet.vfs.provider

/**
 * The choices made in the creation window, carried down to the writer.
 *
. Which of these
 * a format can honour is [ArchiveCapabilities]' answer, not this type's - this is only what was
 * asked for. The writer asserts the pairing rather than assuming the UI got it right, because a
 * password silently dropped on a format that cannot encrypt is the worst outcome available
 * here: the file looks protected and is not.
 *
 * Not a `data class`. [password] is a `CharArray` so it can be blanked after use, and a data
 * class would generate an `equals` that compares it by identity and a `toString` that prints
 * the reference - neither useful, and the second is a password one grep away from a log line.
 */
class ArchiveOptions(
    /** In the format's own units. Null means the encoder's default. */
    val strength: Int? = null,
    /** Cleared by the writer when it is finished with it. */
    val password: CharArray? = null,
    val encryption: EncryptionMethod? = null,
    /** 7z only. A zip's central directory is plain whatever the entries are encrypted with. */
    val encryptNames: Boolean = false,
    /** Bytes per part, or null for one file. */
    val splitBytes: Long? = null,
) {
    /**
     * Whether there is still a password here.
     *
     * A CLEARED password is not a password. [clearPassword] blanks the array in place rather
     * than dropping the reference - that is the whole reason the field is a `CharArray` - so a
     * check on the length alone keeps answering yes to an array of NULs, and the writer would
     * then happily encrypt with a key of nothing while every caller believed the password had
     * been wiped. Found by its own test rather than by review.
     */
    val hasPassword: Boolean get() = password?.any { it != '\u0000' } == true
    val isSplit: Boolean get() = (splitBytes ?: 0) > 0

    /** Blank the password in place. Call once the archive is written, success or failure. */
    fun clearPassword() {
        password?.fill('\u0000')
    }

    /**
     * Check these options against what the format can actually do.
     *
     * @return the reason they cannot be honoured, or null when they can. Called by the writer
     *   BEFORE it opens anything, so a refusal costs nothing and cannot leave a partial file.
     */
    fun problemFor(capability: ArchiveCapability): String? {
        if (hasPassword && !capability.password.supported) {
            return capability.password.refusal ?: "${capability.formatId} cannot take a password."
        }
        if (hasPassword && encryption != null && encryption !in capability.password.methods) {
            return "${capability.formatId} cannot use ${encryption.label}."
        }
        if (encryptNames && !capability.password.canEncryptNames) {
            return "${capability.formatId} cannot hide the file names."
        }
        if (isSplit && !capability.canSplit) {
            return capability.splitRefusal ?: "${capability.formatId} cannot be split."
        }
        if (strength != null && capability.strength == null) {
            return "${capability.formatId} does not compress, so it has no strength setting."
        }
        return null
    }

    /** The level to hand the encoder: what was asked for, clamped, or the format's default. */
    fun levelFor(capability: ArchiveCapability): Int? {
        val scale = capability.strength ?: return null
        return scale.coerce(strength ?: scale.default)
    }

    companion object {
        /** Plain: the format's own defaults, no password, one file. */
        val NONE = ArchiveOptions()
    }
}
