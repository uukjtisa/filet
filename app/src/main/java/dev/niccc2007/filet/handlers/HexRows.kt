package dev.niccc2007.filet.handlers

/**
 * Where everything sits in one row of the hex editor, as character positions in a single line.
 *
 * A row is one line of monospaced text: the offset, sixteen bytes in hex with an extra space after
 * the eighth, and the same sixteen as text. Drawing it as one line is what keeps scrolling fast,
 * and it means a tap is found by arithmetic on the character under the finger rather than by
 * sixteen tap targets per row.
 *
 * @param digits how many hex digits the offset takes: eight, or more for a file past 4 GB, so
 *   every row of one file lines up.
 */
internal class HexLayout(val digits: Int) {

    /** First character of byte [i]'s two hex digits. */
    fun hexAt(i: Int): Int = digits + GAP + 3 * i + if (i >= BYTES / 2) 1 else 0

    /** First character of the text column. */
    val ascii: Int = hexAt(BYTES - 1) + 2 + GAP

    /** Every row is this long, a short last row padded with spaces, so all rows are one width. */
    val chars: Int = ascii + BYTES

    /** The byte column under character [index], or null over the offset or the gap between columns. */
    fun columnAt(index: Int): Int? {
        if (index >= ascii) return (index - ascii).takeIf { it < BYTES }
        if (index < hexAt(0) || index >= hexAt(BYTES - 1) + 2) return null
        // The space after a byte belongs to it, so the whole run of the hex column is a target.
        return (BYTES - 1 downTo 0).first { hexAt(it) <= index }
    }

    /** The row starting at [offset] showing [values] (one to sixteen bytes). */
    fun text(offset: Long, values: ByteArray): String {
        val out = CharArray(chars) { ' ' }
        var o = offset
        for (d in digits - 1 downTo 0) {
            out[d] = DIGITS[(o and 0xF).toInt()]
            o = o ushr 4
        }
        for (i in 0 until minOf(values.size, BYTES)) {
            val b = values[i].toInt() and 0xFF
            val at = hexAt(i)
            out[at] = DIGITS[b ushr 4]
            out[at + 1] = DIGITS[b and 0xF]
            out[ascii + i] = if (b in 32..126) b.toChar() else '.'
        }
        return String(out)
    }

    /** The line above the rows: each column's number. */
    fun header(): String {
        val out = CharArray(chars) { ' ' }
        "Offset".forEachIndexed { i, c -> if (i < digits) out[i] = c }
        for (i in 0 until BYTES) {
            out[hexAt(i)] = '0'
            out[hexAt(i) + 1] = DIGITS[i]
            out[ascii + i] = DIGITS[i]
        }
        return String(out)
    }

    companion object {
        const val BYTES = 16
        private const val GAP = 2
        private val DIGITS = "0123456789ABCDEF".toCharArray()

        /** The layout for a file of [size] bytes: eight offset digits, more past 4 GB. */
        fun forSize(size: Long): HexLayout {
            var digits = 8
            var last = (size - 1).coerceAtLeast(0) ushr 32
            while (last > 0) {
                digits++
                last = last ushr 4
            }
            return HexLayout(digits)
        }
    }
}

/**
 * One step in the hex editor's history: the bytes as shown before it, and after.
 *
 * Shown values, not differences from the file. A save changes what the file holds, and a step
 * recorded as "the file's byte became 4F" would undo to the wrong value once the file's byte is
 * 4F itself. A shown value means the same thing either side of a save.
 */
data class ByteEdit(val changes: List<Change>) {
    data class Change(val offset: Long, val before: Byte, val after: Byte)

    fun inverse(): ByteEdit = ByteEdit(changes.map { Change(it.offset, it.after, it.before) })

    /** [edits] with this step's values shown, against [fileByte]: what the file holds there now. */
    fun applyTo(edits: HexEdits, fileByte: (Long) -> Byte): HexEdits =
        changes.fold(edits) { e, c -> e.put(c.offset, c.after, fileByte(c.offset)) }

    companion object {
        /**
         * The pending edits once [saved] is in the file, given [now], what is pending at the moment
         * the save lands. [file] is the file's byte at every offset either one touches; it is
         * updated to the saved values.
         *
         * Usually the two are the same and nothing is left. A byte changed while the save ran stays
         * pending, and a byte put back while it ran becomes pending again, since the file now holds
         * the saved value.
         */
        fun afterSave(saved: HexEdits, now: HexEdits, file: MutableMap<Long, Byte>): HexEdits {
            val offsets = saved.changes.keys + now.changes.keys
            val shown = offsets.associateWith { now.changes[it] ?: file.getValue(it) }
            for ((at, value) in saved.changes) file[at] = value
            return HexEdits(shown.filter { (at, value) -> file.getValue(at) != value })
        }
    }
}
