package dev.niccc2007.filet.metadata

/**
 * ID3v2 tags on an MP3: the text frames, and the attached picture.
 *
 * ## Why this exists
 *
 * `MetadataSupport` has advertised MP3 as writable, with an attached picture, for several rounds -
 * and `MetadataStore` had no MP3 code at all, so every MP3 fell through to the refusal that says
 * the contents do not match the extension. The table was making a promise nothing kept, which is
 * worse than not listing the format: it is a claim a reader checks by trying it.
 *
 * ## What it handles, and what it deliberately does not
 *
 * **v2.3 and v2.4 only.** v2.2 used three-character frame ids and a different header; it is rare
 * enough that handling it badly is worse than reading it and saying so.
 *
 * **Unsynchronisation is read, never written.** The scheme exists so a decoder cannot mistake tag
 * bytes for a frame sync, and nothing modern needs it; writing a plain tag is correct and safer
 * than producing one this code would have to unpick again.
 *
 * **A v1 tag at the end is left exactly where it is.** It is 128 bytes after the audio, it is not
 * what any modern player reads, and removing it would change the file for no benefit.
 *
 * **The audio is never touched.** Everything here rebuilds the tag in front of it and copies the
 * rest through byte for byte.
 */
object Id3 {

    /** The frame ids worth showing by name, in the order a reader expects them. */
    val FIELDS: List<Pair<String, String>> = listOf(
        "TIT2" to "Title",
        "TPE1" to "Artist",
        "TALB" to "Album",
        "TDRC" to "Date",
        "TYER" to "Year",
        "TRCK" to "Track",
        "TCON" to "Genre",
        "COMM" to "Comment",
    )

    private val BY_LABEL = FIELDS.associate { (id, label) -> label.lowercase() to id }

    fun frameFor(label: String): String? = BY_LABEL[label.trim().lowercase()]

    fun labelFor(frame: String): String? = FIELDS.firstOrNull { it.first == frame }?.second

    fun isMp3(b: ByteArray): Boolean = hasTag(b) || startsWithFrameSync(b)

    /** Whether this begins with an ID3v2 tag, which is what a verify pass needs to know. */
    fun hasId3(b: ByteArray): Boolean = hasTag(b)

    private fun hasTag(b: ByteArray): Boolean =
        b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() &&
            b[2] == '3'.code.toByte() && (b[3].toInt() and 0xFF) in 3..4

    private fun startsWithFrameSync(b: ByteArray): Boolean =
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0

    /**
     * A synchsafe integer: seven bits per byte, top bit always clear.
     *
     * The whole reason a tag's size cannot simply be read as a big-endian int, and the single
     * most common way to corrupt an MP3 while editing one.
     */
    fun readSynchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or
            ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or
            (b[at + 3].toInt() and 0x7F)

    fun writeSynchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun writeInt(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** Where the audio starts: past the tag if there is one, otherwise at the beginning. */
    fun audioStart(b: ByteArray): Int {
        if (!hasTag(b)) return 0
        val size = readSynchsafe(b, 6)
        val footer = if ((b[5].toInt() and 0x10) != 0) 10 else 0
        return (10 + size + footer).coerceAtMost(b.size)
    }

    /** Every frame in the tag, as id to payload. Frames are returned in file order. */
    fun frames(b: ByteArray): List<Pair<String, ByteArray>> {
        if (!hasTag(b)) return emptyList()
        val version = b[3].toInt() and 0xFF
        val end = audioStart(b)
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = 10
        while (at + 10 <= end) {
            val id = String(b, at, 4, Charsets.ISO_8859_1)
            // Padding: the tag is allowed to end in zeros, and reading them as a frame id is how
            // a parser invents garbage frames at the end of every file.
            if (id[0] == '\u0000') break
            if (!id.all { it.isLetterOrDigit() }) break
            // v2.4 sizes are synchsafe; v2.3 sizes are a plain integer. Reading one as the other
            // works for small frames and silently truncates a large one - which a cover image
            // always is.
            val size = if (version >= 4) readSynchsafe(b, at + 4) else readInt(b, at + 4)
            if (size < 0 || at + 10 + size > end) break
            out.add(id to b.copyOfRange(at + 10, at + 10 + size))
            at += 10 + size
        }
        return out
    }

    /** Decode a text frame's payload, honouring its encoding byte. */
    fun textOf(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val body = payload.copyOfRange(1, payload.size)
        val text = when (payload[0].toInt()) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> String(body, Charsets.UTF_16)
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }
        // Frames are allowed to be null-terminated and a trailing NUL rendered in a text field
        // looks like a corrupt value.
        return text.trimEnd('\u0000')
    }

