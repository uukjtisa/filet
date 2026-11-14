package dev.niccc2007.filet.apk

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkBytesTest {

    // ── the end of the zip ──

    @Test
    fun `finds the central directory from the end of the file`() {
        val zip = zipOf("a.txt" to ByteArray(300) { 1 }, "b.txt" to ByteArray(10) { 2 })
        val cd = ApkBytes.centralDirectoryOffset(zip, zip.size.toLong()) { _, _ -> error("no zip64 here") }!!
        assertEquals(CENTRAL, ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).getInt(cd.toInt()))
    }

    @Test
    fun `a comment carrying the end record's signature does not fool it`() {
        val zip = zipOf("a.txt" to ByteArray(5), comment = "xx PK\u0005\u0006 yy")
        val cd = ApkBytes.centralDirectoryOffset(zip, zip.size.toLong()) { _, _ -> error("no zip64 here") }!!
        assertEquals(CENTRAL, ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).getInt(cd.toInt()))
    }

    @Test
    fun `no end record is null, not an exception`() {
        assertNull(ApkBytes.centralDirectoryOffset(ByteArray(100) { 7 }, 100) { _, _ -> ByteArray(0) })
        assertNull(ApkBytes.centralDirectoryOffset(ByteArray(3), 3) { _, _ -> ByteArray(0) })
    }

    // ── the signing block ──

    @Test
    fun `v2 alone gives its first signer's first certificate`() {
        val block = signingBlock(ApkBytes.V2 to v2(cert("A"), cert("A2")))
        assertArrayEquals(cert("A"), ApkBytes.signerCertificate(block, 31))
    }

    @Test
    fun `v3 is read before v2, as Android reads it`() {
        val block = signingBlock(ApkBytes.V2 to v2(cert("old")), ApkBytes.V3 to v3(Triple(cert("new"), 24, Int.MAX_VALUE)))
        assertArrayEquals(cert("new"), ApkBytes.signerCertificate(block, 31))
    }

    @Test
    fun `a v3 signer is picked by the Android version it covers`() {
        val value = v3(Triple(cert("C"), 24, 30), Triple(cert("D"), 31, Int.MAX_VALUE))
        val block = signingBlock(ApkBytes.V3 to value)
        assertArrayEquals(cert("C"), ApkBytes.signerCertificate(block, 28))
        assertArrayEquals(cert("D"), ApkBytes.signerCertificate(block, 31))
    }

    @Test
    fun `v3_1 counts from Android 13 only`() {
        val block = signingBlock(
            ApkBytes.V3 to v3(Triple(cert("v3"), 24, Int.MAX_VALUE)),
            ApkBytes.V31 to v3(Triple(cert("v31"), 33, Int.MAX_VALUE)),
        )
        assertArrayEquals(cert("v3"), ApkBytes.signerCertificate(block, 31))
        assertArrayEquals(cert("v31"), ApkBytes.signerCertificate(block, 34))
    }

    @Test
    fun `the block's start comes from its footer`() {
        val block = signingBlock(ApkBytes.V2 to v2(cert("A")))
        val before = ByteArray(1000)
        val file = before + block
        val cd = file.size.toLong()
        val start = ApkBytes.signingBlockStart(file.copyOfRange(file.size - 24, file.size), cd)
        assertEquals(before.size.toLong(), start)
    }

    @Test
    fun `no signing block, or a broken one, is null`() {
        assertNull(ApkBytes.signingBlockStart(ByteArray(24), 1000))
        val block = signingBlock(ApkBytes.V2 to v2(cert("A")))
        assertNull(ApkBytes.signerCertificate(block.copyOfRange(0, 40), 31))
        assertNull(ApkBytes.signerCertificate(signingBlock(0x42 to ByteArray(8)), 31))
    }

    // ── binary XML ──

    @Test
    fun `references in a compiled XML file are found, theme attributes are not`() {
        val xml = axml(
            0x01 to 0x7f080001, // @drawable/...
            0x02 to 0x7f010000, // ?attr/... : resolves against a theme, not to a file
            0x07 to 0x7f080002, // a reference across a shared library
            0x1c to 0xff336699.toInt(), // a colour written in
            0x01 to 0, // @null
        )
        assertEquals(listOf(0x7f080001, 0x7f080002), ApkBytes.xmlReferences(xml))
    }

    @Test
    fun `a manifest written by ARSCLib gives its icon as a reference`() {
        val manifest = AndroidManifestBlock()
        manifest.packageName = "dev.example.app"
        manifest.iconResourceId = 0x7f0d0000
        manifest.refreshFull()
        assertTrue(ApkBytes.xmlReferences(manifest.bytes).contains(0x7f0d0000))
    }

    @Test
    fun `bytes that are not compiled XML give nothing and do not throw`() {
        assertEquals(emptyList<Int>(), ApkBytes.xmlReferences(ByteArray(0)))
        assertEquals(emptyList<Int>(), ApkBytes.xmlReferences("<vector/>".toByteArray()))
        val xml = axml(0x01 to 0x7f080001)
        assertEquals(emptyList<Int>(), ApkBytes.xmlReferences(xml.copyOfRange(0, xml.size - 6)))
    }

    // ── dex and members ──

    @Test
    fun `a dex header gives its class count`() {
        assertEquals(1234, ApkBytes.dexClassCount(dexHeader(1234)))
        assertNull(ApkBytes.dexClassCount(dexHeader(5).also { it[0] = 'x'.code.toByte() }))
        assertNull(ApkBytes.dexClassCount(ByteArray(20)))
    }

    @Test
    fun `dex files and ABIs by member name`() {
        assertTrue(ApkBytes.isDex("classes.dex"))
        assertTrue(ApkBytes.isDex("classes12.dex"))
        assertTrue(!ApkBytes.isDex("assets/classes.dex"))
        assertEquals("arm64-v8a", ApkBytes.abiOf("lib/arm64-v8a/libfoo.so"))
        assertNull(ApkBytes.abiOf("lib/"))
        assertNull(ApkBytes.abiOf("assets/lib/x86/a.so"))
    }

    // ── the icon's files ──

    @Test
    fun `an adaptive icon brings its layers, and Android's own resources stay out`() {
        val icon = 0x7f0d0000
        val fg = 0x7f0d0001
        val fgFile = 0x7f080000
        val values = mapOf(
            icon to listOf(ApkBytes.Value.FileAt("res/mipmap-anydpi-v26/ic_launcher.xml"), ApkBytes.Value.FileAt("res/mipmap-xxhdpi-v4/ic_launcher.png")),
            fg to listOf(ApkBytes.Value.Ref(fgFile)),
            fgFile to listOf(ApkBytes.Value.FileAt("res/drawable/fg.xml")),
            0x01080000 to listOf(ApkBytes.Value.FileAt("res/framework.png")),
        )
        val refs = mapOf(
            "res/mipmap-anydpi-v26/ic_launcher.xml" to listOf(fg, 0x01080000, icon),
            "res/drawable/fg.xml" to listOf(0x7f050000),
        )
        val files = ApkBytes.closure(listOf(icon, 0), { values[it].orEmpty() }, { refs[it].orEmpty() })
        assertEquals(
            listOf("res/mipmap-anydpi-v26/ic_launcher.xml", "res/mipmap-xxhdpi-v4/ic_launcher.png", "res/drawable/fg.xml"),
            files,
        )
    }

    @Test
    fun `the icon's files stop at the ceiling`() {
        val many = (0 until 100).map { ApkBytes.Value.FileAt("res/d/$it.png") }
        assertEquals(5, ApkBytes.closure(listOf(0x7f000001), { many }, { emptyList() }, maxFiles = 5).size)
    }

    // ── builders ──

    private fun zipOf(vararg members: Pair<String, ByteArray>, comment: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            comment?.let { z.setComment(it) }
            for ((name, bytes) in members) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val CENTRAL = 0x02014b50

        fun cert(tag: String) = "CERT:$tag".toByteArray()

        private fun u32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        private fun u64(n: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
        private fun lp(b: ByteArray) = u32(b.size) + b

        /** A v2 scheme's value: one signer holding [certs]. */
        fun v2(vararg certs: ByteArray): ByteArray {
            val signed = lp(lp(u32(1) + u32(0))) + lp(certs.fold(ByteArray(0)) { a, c -> a + lp(c) }) + lp(ByteArray(0))
            val signer = lp(signed) + lp(ByteArray(0)) + lp(ByteArray(4))
            return lp(lp(signer))
        }

        /** A v3 scheme's value: a signer per (certificate, lowest, highest Android version). */
        fun v3(vararg signers: Triple<ByteArray, Int, Int>): ByteArray {
            val seq = signers.fold(ByteArray(0)) { acc, (c, min, max) ->
                val signed = lp(ByteArray(0)) + lp(lp(c)) + u32(min) + u32(max) + lp(ByteArray(0))
                acc + lp(lp(signed) + u32(min) + u32(max) + lp(ByteArray(0)) + lp(ByteArray(4)))
            }
            return lp(seq)
        }

        /** A whole signing block holding [pairs], footer and all. */
        fun signingBlock(vararg pairs: Pair<Int, ByteArray>): ByteArray {
            val body = pairs.fold(ByteArray(0)) { acc, (id, v) -> acc + u64(4L + v.size) + u32(id) + v }
            val size = body.size + 8L + 16
            return u64(size) + body + u64(size) + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        }

        /** A dex header claiming [classes] classes. */
        fun dexHeader(classes: Int): ByteArray {
            val h = ByteArray(ApkBytes.DEX_HEADER)
            "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(h)
            ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(0x60, classes)
            return h
        }

        /** A compiled XML file with one element carrying an attribute per (type, data). */
        fun axml(vararg attrs: Pair<Int, Int>): ByteArray {
            val el = ByteBuffer.allocate(16 + 20 + 20 * attrs.size).order(ByteOrder.LITTLE_ENDIAN)
            el.putShort(0x0102).putShort(16).putInt(el.capacity()).putInt(1).putInt(-1)
            el.putInt(-1).putInt(0).putShort(20).putShort(20).putShort(attrs.size.toShort())
                .putShort(0).putShort(0).putShort(0)
            for ((type, data) in attrs) {
                el.putInt(-1).putInt(0).putInt(-1).putShort(8).put(0).put(type.toByte()).putInt(data)
            }
            val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(0x0003).putShort(8).putInt(8 + el.capacity())
            return head.array() + el.array()
        }
    }
}
