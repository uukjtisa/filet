package dev.niccc2007.filet.apk

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import dev.niccc2007.filet.vfs.RangedBlocks
import dev.niccc2007.filet.vfs.RangedChannel
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An APK on another device, read where it lies: what the inspector shows, from a few ranged
 * reads instead of a copy of the whole file. Step 3 of `REMOTE-FILES.md`.
 *
 * ## What is read
 *
 * - The table of contents, from the end of the file: which dex files and native libraries
 *   there are.
 * - `AndroidManifest.xml` and `resources.arsc`, each one member.
 * - The first bytes of each dex, whose header holds its class count.
 * - The signing block, just before the table of contents, which holds the signer's certificate.
 * - The icon's own files, followed through the resource table and through each XML file's
 *   references, so an adaptive icon comes with its layers.
 *
 * Everything else - the dex bodies, the libraries, the assets - stays on the other device.
 *
 * ## The head
 *
 * The manifest, the resource table and the icon's files are written to [read]'s `head`: a small
 * APK holding only those. Android's own parser takes a path and nothing else, and given the
 * head it answers as it would for the whole file - the label in the phone's language, the
 * permissions, the activities, the icon drawn as the launcher would draw it - without the head
 * being anything but a few hundred kilobytes in the cache. What the head cannot answer, the
 * signer and the class count, come from the bytes here.
 *
 * Blocking: every read is a network request. Run it on an IO thread.
 */
class RangedApk(private val blocks: RangedBlocks) {

    /** What [read] found, beside the head it wrote. */
    class Facts(
        val manifest: AndroidManifestBlock,
        val dexEntries: List<String>,
        /** Null when a dex header would not read, so a wrong count is never shown as right. */
        val classCount: Int?,
        val nativeAbis: List<String>,
        /** The signer's certificate as DER, or null for an APK with no signature found. */
        val signer: ByteArray?,
        /** The members the head holds besides the manifest and the table. */
        val iconFiles: List<String>,
    )

    /**
     * Read the APK and write its head to [head].
     *
     * @param sdk the Android version, which picks the signer when a v3 block holds several.
     * @throws IOException when the APK will not read this way; the caller stages a copy instead.
     */
    fun read(head: File, sdk: Int): Facts {
        val zip = ZipFile.builder()
            .setSeekableByteChannel(RangedChannel(blocks))
            .setIgnoreLocalFileHeader(true)
            .get()
        zip.use {
            val names = zip.entries.asSequence().map { it.name }.toList()
            val manifestBytes = member(zip, MANIFEST) ?: throw IOException("there is no AndroidManifest.xml")
            val manifest = AndroidManifestBlock.load(manifestBytes.inputStream())
            val tableBytes = member(zip, TABLE)

            val files = LinkedHashMap<String, ByteArray>()
            val iconFiles = if (tableBytes != null) iconClosure(zip, manifest, tableBytes, files) else emptyList()
            writeHead(head, manifestBytes, tableBytes, iconFiles.associateWith { files.getValue(it) })

            val dex = names.filter(ApkBytes::isDex)
            var classes: Int? = 0
            for (name in dex) {
                val count = dexHeader(zip, name)?.let(ApkBytes::dexClassCount)
                classes = if (count == null || classes == null) null else classes + count
            }
            return Facts(
                manifest = manifest,
                dexEntries = dex,
                classCount = classes,
                nativeAbis = names.mapNotNullTo(LinkedHashSet(), ApkBytes::abiOf).toList(),
                signer = signer(names, zip, sdk),
                iconFiles = iconFiles,
            )
        }
    }

    // ── the icon ────────────────────────────────────────────────────────────────────────────

    private fun iconClosure(
        zip: ZipFile,
        manifest: AndroidManifestBlock,
        tableBytes: ByteArray,
        files: MutableMap<String, ByteArray>,
    ): List<String> {
        val table = runCatching { TableBlock.load(tableBytes.inputStream()) }.getOrNull() ?: return emptyList()
        val roots = listOf(manifest.iconResourceId, manifest.roundIconResourceId)
        var total = 0L
        val wanted = ApkBytes.closure(
            roots = roots,
            valuesOf = { id -> valuesOf(table, id) },
            refsIn = { path ->
                val bytes = fetch(zip, path, files, total).also { if (it != null) total += it.size }
                if (bytes == null) emptyList() else ApkBytes.xmlReferences(bytes)
            },
        )
        // The XML files were fetched while following them; the pictures are fetched now. A file
        // that will not read is left out, and Android then draws what the rest allows.
        return wanted.filter { path ->
            files.containsKey(path) || fetch(zip, path, files, total)?.also { total += it.size } != null
        }
    }

