package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream

/**
 * iTunes-style tags in an MP4 container: `.m4a`, `.mp4`, `.m4v`, `.mov`.
 *
 * ## The objection this answers
 *
 * The support table used to call this read-only, and the reason it gave was real: tags live in a
 * `moov` atom whose size is written in its own header, and the sample tables inside it point at
 * the audio or video data with **absolute file offsets**. Growing `moov` by one byte moves
 * everything after it and leaves every one of those offsets pointing one byte early.
 *
 * What the caveat got wrong is the conclusion. Those offsets are not scattered through the file -
 * they are all in `stco` (32-bit) or `co64` (64-bit) tables, both inside `moov`, and the fix is to
 * add the same delta to each one. That is what every tagger does, and it is about thirty lines.
 * The hard part is doing it *only* for the offsets that actually moved, which is why the rule here
 * is written as a comparison against the old position of `moov` rather than as a blanket add.
 *
 * ## What is still refused
 *
 * A **fragmented** file - one with `moof`, `sidx` or `mfra` at the top level - is refused. Those
 * carry their own offset tables outside `moov`, streaming players seek by them, and a file that
 * plays from the start and then stops at the first seek is a worse outcome than a refusal.
 */
object Mp4Tags {

    /** Display label to the four-character item name iTunes uses. */
    val FIELDS: List<Pair<String, String>> = listOf(
        "Title" to "©nam",
        "Artist" to "©ART",
        "Album" to "©alb",
        "Album artist" to "aART",
        "Date" to "©day",
        "Genre" to "©gen",
        "Composer" to "©wrt",
        "Comment" to "©cmt",
        "Track" to "trkn",
    )

    private val BY_LABEL = FIELDS.associate { (label, name) -> label.lowercase() to name }
    private val BY_NAME = FIELDS.associate { (label, name) -> name to label }

    const val COVER = "covr"

    /** The `data` atom's well-known type numbers, which decide how a value is encoded. */
    private const val TYPE_BINARY = 0
    private const val TYPE_TEXT = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    /** `meta` carries four bytes of version and flags before its children; `udta` does not. */
    private val ILST_PATH = listOf("udta", "meta", "ilst")

    fun nameFor(label: String): String? = BY_LABEL[label.trim().lowercase()]

    fun labelFor(name: String): String? = BY_NAME[name]

    // ── atoms ─────────────────────────────────────────────────────────────────────────────

