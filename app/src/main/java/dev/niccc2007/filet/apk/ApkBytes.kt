package dev.niccc2007.filet.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parts of an APK read straight from its bytes, with no parser library: where the zip's table
 * of contents starts, who signed it, which resources a binary XML file points at, and how many
 * classes a dex holds. Pure, so each one is a test. Step 3 of `REMOTE-FILES.md`, for [RangedApk].
 *
 * Every function here answers null or empty for bytes it does not recognise, and never throws on
 * them: an APK on a share that reads oddly falls back to a staged copy, and a parser that threw on
 * a corner of the format would turn that fallback into an error on screen.
 */
object ApkBytes {

    // ── the end of the zip ──────────────────────────────────────────────────────────────────

    /** The end-of-central-directory record's signature, `PK\5\6`. */
    private const val EOCD = 0x06054b50

    /** The zip64 locator's signature, `PK\6\7`, 20 bytes before the end record when there is one. */
    private const val ZIP64_LOCATOR = 0x07064b50

    /** The zip64 end record's signature, `PK\6\6`. */
    private const val ZIP64_EOCD = 0x06064b50

    /** The end record is 22 bytes and its comment at most 65535, so this much of the end holds it. */
    const val TAIL = 22 + 0xFFFF

    /**
     * Where the central directory starts, from the last [tail] bytes of a file of [length] bytes.
     *
     * @param readAt reads a few bytes elsewhere in the file, for a zip64 end record that sits
     *   before the tail. Only asked for an archive over 4 GB, so a plain APK never calls it.
     * @return the offset, or null when the tail holds no end record.
     */
    fun centralDirectoryOffset(tail: ByteArray, length: Long, readAt: (from: Long, size: Int) -> ByteArray): Long? {
        val at = endRecordIn(tail) ?: return null
        val b = le(tail)
        val offset = b.getInt(at + 16).toLong() and 0xFFFFFFFFL
        if (offset != 0xFFFFFFFFL) return offset.takeIf { it < length }
        // Zip64: the 32-bit field is a placeholder and the real offset is in the zip64 end record.
        val loc = at - 20
        if (loc < 0 || b.getInt(loc) != ZIP64_LOCATOR) return null
        val recordAt = b.getLong(loc + 8)
        if (recordAt < 0 || recordAt + 56 > length) return null
        val record = le(runCatching { readAt(recordAt, 56) }.getOrNull() ?: return null)
        if (record.capacity() < 56 || record.getInt(0) != ZIP64_EOCD) return null
        return record.getLong(48).takeIf { it in 0 until length }
    }

    /** The index of the end record in [tail], searching back from the end. */
    fun endRecordIn(tail: ByteArray): Int? {
        val b = le(tail)
        var i = tail.size - 22
        while (i >= 0) {
            // The comment length must reach exactly to the end, or this is a stray PK\5\6 inside
            // the comment or a member and not the record.
            if (b.getInt(i) == EOCD && i + 22 + (b.getShort(i + 20).toInt() and 0xFFFF) == tail.size) return i
            i--
        }
        return null
    }

    // ── the APK signing block ───────────────────────────────────────────────────────────────

    private const val MAGIC = "APK Sig Block 42"

    /** Signature scheme v2. */
    const val V2 = 0x7109871a

    /** Signature scheme v3. */
    const val V3 = 0xf05368c0.toInt()

    /** Signature scheme v3.1, which Android 13 and later read before v3. */
    const val V31 = 0x1b93ad61

    /**
     * Where the signing block starts, from the 24 bytes just before the central directory.
     *
     * @return null when those bytes are not a signing block's footer: an APK signed with v1 only,
     *   or none at all.
     */
    fun signingBlockStart(footer: ByteArray, centralDirectory: Long): Long? {
        if (footer.size < 24) return null
        val b = le(footer)
        if (String(footer, 8, 16, Charsets.US_ASCII) != MAGIC) return null
        val size = b.getLong(0)
        // The size counts everything but its own first copy: the pairs, the second size and the
        // magic. Anything smaller than that frame, or reaching before the file, is not a block.
        if (size < 24 || size > centralDirectory - 8) return null
        return centralDirectory - size - 8
    }

