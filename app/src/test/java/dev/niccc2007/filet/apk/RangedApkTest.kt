package dev.niccc2007.filet.apk

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.niccc2007.filet.vfs.RangedBlocks
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * An APK read through ranges, as from a share: what comes over, what does not, and the head it
 * writes for Android's parser. The APK is built here, with a real manifest and resource table
 * from ARSCLib, so nothing depends on a file in the repo.
 */
class RangedApkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads the manifest, dex counts, ABIs and signer without the bulk of the file`() {
        val apk = Apk.build(signed = true)
        val blocks = blocksOf(apk.bytes)
        val facts = RangedApk(blocks).read(tmp.newFile("head.apk"), 31)

        assertEquals("dev.example.app", facts.manifest.packageName)
        assertEquals(42, facts.manifest.versionCode)
        assertEquals(listOf("android.permission.CAMERA"), facts.manifest.usesPermissions)
        assertEquals(listOf("classes.dex", "classes2.dex"), facts.dexEntries)
        assertEquals(5 + 3, facts.classCount)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), facts.nativeAbis.toSet())
        assertArrayEquals(ApkBytesTest.cert("signer"), facts.signer)
        // The 3 MB of assets and the dex bodies stay where they are.
        assertTrue("fetched ${blocks.fetched} of ${apk.bytes.size}", blocks.fetched < 700_000)
        assertTrue("requests ${blocks.requests}", blocks.requests <= 12)
    }

    @Test
    fun `the head holds the manifest, the table aligned, and every file of the icon`() {
        val apk = Apk.build(signed = false)
        val head = tmp.newFile("head.apk")
        val facts = RangedApk(blocksOf(apk.bytes)).read(head, 31)

        assertEquals(
            setOf("res/mipmap-anydpi-v26/ic_launcher.xml", "res/mipmap-xxhdpi-v4/ic_launcher.png", "res/drawable/fg.png"),
            facts.iconFiles.toSet(),
        )
        ZipFile.builder().setFile(head).get().use { zip ->
            val names = zip.entries.asSequence().map { it.name }.toSet()
            assertEquals(setOf(RangedApk.MANIFEST, RangedApk.TABLE) + facts.iconFiles, names)
            val table = zip.getEntry(RangedApk.TABLE)
            assertEquals(ZipEntry.STORED, table.method)
            assertEquals(0L, table.dataOffset % 4)
            assertArrayEquals(apk.table, zip.getInputStream(table).use { it.readBytes() })
            assertArrayEquals(apk.png, zip.getInputStream(zip.getEntry("res/mipmap-xxhdpi-v4/ic_launcher.png")).use { it.readBytes() })
        }
    }

    @Test
    fun `an APK signed only the old way, or not at all, has no signer from the block`() {
        val facts = RangedApk(blocksOf(Apk.build(signed = false).bytes)).read(tmp.newFile("head.apk"), 31)
        assertNull(facts.signer)
    }

    @Test(expected = IOException::class)
    fun `a zip with no manifest is refused, so the caller stages a copy`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(ByteArray(10)); z.closeEntry() }
        RangedApk(blocksOf(out.toByteArray())).read(tmp.newFile("head.apk"), 31)
    }

    @Test
    fun `the alignment field starts the data on a four-byte boundary from any offset`() {
        for (offset in 0L until 16L) {
            val extra = RangedApk.alignment(offset, RangedApk.TABLE.length)
            assertEquals(0L, (offset + 30 + RangedApk.TABLE.length + extra.size) % 4)
            assertEquals(0x35.toByte(), extra[0])
            assertEquals(0xd9.toByte(), extra[1])
            assertEquals(extra.size - 4, extra[2].toInt())
        }
    }

    private fun blocksOf(bytes: ByteArray) = RangedBlocks(bytes.size.toLong()) { from, until ->
        bytes.copyOfRange(from.toInt(), until.toInt())
    }

    /** A small APK with a real manifest and table, two dex files, libraries and 3 MB of assets. */
    private class Apk(val bytes: ByteArray, val table: ByteArray, val png: ByteArray) {
        companion object {
            fun build(signed: Boolean): Apk {
                val tb = TableBlock()
                val pkg = tb.newPackage(0x7f, "dev.example.app")
                val anyDpi = pkg.getOrCreate("anydpi-v26", "mipmap", "ic_launcher")
                anyDpi.setValueAsString("res/mipmap-anydpi-v26/ic_launcher.xml")
                val icon = anyDpi.resourceId
                pkg.getOrCreate("xxhdpi-v4", "mipmap", "ic_launcher").setValueAsString("res/mipmap-xxhdpi-v4/ic_launcher.png")
                val fgFile = pkg.getOrCreate("", "drawable", "fg")
                fgFile.setValueAsString("res/drawable/fg.png")
                val fgAlias = pkg.getOrCreate("", "drawable", "fg_alias")
                fgAlias.setValueAsReference(fgFile.resourceId)
                tb.refreshFull()
                val table = tb.bytes

                val manifest = AndroidManifestBlock()
                manifest.packageName = "dev.example.app"
                manifest.versionCode = 42
                manifest.versionName = "4.2"
                manifest.addUsesPermission("android.permission.CAMERA")
                manifest.iconResourceId = icon
                manifest.refreshFull()

                // The adaptive icon: one element whose attribute points at the alias of the
                // foreground, which points at the file.
                val adaptive = ApkBytesTest.axml(0x01 to fgAlias.resourceId)
                val png = Random(7).nextBytes(3000)

                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { z ->
                    fun put(name: String, data: ByteArray, stored: Boolean = false) {
                        val e = ZipEntry(name)
                        if (stored) {
                            e.method = ZipEntry.STORED
                            e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                            e.crc = CRC32().also { it.update(data) }.value
                        }
                        z.putNextEntry(e); z.write(data); z.closeEntry()
                    }
                    put("AndroidManifest.xml", manifest.bytes)
                    put("classes.dex", ApkBytesTest.dexHeader(5) + Random(1).nextBytes(200_000))
                    put("classes2.dex", ApkBytesTest.dexHeader(3) + Random(2).nextBytes(150_000), stored = true)
                    put("assets/big.bin", Random(3).nextBytes(3_000_000), stored = true)
                    put("lib/arm64-v8a/libx.so", Random(4).nextBytes(5000))
                    put("lib/armeabi-v7a/libx.so", Random(5).nextBytes(5000))
                    put("res/mipmap-anydpi-v26/ic_launcher.xml", adaptive)
                    put("res/mipmap-xxhdpi-v4/ic_launcher.png", png, stored = true)
                    put("res/drawable/fg.png", Random(6).nextBytes(2000), stored = true)
                    put("res/drawable/unrelated.png", Random(8).nextBytes(2000), stored = true)
                    put("resources.arsc", table, stored = true)
                }
                var bytes = out.toByteArray()
                if (signed) bytes = withSigningBlock(bytes, ApkBytesTest.signingBlock(ApkBytes.V2 to ApkBytesTest.v2(ApkBytesTest.cert("signer"))))
                return Apk(bytes, table, png)
            }

            /** The block placed before the table of contents, and the end record moved to match. */
            private fun withSigningBlock(zip: ByteArray, block: ByteArray): ByteArray {
                val eocd = ApkBytes.endRecordIn(zip)!!
                val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
                val cd = b.getInt(eocd + 16)
                val out = zip.copyOfRange(0, cd) + block + zip.copyOfRange(cd, zip.size)
                ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd + block.size + 16, cd + block.size)
                return out
            }
        }
    }
}
