package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream

/**
 * Vorbis comments: the tag format shared by FLAC, Ogg Vorbis and Opus.
 *
 * ## Why these three are one file
 *
 * The *tag* is identical in all three - a vendor string and a list of `KEY=value` lines. Only the
 * wrapper differs, and the wrapper is the entire difficulty:
 *
 *  - **FLAC** keeps it in a length-prefixed metadata block at the front of the file. Resizing that
 *    block moves the audio, and nothing in a FLAC file cares: SEEKTABLE offsets are measured from
 *    the first audio frame, not from the start of the file. So FLAC is genuinely safe to rewrite,
 *    which is why the support table now says so.
 *  - **Ogg** keeps it in the second packet of a page stream. Every page carries its own CRC and a
 *    sequence number, so changing the tag's length means re-paginating from that point and
 *    recomputing the checksum of every page after it. That is the work the old caveat called off
 *    as too hard, and it is a hundred lines, not a research project.
 *
 * ## What is refused, and why that is the honest answer
 *
 * A page stream whose last header packet shares a page with the first audio packet is refused
 * rather than rebuilt. Nothing in the wild writes one - both reference encoders end the headers on
 * a page boundary - but a file that did would lose the start of its audio, and losing audio to
 * save a tag is not a trade this is allowed to make silently.
 *
 * FLAC inside Ogg (the `\u007fFLAC` mapping) is read but not written: it is a third wrapper around
 * the same tag, used by almost nothing, and guessing at it is how a format gets a wrong tier.
 */
object VorbisComment {

    /** Display label to Vorbis field name. The names are conventional, not specified. */
    val FIELDS: List<Pair<String, String>> = listOf(
        "Title" to "TITLE",
        "Artist" to "ARTIST",
        "Album" to "ALBUM",
        "Album artist" to "ALBUMARTIST",
        "Date" to "DATE",
        "Track" to "TRACKNUMBER",
        "Genre" to "GENRE",
        "Comment" to "DESCRIPTION",
    )

    private val BY_LABEL = FIELDS.associate { (label, name) -> label.lowercase() to name }
    private val BY_NAME = FIELDS.associate { (label, name) -> name to label }

    /** The field that carries an attached picture, base64'd. */
    const val PICTURE_FIELD = "METADATA_BLOCK_PICTURE"

    fun nameFor(label: String): String? = BY_LABEL[label.trim().lowercase()]

    /**
     * The label for a field name, or the name itself.
     *
     * An unknown field is shown under its own name rather than hidden: Vorbis comments are
     * arbitrary by specification, and a reader that only shows the eight it has heard of is
     * hiding most of what a ripper writes.
     */
    fun labelFor(name: String): String = BY_NAME[name.uppercase()] ?: name

    // ── the tag itself ────────────────────────────────────────────────────────────────────

    data class Tag(val vendor: String, val fields: List<Pair<String, String>>)

    private fun u32le(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun putU32le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    /**
     * Parse a comment payload: vendor, then the `KEY=value` list.
     *
     * Lengths here are **little-endian**, which they are nowhere else in FLAC. Reading them
     * big-endian gives a vendor string several gigabytes long, which is the first thing to check
     * when a FLAC parser appears to be reading garbage.
     */
    fun parse(payload: ByteArray): Tag {
        if (payload.size < 8) return Tag("", emptyList())
        var at = 0
        val vendorLen = u32le(payload, at)
        at += 4
        if (vendorLen < 0 || at + vendorLen > payload.size) return Tag("", emptyList())
        val vendor = String(payload, at, vendorLen, Charsets.UTF_8)
        at += vendorLen
        if (at + 4 > payload.size) return Tag(vendor, emptyList())
        val count = u32le(payload, at)
        at += 4
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < count && at + 4 <= payload.size) {
            val len = u32le(payload, at)
            at += 4
            if (len < 0 || at + len > payload.size) break
            val line = String(payload, at, len, Charsets.UTF_8)
            at += len
            i++
            val eq = line.indexOf('=')
            if (eq > 0) out.add(line.substring(0, eq).uppercase() to line.substring(eq + 1))
        }
        return Tag(vendor, out)
    }