    /**
     * The signing certificate in a signing block, as DER bytes: the same certificate Android
     * reports as the APK's signer, so its SHA-256 compares with an installed app's.
     *
     * Read in the order Android reads the schemes: v3.1 from Android 13, then v3, then v2.
     *
     * @param block the whole block, from its start to the central directory.
     * @param sdk the Android version the answer is for, which picks among v3 signers by their range.
     */
    fun signerCertificate(block: ByteArray, sdk: Int): ByteArray? {
        val pairs = pairsOf(block) ?: return null
        val order = buildList {
            if (sdk >= 33) add(V31)
            add(V3)
            add(V2)
        }
        for (id in order) {
            val value = pairs[id] ?: continue
            certificateIn(value, ranged = id != V2, sdk = sdk)?.let { return it }
        }
        return null
    }

    /** The ID-value pairs of a signing block. */
    fun pairsOf(block: ByteArray): Map<Int, ByteArray>? = runCatching {
        val b = le(block)
        val end = block.size - 24
        if (end < 8) return null
        b.position(8)
        val out = LinkedHashMap<Int, ByteArray>()
        while (b.position() + 12 <= end) {
            val len = b.getLong()
            if (len < 4 || len > end - b.position()) return null
            val id = b.getInt()
            val value = ByteArray((len - 4).toInt())
            b.get(value)
            out.putIfAbsent(id, value)
        }
        out
    }.getOrNull()

    /**
     * The first certificate of the signer that applies, from a v2 or v3 scheme's value.
     *
     * Layout, every sequence prefixed by its length in four bytes: signers, each one signed data
     * (digests, certificates, and for v3 the signer's lowest and highest Android version), then
     * its signatures and public key.
     */
    private fun certificateIn(value: ByteArray, ranged: Boolean, sdk: Int): ByteArray? = runCatching {
        val signers = prefixed(le(value))
        var first: ByteArray? = null
        while (signers.remaining() >= 4) {
            val signer = prefixed(signers)
            val signed = prefixed(signer)
            prefixed(signed) // digests
            val certs = prefixed(signed)
            val cert = if (certs.remaining() >= 4) prefixed(certs).let { c -> ByteArray(c.remaining()).also { c.get(it) } } else null
            if (cert == null || cert.isEmpty()) continue
            if (!ranged) return cert
            val min = if (signed.remaining() >= 8) signed.getInt() else 0
            val max = if (signed.remaining() >= 4) signed.getInt() else Int.MAX_VALUE
            if (first == null) first = cert
            if (sdk in min..max) return cert
        }
        first
    }.getOrNull()

    private fun prefixed(b: ByteBuffer): ByteBuffer {
        val len = b.getInt()
        require(len >= 0 && len <= b.remaining()) { "bad length $len" }
        val slice = b.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(len)
        b.position(b.position() + len)
        return slice
    }

    // ── binary XML ──────────────────────────────────────────────────────────────────────────

    private const val XML = 0x0003
    private const val START_ELEMENT = 0x0102
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_DYNAMIC_REFERENCE = 0x07