    /** A text frame's payload, always written as UTF-8 with the matching encoding byte. */
    fun textFrame(value: String): ByteArray =
        byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)

    /** Every named field the tag carries, by label. */
    fun read(b: ByteArray): List<Pair<String, String>> =
        frames(b).mapNotNull { (id, payload) ->
            val label = labelFor(id) ?: return@mapNotNull null
            // COMM carries a language and a short description before the text; everything else
            // is the text straight after the encoding byte.
            val text = if (id == "COMM" && payload.size > 4) {
                val rest = payload.copyOfRange(4, payload.size)
                textOf(byteArrayOf(payload[0]) + rest.dropWhile { it != 0.toByte() }
                    .drop(1).toByteArray())
            } else {
                textOf(payload)
            }
            if (text.isBlank()) null else label to text
        }

    /**
     * The attached picture, if there is one.
     *
     * Front cover preferred, then whatever is there: a file with only a back cover should still
     * show something rather than claiming to have no picture at all.
     */
    fun picture(b: ByteArray): Cover? {
        val apics = frames(b).filter { it.first == "APIC" }
        if (apics.isEmpty()) return null
        val chosen = apics.firstOrNull { parseApic(it.second)?.kind == 3 } ?: apics.first()
        return parseApic(chosen.second)
    }

    private fun parseApic(payload: ByteArray): Cover? {
        if (payload.size < 4) return null
        val encoding = payload[0].toInt()
        var at = 1
        val mimeEnd = indexOfZero(payload, at) ?: return null
        val mime = String(payload, at, mimeEnd - at, Charsets.ISO_8859_1)
        at = mimeEnd + 1
        if (at >= payload.size) return null
        val kind = payload[at].toInt() and 0xFF
        at += 1
        // The description's terminator is one NUL for the single-byte encodings and two for the
        // UTF-16 ones. Getting this wrong shifts the image data by a byte, which is a picture
        // that decodes to nothing.
        val wide = encoding == 1 || encoding == 2
        val descEnd = if (wide) indexOfDoubleZero(payload, at) else indexOfZero(payload, at)
        if (descEnd == null) return null
        val description = if (wide) {
            String(payload, at, descEnd - at, Charsets.UTF_16)
        } else {
            String(payload, at, descEnd - at, Charsets.ISO_8859_1)
        }
        val start = descEnd + if (wide) 2 else 1
        if (start > payload.size) return null
        return Cover(mime, kind, description, payload.copyOfRange(start, payload.size))
    }

    private fun indexOfZero(b: ByteArray, from: Int): Int? {
        var i = from
        while (i < b.size) {
            if (b[i] == 0.toByte()) return i
            i++
        }
        return null
    }

    private fun indexOfDoubleZero(b: ByteArray, from: Int): Int? {
        var i = from
        while (i + 1 < b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
            i += 2
        }
        return null
    }

    /** Build an APIC payload. Written with the single-byte encoding, so the terminator is one NUL. */
    fun apicFrame(picture: Cover): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0)
        out.write(Cover.mimeFor(picture.mime, picture.bytes).toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(picture.kind)
        out.write(picture.description.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(picture.bytes)
        return out.toByteArray()
    }

    /**
     * Rebuild the file with these frames in front of the untouched audio.
     *
     * Always written as **v2.3 with plain frame sizes**, whatever the input was. One output
     * format is one thing to get right, every player reads v2.3, and a file that came in as v2.4
     * loses nothing that this code preserves.
     */
    fun rebuild(original: ByteArray, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for ((id, payload) in frames) {
            if (id.length != 4) continue
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(writeInt(payload.size))
            body.write(0)
            body.write(0)
            body.write(payload)
        }
        val tagBody = body.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(3)
        out.write(0)
        out.write(0)
        out.write(writeSynchsafe(tagBody.size))
        out.write(tagBody)
        out.write(original, audioStart(original), original.size - audioStart(original))
        return out.toByteArray()
    }

    /** Set or clear one named field, leaving every other frame and the audio alone. */
    fun put(original: ByteArray, label: String, value: String): ByteArray? {
        val frame = frameFor(label) ?: return null
        val kept = frames(original).filterNot { it.first == frame }
        val next = if (value.isBlank()) kept else kept + (frame to textFrame(value))
        return rebuild(original, next)
    }

    /** Replace the attached picture, or remove it when given null. */
    fun putPicture(original: ByteArray, picture: Cover?): ByteArray {
        val kept = frames(original).filterNot { it.first == "APIC" }
        val next = if (picture == null) kept else kept + ("APIC" to apicFrame(picture))
        return rebuild(original, next)
    }
}