    data class Atom(val type: String, val at: Int, val headerLen: Int, val size: Int) {
        val contentAt: Int get() = at + headerLen
        val contentLen: Int get() = size - headerLen
        val end: Int get() = at + size
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun putU32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(b: ByteArray, at: Int, v: Long) {
        b[at] = ((v ushr 24) and 0xFF).toByte()
        b[at + 1] = ((v ushr 16) and 0xFF).toByte()
        b[at + 2] = ((v ushr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
    }

    /**
     * The atoms directly inside the byte range [from, to).
     *
     * A size of 1 means the real 64-bit size follows the type, and a size of 0 means "to the end
     * of the file" - both legal, and both a parser that assumes a plain 32-bit size gets wrong.
     */
    fun atoms(b: ByteArray, from: Int, to: Int): List<Atom> {
        val out = ArrayList<Atom>()
        var at = from
        while (at + 8 <= to) {
            val declared = u32(b, at)
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (at + 16 > to) break
                size = u64(b, at + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (to - at).toLong()
            }
            if (size < headerLen || at + size > to) break
            out.add(Atom(type, at, headerLen, size.toInt()))
            at += size.toInt()
        }
        return out
    }

    fun isMp4(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val top = atoms(b, 0, b.size)
        if (top.isEmpty()) return false
        // `ftyp` first is the normal shape; a QuickTime file can lead with `moov` or `wide`, so
        // the test is that one of the two structural atoms is actually there.
        return top.any { it.type == "ftyp" } && top.any { it.type == "moov" || it.type == "mdat" }
    }

    /** Whether this file's offsets live anywhere this code cannot reach. */
    fun isFragmented(b: ByteArray): Boolean =
        atoms(b, 0, b.size).any { it.type == "moof" || it.type == "sidx" || it.type == "mfra" }

    private fun child(b: ByteArray, parent: Atom, type: String): Atom? =
        atoms(b, parent.contentAt + extraFor(b, parent), parent.end).firstOrNull { it.type == type }

    /**
     * How many bytes of version and flags sit before a container's children.
     *
     * `meta` is a full box in the ISO base media format and a plain container in QuickTime, and
     * files of both kinds are called `.m4a`. Rather than guess from the extension, this looks at
     * whether the first four bytes read as a plausible child atom header.
     */
    private fun extraFor(b: ByteArray, atom: Atom): Int {
        if (atom.type != "meta") return 0
        val c = atom.contentAt
        if (c + 8 > atom.end) return 0
        val size = u32(b, c)
        val type = String(b, c + 4, 4, Charsets.ISO_8859_1)
        val looksLikeAtom = size >= 8 && c + size <= atom.end &&
            type.all { it.code in 0x20..0x7E }
        return if (looksLikeAtom) 0 else 4
    }

    private fun ilst(b: ByteArray): Atom? {
        var here = atoms(b, 0, b.size).firstOrNull { it.type == "moov" } ?: return null
        for (step in ILST_PATH) here = child(b, here, step) ?: return null
        return here
    }

    // ── reading ───────────────────────────────────────────────────────────────────────────

    /** One item's value, decoded according to its `data` type. */
    private fun valueOf(b: ByteArray, item: Atom): String? {
        val data = atoms(b, item.contentAt, item.end).firstOrNull { it.type == "data" } ?: return null
        if (data.contentLen < 8) return null
        val kind = (u32(b, data.contentAt) and 0xFFFFFF).toInt()
        val at = data.contentAt + 8
        val len = data.end - at
        if (len <= 0) return null
        return when {
            item.type == "trkn" || item.type == "disk" -> {
                // Two 16-bit numbers after a leading pad: the position and the total. Shown the
                // way it is written on a sleeve rather than as four raw bytes.
                if (len < 6) return null
                val n = ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
                val of = ((b[at + 4].toInt() and 0xFF) shl 8) or (b[at + 5].toInt() and 0xFF)
                if (n == 0) null else if (of > 0) "$n/$of" else "$n"
            }
            kind == TYPE_TEXT -> String(b, at, len, Charsets.UTF_8).trimEnd('\u0000')
            kind == TYPE_BINARY && len <= 4 -> {
                var v = 0L
                for (i in 0 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
                v.toString()
            }
            else -> null
        }
    }

    fun read(b: ByteArray): List<Pair<String, String>> {
        val list = ilst(b) ?: return emptyList()
        return atoms(b, list.contentAt, list.end).mapNotNull { item ->
            val label = labelFor(item.type) ?: return@mapNotNull null
            val value = valueOf(b, item) ?: return@mapNotNull null
            if (value.isBlank()) null else label to value
        }
    }

    fun picture(b: ByteArray): Cover? {
        val list = ilst(b) ?: return null
        val item = atoms(b, list.contentAt, list.end).firstOrNull { it.type == COVER } ?: return null
        val data = atoms(b, item.contentAt, item.end).firstOrNull { it.type == "data" } ?: return null
        if (data.contentLen <= 8) return null
        val bytes = b.copyOfRange(data.contentAt + 8, data.end)
        // The atom records 13 for JPEG and 14 for PNG, and gets it wrong often enough that the
        // bytes decide. Cover.of sniffs them.
        return Cover.of(bytes)
    }

    // ── writing ───────────────────────────────────────────────────────────────────────────

    private fun atom(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        putU32(out, payload.size + 8)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun dataAtom(kind: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        putU32(out, kind)
        putU32(out, 0) // locale, which iTunes writes as zero and nothing reads
        out.write(value)
        return atom("data", out.toByteArray())
    }

    private fun itemFor(name: String, value: String): ByteArray? {
        if (name == "trkn" || name == "disk") {
            val parts = value.split('/', ' ').filter { it.isNotBlank() }
            val n = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val of = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val raw = byteArrayOf(
                0, 0, ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte(),
                ((of shr 8) and 0xFF).toByte(), (of and 0xFF).toByte(), 0, 0,
            )
            return atom(name, dataAtom(TYPE_BINARY, raw))
        }
        return atom(name, dataAtom(TYPE_TEXT, value.toByteArray(Charsets.UTF_8)))
    }

    private fun coverItem(cover: Cover): ByteArray {
        val kind = if (Cover.sniff(cover.bytes) == "image/png") TYPE_PNG else TYPE_JPEG
        return atom(COVER, dataAtom(kind, cover.bytes))
    }

    /** The handler a `meta` atom needs for iTunes-style tags to be looked for inside it. */
    private fun hdlr(): ByteArray {
        val out = ByteArrayOutputStream()
        putU32(out, 0) // version and flags
        putU32(out, 0) // predefined
        out.write("mdir".toByteArray(Charsets.ISO_8859_1))
        out.write("appl".toByteArray(Charsets.ISO_8859_1))
        putU32(out, 0)
        putU32(out, 0)
        out.write(0) // an empty name, counted in the length
        return atom("hdlr", out.toByteArray())
    }

    /**
     * Replace or create the atom at [path] inside a container's bytes.
     *
     * Recursive because the chain `moov/udta/meta/ilst` may be missing at any link: an untagged
     * file straight out of an encoder has no `udta` at all, and a writer that only edits an
     * existing chain can never add the first tag to a file.
     */
    private fun splice(container: ByteArray, path: List<String>, payload: ByteArray): ByteArray? {
        val self = atoms(container, 0, container.size).singleOrNull() ?: return null
        val extra = extraFor(container, self)
        val head = path.first()
        val kids = atoms(container, self.contentAt + extra, self.end)
        val target = kids.firstOrNull { it.type == head }

        val replacement: ByteArray = if (path.size == 1) {
            atom(head, payload)
        } else {
            val inner = if (target != null) {
                container.copyOfRange(target.at, target.end)
            } else {
                // A `meta` created here needs its version/flags word and its handler, or players
                // skip the tags it holds without complaint.
                if (head == "meta") atom(head, byteArrayOf(0, 0, 0, 0) + hdlr()) else atom(head, ByteArray(0))
            }
            splice(inner, path.drop(1), payload) ?: return null
        }

        val out = ByteArrayOutputStream(container.size + replacement.size)
        out.write(container, self.contentAt, extra)
        var wrote = false
        for (k in kids) {
            if (k.type == head) {
                out.write(replacement)
                wrote = true
            } else {
                out.write(container, k.at, k.size)
            }
        }
        if (!wrote) out.write(replacement)
        return atom(self.type, out.toByteArray())
    }

    /** Every `stco` and `co64` table inside these bytes, as absolute positions within them. */
    private fun offsetTables(b: ByteArray, from: Int, to: Int, into: MutableList<Atom>) {
        for (a in atoms(b, from, to)) {
            when (a.type) {
                "stco", "co64" -> into.add(a)
                // Only the containers on the way down, so a `mdat` copied into the search range
                // is never walked as if it were structure.
                "moov", "trak", "mdia", "minf", "stbl", "edts", "udta" ->
                    offsetTables(b, a.contentAt, a.end, into)
            }
        }
    }

    /**
     * Shift every chunk offset that moved.
     *
     * The comparison is the whole point: an offset below where `moov` used to start points at data
     * in front of it, which did not move. Adding the delta to those as well is how a tagger breaks
     * a file whose `mdat` comes first - which is most files recorded on a phone.
     */
    private fun shiftOffsets(moov: ByteArray, moovAt: Int, delta: Int) {
        if (delta == 0) return
        val tables = ArrayList<Atom>()
        offsetTables(moov, 0, moov.size, tables)
        for (t in tables) {
            if (t.contentLen < 8) continue
            val count = u32(moov, t.contentAt + 4).toInt()
            val wide = t.type == "co64"
            val stride = if (wide) 8 else 4
            var at = t.contentAt + 8
            var i = 0
            while (i < count && at + stride <= t.end) {
                val v = if (wide) u64(moov, at) else u32(moov, at)
                if (v >= moovAt) {
                    if (wide) writeU64(moov, at, v + delta) else writeU32(moov, at, v + delta)
                }
                at += stride
                i++
            }
        }
    }

    /** Rebuild the file with this `ilst` content, fixing up every offset the change moved. */
    private fun withIlst(original: ByteArray, items: List<ByteArray>): ByteArray? {
        if (!isMp4(original) || isFragmented(original)) return null
        val moov = atoms(original, 0, original.size).firstOrNull { it.type == "moov" } ?: return null
        val payload = ByteArrayOutputStream()
        for (i in items) payload.write(i)
        val newMoov = splice(original.copyOfRange(moov.at, moov.end), ILST_PATH, payload.toByteArray())
            ?: return null
        shiftOffsets(newMoov, moov.at, newMoov.size - moov.size)
        val out = ByteArrayOutputStream(original.size + newMoov.size)
        out.write(original, 0, moov.at)
        out.write(newMoov)
        out.write(original, moov.end, original.size - moov.end)
        return out.toByteArray()
    }

    /** The items currently in the file, as raw atom bytes, with [drop] left out. */
    private fun existingItems(b: ByteArray, drop: String): List<ByteArray> {
        val list = ilst(b) ?: return emptyList()
        return atoms(b, list.contentAt, list.end)
            .filterNot { it.type == drop }
            .map { b.copyOfRange(it.at, it.end) }
    }

    fun put(original: ByteArray, label: String, value: String): ByteArray? {
        val name = nameFor(label) ?: return null
        val kept = existingItems(original, name)
        val next = if (value.isBlank()) kept else kept + (itemFor(name, value) ?: return null)
        return withIlst(original, next)
    }

    fun putPicture(original: ByteArray, cover: Cover?): ByteArray? {
        val kept = existingItems(original, COVER)
        return withIlst(original, if (cover == null) kept else kept + coverItem(cover))
    }
}