    /**
     * Every resource a compiled XML file points at through an attribute: an adaptive icon's
     * foreground and background, a layer's drawable, a vector's colours.
     *
     * Theme attributes (`?attr/...`) are left out: they resolve against a theme, not to a file.
     */
    fun xmlReferences(axml: ByteArray): List<Int> = runCatching {
        val b = le(axml)
        if (axml.size < 8 || (b.getShort(0).toInt() and 0xFFFF) != XML) return emptyList()
        val out = LinkedHashSet<Int>()
        var at = b.getShort(2).toInt() and 0xFFFF
        while (at + 8 <= axml.size) {
            val type = b.getShort(at).toInt() and 0xFFFF
            val header = b.getShort(at + 2).toInt() and 0xFFFF
            val size = b.getInt(at + 4)
            if (size < 8 || at + size > axml.size) break
            if (type == START_ELEMENT && header >= 16 && at + header + 20 <= axml.size) {
                val ext = at + header
                val start = b.getShort(ext + 8).toInt() and 0xFFFF
                val each = b.getShort(ext + 10).toInt() and 0xFFFF
                val count = b.getShort(ext + 12).toInt() and 0xFFFF
                for (i in 0 until count) {
                    val a = ext + start + i * each
                    if (each < 20 || a + 20 > at + size) break
                    val dataType = axml[a + 15].toInt() and 0xFF
                    val data = b.getInt(a + 16)
                    if ((dataType == TYPE_REFERENCE || dataType == TYPE_DYNAMIC_REFERENCE) && data != 0) out += data
                }
            }
            at += size
        }
        out.toList()
    }.getOrDefault(emptyList())

    // ── dex ─────────────────────────────────────────────────────────────────────────────────

    /** Bytes of a dex header that hold its class count. */
    const val DEX_HEADER = 0x70

    /**
     * How many classes a dex defines, from its header alone: `class_defs_size`, the same number
     * dexlib2 gives as its class count, without reading the rest of the file.
     */
    fun dexClassCount(header: ByteArray): Int? {
        if (header.size < DEX_HEADER) return null
        if (header[0] != 'd'.code.toByte() || header[1] != 'e'.code.toByte() || header[2] != 'x'.code.toByte() || header[3] != '\n'.code.toByte()) return null
        return le(header).getInt(0x60).takeIf { it >= 0 }
    }

    /** The ABI a member is for, from `lib/<abi>/...`, or null for anything else. */
    fun abiOf(member: String): String? =
        member.takeIf { it.startsWith("lib/") }?.split('/')?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    /** Whether a member is one of the APK's own dex files, which sit at the top of it. */
    fun isDex(member: String): Boolean = member.endsWith(".dex") && !member.contains('/')

    // ── the files an icon needs ─────────────────────────────────────────────────────────────

    /** One value of a resource: a file in the APK, or another resource. */
    sealed interface Value {
        data class FileAt(val path: String) : Value
        data class Ref(val id: Int) : Value
    }

    /**
     * Every file the resources in [roots] need, following references through the resource table
     * and through each XML file's attributes, so an adaptive icon arrives with its layers.
     *
     * Android's own resources (package 0x01) are left out: they are on every phone already.
     *
     * @param valuesOf a resource's values, one per configuration.
     * @param refsIn the resources an XML file in the APK points at; called once per XML file.
     * @param maxFiles a ceiling, so a resource that drags in half the APK stops early rather
     *   than turning a few small reads into a download.
     */
    fun closure(
        roots: List<Int>,
        valuesOf: (Int) -> List<Value>,
        refsIn: (String) -> List<Int>,
        maxFiles: Int = MAX_ICON_FILES,
    ): List<String> {
        val files = LinkedHashSet<String>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque(roots.filter { it != 0 })
        while (queue.isNotEmpty() && files.size < maxFiles) {
            val id = queue.removeFirst()
            if (!seen.add(id) || id ushr 24 == 0x01) continue
            for (v in valuesOf(id)) {
                when (v) {
                    is Value.Ref -> if (v.id != 0) queue += v.id
                    is Value.FileAt -> if (files.size < maxFiles && files.add(v.path) && v.path.endsWith(".xml")) {
                        queue += refsIn(v.path)
                    }
                }
            }
        }
        return files.toList()
    }

    /** An icon is a handful of files per density; past this something else is being followed. */
    const val MAX_ICON_FILES = 48

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