    private fun valuesOf(table: TableBlock, id: Int): List<ApkBytes.Value> {
        val res = runCatching { table.getResource(id) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ApkBytes.Value>()
        for (entry in res) {
            if (entry == null || entry.isNull || entry.isComplex) continue
            val value = entry.resValue ?: continue
            when (value.valueType) {
                ValueType.STRING -> value.valueAsString?.takeIf { it.startsWith("res/") }?.let { out += ApkBytes.Value.FileAt(it) }
                ValueType.REFERENCE, ValueType.DYNAMIC_REFERENCE -> out += ApkBytes.Value.Ref(value.data)
                else -> Unit
            }
        }
        return out
    }

    private fun fetch(zip: ZipFile, path: String, into: MutableMap<String, ByteArray>, total: Long): ByteArray? {
        into[path]?.let { return it }
        if (total >= MAX_ICON_BYTES) return null
        val bytes = runCatching { member(zip, path, MAX_ICON_BYTES - total) }.getOrNull() ?: return null
        into[path] = bytes
        return bytes
    }

    // ── the head ────────────────────────────────────────────────────────────────────────────

    /**
     * A small APK of the manifest, the resource table and the icon's files.
     *
     * The table is stored rather than compressed and starts on a four-byte boundary, as Android
     * wants it in any APK it loads resources from; the rest is compressed, which it reads either way.
     */
    private fun writeHead(head: File, manifest: ByteArray, table: ByteArray?, icon: Map<String, ByteArray>) {
        head.parentFile?.mkdirs()
        val part = File(head.parentFile, head.name + ".part")
        val counter = Counting(part.outputStream().buffered())
        ZipOutputStream(counter).use { out ->
            out.putNextEntry(ZipEntry(MANIFEST))
            out.write(manifest)
            out.closeEntry()
            if (table != null) {
                out.flush()
                val entry = ZipEntry(TABLE).apply {
                    method = ZipEntry.STORED
                    size = table.size.toLong()
                    compressedSize = table.size.toLong()
                    crc = CRC32().also { it.update(table) }.value
                    extra = alignment(counter.count, TABLE.length)
                }
                out.putNextEntry(entry)
                out.write(table)
                out.closeEntry()
            }
            for ((name, bytes) in icon) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        if (!part.renameTo(head)) {
            head.delete()
            if (!part.renameTo(head)) throw IOException("could not write ${head.name}")
        }
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    // ── the dex headers and the signer ──────────────────────────────────────────────────────

    private fun dexHeader(zip: ZipFile, name: String): ByteArray? = runCatching {
        val entry = zip.getEntry(name) ?: return null
        if (!zip.canReadEntryData(entry)) return null
        zip.getInputStream(entry).use { it.readUpTo(ApkBytes.DEX_HEADER) }
    }.getOrNull()

    private fun signer(names: List<String>, zip: ZipFile, sdk: Int): ByteArray? =
        runCatching { schemeSigner(sdk) }.getOrNull() ?: runCatching { v1Signer(names, zip) }.getOrNull()

    /** The signer from the v2/v3 signing block, which sits just before the table of contents. */
    private fun schemeSigner(sdk: Int): ByteArray? {
        val length = blocks.length
        val tailSize = minOf(length, ApkBytes.TAIL.toLong()).toInt()
        val tail = bytesAt(length - tailSize, tailSize)
        val cd = ApkBytes.centralDirectoryOffset(tail, length) { from, size -> bytesAt(from, size) } ?: return null
        if (cd < 32) return null
        val start = ApkBytes.signingBlockStart(bytesAt(cd - 24, 24), cd) ?: return null
        if (cd - start > MAX_SIGNING_BLOCK) return null
        return ApkBytes.signerCertificate(bytesAt(start, (cd - start).toInt()), sdk)
    }

    /**
     * The signer of an APK signed only the old way, from the certificate file in `META-INF`.
     * Android reads these through `CertificateFactory` as well, so the certificate is the same one.
     */
    private fun v1Signer(names: List<String>, zip: ZipFile): ByteArray? {
        val file = names.firstOrNull { n ->
            n.startsWith("META-INF/") && n.count { it == '/' } == 1 &&
                (n.endsWith(".RSA", true) || n.endsWith(".DSA", true) || n.endsWith(".EC", true))
        } ?: return null
        val bytes = member(zip, file, MAX_SIGNING_BLOCK.toLong()) ?: return null
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificates(bytes.inputStream()).firstOrNull() ?: return null
        return cert.encoded
    }

    private fun bytesAt(from: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = blocks.read(from + done, out, done, size - done)
            if (n <= 0) throw IOException("the file ended at ${from + done}")
            done += n
        }
        return out
    }

    // ── members ─────────────────────────────────────────────────────────────────────────────

    /** A member's bytes, or null when there is none. Refuses one larger than [limit]. */
    private fun member(zip: ZipFile, name: String, limit: Long = MAX_MEMBER): ByteArray? {
        val entry: ZipArchiveEntry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        if (!zip.canReadEntryData(entry)) throw IOException("$name is packed in a way this reader does not have")
        if (entry.size > limit) throw IOException("$name is ${entry.size} bytes, more than a head should fetch")
        return zip.getInputStream(entry).use { input ->
            val bytes = input.readUpTo((limit + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (bytes.size > limit) throw IOException("$name is larger than its table of contents says")
            bytes
        }
    }

    /**
     * Up to [n] bytes, fewer only at the end of the stream. `InputStream.readNBytes` does this
     * from Android 13 only, and Filet runs from Android 8.
     */
    private fun java.io.InputStream.readUpTo(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(n, 64 * 1024))
        val buf = ByteArray(8 * 1024)
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val TABLE = "resources.arsc"

        /**
         * The most one member may bring over: a resource table of a large app is a few
         * megabytes, and past this the staged copy is the better deal.
         */
        const val MAX_MEMBER = 24L * 1024 * 1024

        /** The most the icon's files may bring over between them. */
        const val MAX_ICON_BYTES = 4L * 1024 * 1024

        /** A signing block holds certificates and signatures: kilobytes, never megabytes. */
        const val MAX_SIGNING_BLOCK = 4 * 1024 * 1024

        /**
         * The extra field that starts an entry's data on a four-byte boundary: zipalign's own
         * (ID 0xd935), holding the alignment and then the padding.
         *
         * @param offset where the entry's local header starts.
         * @param nameLength the entry name's length in bytes.
         */
        fun alignment(offset: Long, nameLength: Int): ByteArray {
            val before = offset + 30 + nameLength + 6
            val pad = ((4 - before % 4) % 4).toInt()
            val out = ByteArray(6 + pad)
            out[0] = 0x35; out[1] = 0xd9.toByte()
            out[2] = (2 + pad).toByte(); out[3] = 0
            out[4] = 4; out[5] = 0
            return out
        }
    }
}