    fun build(tag: Tag): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = tag.vendor.toByteArray(Charsets.UTF_8)
        putU32le(out, vendor.size)
        out.write(vendor)
        putU32le(out, tag.fields.size)
        for ((name, value) in tag.fields) {
            val line = (name.uppercase() + "=" + value).toByteArray(Charsets.UTF_8)
            putU32le(out, line.size)
            out.write(line)
        }
        return out.toByteArray()
    }

    /** Set or clear one field in a tag, by its Vorbis field name. */
    fun withField(tag: Tag, name: String, value: String): Tag {
        val kept = tag.fields.filterNot { it.first.equals(name, ignoreCase = true) }
        return tag.copy(fields = if (value.isBlank()) kept else kept + (name.uppercase() to value))
    }

    /** Everything worth showing, by label, with the base64 picture field left out. */
    fun readable(tag: Tag): List<Pair<String, String>> =
        tag.fields
            .filterNot { it.first.equals(PICTURE_FIELD, ignoreCase = true) }
            .filter { it.second.isNotBlank() }
            .map { labelFor(it.first) to it.second }

    // ── the FLAC picture block, which Ogg also uses base64'd ──────────────────────────────

    private fun u32be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun putU32be(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    fun parsePictureBlock(p: ByteArray): Cover? {
        if (p.size < 32) return null
        var at = 0
        val kind = u32be(p, at); at += 4
        val mimeLen = u32be(p, at); at += 4
        if (mimeLen < 0 || at + mimeLen > p.size) return null
        val mime = String(p, at, mimeLen, Charsets.ISO_8859_1); at += mimeLen
        if (at + 4 > p.size) return null
        val descLen = u32be(p, at); at += 4
        if (descLen < 0 || at + descLen > p.size) return null
        val desc = String(p, at, descLen, Charsets.UTF_8); at += descLen
        // width, height, depth, colours - read past, not used: the picture is handed on as bytes
        // and whatever decodes it knows its own dimensions better than a tag does.
        at += 16
        if (at + 4 > p.size) return null
        val dataLen = u32be(p, at); at += 4
        if (dataLen < 0 || at + dataLen > p.size) return null
        return Cover(mime, kind, desc, p.copyOfRange(at, at + dataLen))
    }

    fun buildPictureBlock(cover: Cover): ByteArray {
        val out = ByteArrayOutputStream()
        putU32be(out, cover.kind)
        val mime = Cover.mimeFor(cover.mime, cover.bytes).toByteArray(Charsets.ISO_8859_1)
        putU32be(out, mime.size)
        out.write(mime)
        val desc = cover.description.toByteArray(Charsets.UTF_8)
        putU32be(out, desc.size)
        out.write(desc)
        // Width, height, colour depth and indexed-colour count, all recorded as unknown. The
        // alternative is decoding the image to fill them in, and every player reads the real
        // numbers out of the image rather than trusting these.
        repeat(4) { putU32be(out, 0) }
        putU32be(out, cover.bytes.size)
        out.write(cover.bytes)
        return out.toByteArray()
    }

    // ── FLAC ──────────────────────────────────────────────────────────────────────────────

    const val STREAMINFO = 0
    const val PADDING = 1
    const val VORBIS_COMMENT = 4
    const val PICTURE = 6

    data class Block(val type: Int, val last: Boolean, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Block && type == other.type && last == other.last &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = (type * 31 + last.hashCode()) * 31 + payload.contentHashCode()
    }

    fun isFlac(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    fun flacBlocks(b: ByteArray): List<Block> {
        if (!isFlac(b)) return emptyList()
        val out = ArrayList<Block>()
        var at = 4
        while (at + 4 <= b.size) {
            val head = b[at].toInt() and 0xFF
            val last = (head and 0x80) != 0
            val type = head and 0x7F
            val len = ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
            if (at + 4 + len > b.size) break
            out.add(Block(type, last, b.copyOfRange(at + 4, at + 4 + len)))
            at += 4 + len
            if (last) break
        }
        return out
    }

    /** Where the audio frames begin: past the last metadata block. */
    fun flacAudioStart(b: ByteArray): Int {
        if (!isFlac(b)) return 0
        var at = 4
        while (at + 4 <= b.size) {
            val head = b[at].toInt() and 0xFF
            val len = ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
            at += 4 + len
            if ((head and 0x80) != 0) break
        }
        return at.coerceAtMost(b.size)
    }

    private fun flacTag(b: ByteArray): Tag =
        flacBlocks(b).firstOrNull { it.type == VORBIS_COMMENT }
            ?.let { parse(it.payload) } ?: Tag("", emptyList())

    fun readFlac(b: ByteArray): List<Pair<String, String>> = readable(flacTag(b))

    /**
     * Rebuild a FLAC with these metadata blocks in front of its untouched audio.
     *
     * STREAMINFO is forced back to the front because the specification requires it there and a
     * file with it anywhere else will not decode. PADDING is dropped: it exists so a tagger can
     * edit in place, and this one never does.
     */
    fun rebuildFlac(original: ByteArray, blocks: List<Block>): ByteArray? {
        if (!isFlac(original)) return null
        val ordered = blocks.filter { it.type != PADDING }
            .sortedBy { if (it.type == STREAMINFO) 0 else 1 }
        if (ordered.isEmpty() || ordered.first().type != STREAMINFO) return null
        val out = ByteArrayOutputStream(original.size + 1024)
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        for ((i, blk) in ordered.withIndex()) {
            val last = i == ordered.size - 1
            out.write((blk.type and 0x7F) or if (last) 0x80 else 0)
            out.write((blk.payload.size ushr 16) and 0xFF)
            out.write((blk.payload.size ushr 8) and 0xFF)
            out.write(blk.payload.size and 0xFF)
            out.write(blk.payload)
        }
        val audio = flacAudioStart(original)
        out.write(original, audio, original.size - audio)
        return out.toByteArray()
    }

    private fun withFlacTag(original: ByteArray, tag: Tag): ByteArray? {
        val blocks = flacBlocks(original)
        if (blocks.isEmpty()) return null
        val rest = blocks.filterNot { it.type == VORBIS_COMMENT }
        return rebuildFlac(original, rest + Block(VORBIS_COMMENT, false, build(tag)))
    }

    fun putFlac(original: ByteArray, label: String, value: String): ByteArray? {
        val name = nameFor(label) ?: label.uppercase().takeIf { isValidName(it) } ?: return null
        return withFlacTag(original, withField(flacTag(original), name, value))
    }

    fun flacPicture(b: ByteArray): Cover? {
        val blocks = flacBlocks(b).filter { it.type == PICTURE }
        val chosen = blocks.firstOrNull { parsePictureBlock(it.payload)?.kind == Cover.FRONT }
            ?: blocks.firstOrNull()
            // A FLAC written by a tagger that only knew Ogg can carry the base64 field instead.
            ?: return flacTag(b).fields
                .firstOrNull { it.first.equals(PICTURE_FIELD, ignoreCase = true) }
                ?.let { decodePictureField(it.second) }
        return parsePictureBlock(chosen.payload)
    }

    fun putFlacPicture(original: ByteArray, cover: Cover?): ByteArray? {
        val blocks = flacBlocks(original)
        if (blocks.isEmpty()) return null
        val kept = blocks.filterNot { it.type == PICTURE }
        // Also strip the base64 form, or a file with both would show the old picture after the
        // new one was written.
        val tag = flacTag(original)
        val cleaned = tag.copy(
            fields = tag.fields.filterNot { it.first.equals(PICTURE_FIELD, ignoreCase = true) },
        )
        val withoutComment = kept.filterNot { it.type == VORBIS_COMMENT }
        val next = withoutComment + Block(VORBIS_COMMENT, false, build(cleaned)) +
            (if (cover == null) emptyList() else listOf(Block(PICTURE, false, buildPictureBlock(cover))))
        return rebuildFlac(original, next)
    }

    /**
     * Is this a legal Vorbis field name?
     *
     * Printable ASCII excluding `=`, per the specification. Checked because the name goes into a
     * `KEY=value` line unescaped, so a name containing `=` would be read back as a different
     * field with a different value - a silent corruption of the tag rather than a failure.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.code in 0x20..0x7D && it != '=' }

    // ── Ogg ───────────────────────────────────────────────────────────────────────────────

    private const val OGGS = "OggS"

    /**
     * The Ogg page checksum: a plain CRC-32 with polynomial 0x04c11db7, but **no reflection, no
     * initial value and no final inversion** - unlike the CRC-32 in zip, PNG and everything else.
     * Using the familiar one produces a file every player rejects with no explanation.
     */
    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r < 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    fun pageCrc(b: ByteArray, from: Int, len: Int): Int {
        var r = 0
        for (i in from until from + len) {
            r = (r shl 8) xor CRC_TABLE[((r ushr 24) xor (b[i].toInt() and 0xFF)) and 0xFF]
        }
        return r
    }

    data class Page(
        val at: Int,
        val headerLen: Int,
        val bodyLen: Int,
        val serial: Int,
        val seq: Int,
        val granule: Long,
        val flags: Int,
        val lacing: IntArray,
    ) {
        val end: Int get() = at + headerLen + bodyLen
        val bodyAt: Int get() = at + headerLen

        override fun equals(other: Any?): Boolean = other is Page && at == other.at && end == other.end
        override fun hashCode(): Int = at * 31 + end
    }

    fun isOgg(b: ByteArray): Boolean =
        b.size >= 27 && String(b, 0, 4, Charsets.ISO_8859_1) == OGGS

    fun pages(b: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var at = 0
        while (at + 27 <= b.size) {
            if (String(b, at, 4, Charsets.ISO_8859_1) != OGGS) break
            val segments = b[at + 26].toInt() and 0xFF
            val headerLen = 27 + segments
            if (at + headerLen > b.size) break
            val lacing = IntArray(segments) { b[at + 27 + it].toInt() and 0xFF }
            val bodyLen = lacing.sum()
            if (at + headerLen + bodyLen > b.size) break
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (b[at + 6 + i].toLong() and 0xFF)
            out.add(
                Page(
                    at = at,
                    headerLen = headerLen,
                    bodyLen = bodyLen,
                    serial = u32le(b, at + 14),
                    seq = u32le(b, at + 18),
                    granule = granule,
                    flags = b[at + 5].toInt() and 0xFF,
                    lacing = lacing,
                ),
            )
            at += headerLen + bodyLen
        }
        return out
    }

    /** One assembled packet, and the page its last segment sat on. */
    private data class Packet(val bytes: ByteArray, val lastPage: Int, val endsPage: Boolean)

    /**
     * Assemble up to [want] packets from the first logical stream.
     *
     * Only the first stream: a chained or multiplexed Ogg has several, and tagging one of them
     * means understanding which, so those are refused further down rather than half-written.
     */
    private fun packets(b: ByteArray, pages: List<Page>, serial: Int, want: Int): List<Packet> {
        val out = ArrayList<Packet>()
        val buf = ByteArrayOutputStream()
        for ((pi, p) in pages.withIndex()) {
            if (p.serial != serial) continue
            var bodyAt = p.bodyAt
            for ((li, len) in p.lacing.withIndex()) {
                buf.write(b, bodyAt, len)
                bodyAt += len
                if (len < 255) {
                    out.add(Packet(buf.toByteArray(), pi, li == p.lacing.size - 1))
                    buf.reset()
                    if (out.size >= want) return out
                }
            }
        }
        return out
    }

    private fun isVorbis(p: ByteArray): Boolean =
        p.size >= 7 && p[0] == 1.toByte() && String(p, 1, 6, Charsets.ISO_8859_1) == "vorbis"

    private fun isOpus(p: ByteArray): Boolean =
        p.size >= 8 && String(p, 0, 8, Charsets.ISO_8859_1) == "OpusHead"

    /** How many header packets this stream has, or null if the codec is not one of the two. */
    private fun headerCount(first: ByteArray): Int? = when {
        isVorbis(first) -> 3
        isOpus(first) -> 2
        else -> null
    }

    private fun commentPayloadOf(codecFirst: ByteArray, packet: ByteArray): ByteArray? = when {
        isVorbis(codecFirst) ->
            if (packet.size >= 7 && packet[0] == 3.toByte()) {
                // Drop the signature; the trailing framing byte is part of what parse() ignores.
                packet.copyOfRange(7, packet.size)
            } else {
                null
            }
        isOpus(codecFirst) ->
            if (packet.size >= 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags") {
                packet.copyOfRange(8, packet.size)
            } else {
                null
            }
        else -> null
    }

    private fun commentPacket(codecFirst: ByteArray, payload: ByteArray): ByteArray = when {
        isVorbis(codecFirst) ->
            byteArrayOf(3) + "vorbis".toByteArray(Charsets.ISO_8859_1) + payload + byteArrayOf(1)
        else -> "OpusTags".toByteArray(Charsets.ISO_8859_1) + payload
    }

    fun oggTag(b: ByteArray): Tag {
        if (!isOgg(b)) return Tag("", emptyList())
        val pages = pages(b)
        val serial = pages.firstOrNull()?.serial ?: return Tag("", emptyList())
        val packets = packets(b, pages, serial, 2)
        if (packets.size < 2) return Tag("", emptyList())
        val payload = commentPayloadOf(packets[0].bytes, packets[1].bytes) ?: return Tag("", emptyList())
        return parse(payload)
    }

    fun readOgg(b: ByteArray): List<Pair<String, String>> = readable(oggTag(b))

    fun oggPicture(b: ByteArray): Cover? =
        oggTag(b).fields.firstOrNull { it.first.equals(PICTURE_FIELD, ignoreCase = true) }
            ?.let { decodePictureField(it.second) }

    private fun decodePictureField(value: String): Cover? = runCatching {
        parsePictureBlock(java.util.Base64.getMimeDecoder().decode(value))
    }.getOrNull()

    private fun encodePictureField(cover: Cover): String =
        java.util.Base64.getEncoder().encodeToString(buildPictureBlock(cover))

    /** Replace the whole tag of an Ogg stream, repaginating from the comment packet. */
    fun withOggTag(original: ByteArray, tag: Tag): ByteArray? {
        if (!isOgg(original)) return null
        val pages = pages(original)
        if (pages.isEmpty()) return null
        val serial = pages[0].serial
        // A multiplexed or chained stream has more than one serial number, and choosing which to
        // tag is a decision this cannot make for somebody.
        if (pages.any { it.serial != serial }) return null
        val first = packets(original, pages, serial, 1).firstOrNull() ?: return null
        val want = headerCount(first.bytes) ?: return null
        val headers = packets(original, pages, serial, want)
        if (headers.size < want) return null
        val last = headers[want - 1]
        // The refusal this file exists to be careful about: if the last header packet does not
        // end its page, that page also holds the start of the audio, and copying the pages after
        // it would drop that audio.
        if (!last.endsPage) return null
        if (commentPayloadOf(first.bytes, headers[1].bytes) == null) return null

        val newComment = commentPacket(first.bytes, build(tag))

        val out = ByteArrayOutputStream(original.size + newComment.size)
        // Packet 0 alone on the first page, which both the Vorbis and Opus mappings require.
        out.write(emitPages(listOf(headers[0].bytes), serial, 0, bos = true, granule = 0L))
        var seq = 1
        val afterFirst = listOf(newComment) + headers.drop(2).map { it.bytes }
        val headerPages = emitPagesCounted(afterFirst, serial, seq, bos = false, granule = 0L)
        out.write(headerPages.first)
        seq += headerPages.second

        // Everything from the page after the headers is copied byte for byte, with only its
        // sequence number and checksum rewritten - the audio is never re-packetised.
        for (p in pages.drop(last.lastPage + 1)) {
            val copy = original.copyOfRange(p.at, p.end)
            writeU32le(copy, 18, seq)
            seq++
            writeU32le(copy, 22, 0)
            writeU32le(copy, 22, pageCrc(copy, 0, copy.size))
            out.write(copy)
        }
        return out.toByteArray()
    }

    fun putOgg(original: ByteArray, label: String, value: String): ByteArray? {
        val name = nameFor(label) ?: label.uppercase().takeIf { isValidName(it) } ?: return null
        return withOggTag(original, withField(oggTag(original), name, value))
    }

    fun putOggPicture(original: ByteArray, cover: Cover?): ByteArray? {
        val tag = oggTag(original)
        val kept = tag.fields.filterNot { it.first.equals(PICTURE_FIELD, ignoreCase = true) }
        val next = if (cover == null) kept else kept + (PICTURE_FIELD to encodePictureField(cover))
        return withOggTag(original, tag.copy(fields = next))
    }

    private fun writeU32le(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
        b[at + 2] = ((v ushr 16) and 0xFF).toByte()
        b[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun emitPages(
        packets: List<ByteArray>,
        serial: Int,
        startSeq: Int,
        bos: Boolean,
        granule: Long,
    ): ByteArray = emitPagesCounted(packets, serial, startSeq, bos, granule).first

    /**
     * Pack packets into pages, and say how many pages that took.
     *
     * The lacing rule is the fiddly part: a packet is written as however many 255s it needs plus
     * one final value below 255, so a packet whose length is an exact multiple of 255 ends with a
     * zero-length segment. Leaving that zero out makes the next packet an unterminated
     * continuation of this one.
     */
    private fun emitPagesCounted(
        packets: List<ByteArray>,
        serial: Int,
        startSeq: Int,
        bos: Boolean,
        granule: Long,
    ): Pair<ByteArray, Int> {
        val lacing = ArrayList<Int>()
        val body = ByteArrayOutputStream()
        for (p in packets) {
            var n = p.size
            while (n >= 255) {
                lacing.add(255)
                n -= 255
            }
            lacing.add(n)
            body.write(p)
        }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        var seq = startSeq
        var li = 0
        var bodyAt = 0
        var pages = 0
        var continued = false
        while (li < lacing.size) {
            val take = minOf(255, lacing.size - li)
            val group = lacing.subList(li, li + take)
            val len = group.sum()
            val flags = (if (continued) 0x01 else 0) or (if (bos && pages == 0) 0x02 else 0)
            out.write(buildPage(serial, seq, flags, granule, group, bytes, bodyAt, len))
            continued = group.last() == 255
            li += take
            bodyAt += len
            seq++
            pages++
        }
        return out.toByteArray() to pages
    }

    private fun buildPage(
        serial: Int,
        seq: Int,
        flags: Int,
        granule: Long,
        lacing: List<Int>,
        body: ByteArray,
        bodyAt: Int,
        bodyLen: Int,
    ): ByteArray {
        val page = ByteArray(27 + lacing.size + bodyLen)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[4] = 0
        page[5] = flags.toByte()
        for (i in 0 until 8) page[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        writeU32le(page, 14, serial)
        writeU32le(page, 18, seq)
        // The checksum field is zero while the checksum is computed over the whole page, itself
        // included. Forgetting to zero it first is the classic way to produce a page that is
        // structurally perfect and still rejected.
        writeU32le(page, 22, 0)
        page[26] = lacing.size.toByte()
        for ((i, v) in lacing.withIndex()) page[27 + i] = v.toByte()
        System.arraycopy(body, bodyAt, page, 27 + lacing.size, bodyLen)
        writeU32le(page, 22, pageCrc(page, 0, page.size))
        return page
    }
}
