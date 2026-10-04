package dev.niccc2007.filet.handlers

/**
 * Byte changes waiting to be written, and the rules about writing them.
 *
 * ## Why editing hex needs a model and viewing it did not
 *
 * A hex editor writes bytes into a file with no idea what they mean. Every other editor in this
 * app works on something it understands - text it can re-parse, pixels it can redraw - and can
 * therefore tell you when you have broken it. This one cannot: a single wrong byte in a header is
 * a file that no longer opens, and nothing about the edit looks different from a correct one.
 *
 * So the edits are held, not applied. They are visible as a count and reversible one at a time
 * until somebody deliberately saves, and the save path is the only thing that touches the file.
 *
 * ## The size rule
 *
 * A patch is read-modify-write: there is no protocol here for changing four bytes in the middle
 * of a remote file, and none for a document provider either. That is fine for a header and absurd
 * for a disk image, so there is a ceiling, and it is stated rather than discovered when a phone
 * runs out of memory holding two copies of a 3 GB file.
 */
data class HexEdits(val changes: Map<Long, Byte> = emptyMap()) {

    val count: Int get() = changes.size

    val isEmpty: Boolean get() = changes.isEmpty()

    /**
     * Set the byte at [offset].
     *
     * Setting a byte back to what the file already held REMOVES the edit rather than recording a
     * no-op. Otherwise the pending count keeps climbing while somebody tries values out, and a
     * count that does not go back down cannot be trusted to mean anything.
     */
    fun put(offset: Long, value: Byte, original: Byte): HexEdits {
        val next = changes.toMutableMap()
        if (value == original) next.remove(offset) else next[offset] = value
        return HexEdits(next)
    }

    /** Drop one edit, putting that byte back to what the file holds. */
    fun revert(offset: Long): HexEdits =
        if (offset in changes) HexEdits(changes - offset) else this

    /** What this offset should show: the pending value if there is one, otherwise the file's. */
    fun valueAt(offset: Long, original: Byte): Byte = changes[offset] ?: original

    fun isEdited(offset: Long): Boolean = offset in changes

    /**
     * Apply the edits to a buffer that starts at [bufferStart] in the file.
     *
     * Takes the buffer's own position so a page can be painted with the pending changes without
     * the caller doing offset arithmetic at every call site - which is the arithmetic that would
     * eventually be wrong by one page and write a byte into the wrong place.
     */
    fun applyTo(buffer: ByteArray, bufferStart: Long): ByteArray {
        if (changes.isEmpty()) return buffer
        val out = buffer.copyOf()
        for ((offset, value) in changes) {
            val at = offset - bufferStart
            if (at in 0 until out.size.toLong()) out[at.toInt()] = value
        }
        return out
    }

    companion object {
        /**
         * The largest file this will patch.
         *
         * A patch is read-modify-write - there is no partial write over WebDAV and none through a
         * document provider either - so saving holds the whole file. 64 MB is comfortably more
         * than anything somebody hand-edits bytes in and comfortably less than what makes a phone
         * kill the app.
         */
        const val MAX_PATCH_BYTES = 64L * 1024 * 1024

        /**
         * Parse what somebody typed into a byte.
         *
         * Hex only, one or two digits, case-insensitive. Null for anything else rather than a
         * guess: in a tool where a wrong byte silently corrupts a file, reading "1G" as 1 is worse
         * than refusing it.
         */
        fun parseByte(typed: String): Byte? {
            val s = typed.trim()
            if (s.isEmpty() || s.length > 2) return null
            if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return s.toInt(16).toByte()
        }
    }
}
