package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.RangedBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A zip on another device read where it lies: listed and opened through ranged reads, with
 * nothing else of it fetched. Counted, because "it works" is not the claim - "it works without
 * downloading the zip" is.
 */
class RangedZipReaderTest {

    private val photo = 200 * 1024
    private val photos = 30

    /** Thirty pictures that will not compress, a note, and an empty folder: a 6 MB zip. */
    private val zip: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            val random = Random(7)
            for (i in 0 until photos) {
                z.putNextEntry(ZipEntry("photos/p%02d.jpg".format(i)))
                z.write(ByteArray(photo).also { random.nextBytes(it) })
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("notes/readme.txt"))
            z.write("read me".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("empty/"))
            z.closeEntry()
        }
    }.toByteArray()

    private fun blocks() = RangedBlocks(zip.size.toLong()) { from, until -> zip.copyOfRange(from.toInt(), until.toInt()) }

    /** A stand-in of its own per test, since listings are kept against the stand-in. */
    private fun standIn(): File = File.createTempFile("ranged", ".zip").apply { deleteOnExit() }

    @Test
    fun `listing reads the table of contents and not the zip`() {
        val b = blocks()
        val members = RangedZipReader(standIn(), b).members()
        assertEquals(photos + 2, members.size)
        assertTrue(members.any { it.name == "notes/readme.txt" && !it.isDir && it.size == 7L })
        assertTrue(members.any { it.name == "empty" && it.isDir })
        assertTrue(zip.size > 6_000_000)
        assertTrue("${b.requests} requests", b.requests <= 3)
        assertTrue("${b.fetched} bytes of ${zip.size}", b.fetched <= 3L * RangedBlocks.BLOCK)
    }

    @Test
    fun `opening a member fetches that member, and its bytes are right`() {
        val b = blocks()
        val reader = RangedZipReader(standIn(), b)
        reader.members()
        val before = b.fetched
        assertEquals("read me", reader.open("notes/readme.txt")!!.use { String(it.readBytes()) })

        val expected = Random(7).let { r -> List(photos) { ByteArray(photo).also { r.nextBytes(it) } } }
        assertArrayEquals(expected[12], reader.open("photos/p12.jpg")!!.use { it.readBytes() })
        // About one picture, never the whole zip: 6 MB here.
        assertTrue("${b.fetched - before} bytes", b.fetched - before < 3L * photo)
    }

    @Test
    fun `a member that is not there, or is a folder, opens as nothing`() {
        val reader = RangedZipReader(standIn(), blocks())
        assertNull(reader.open("photos/p99.jpg"))
        assertNull(reader.open("empty"))
    }

    @Test
    fun `the provider lists a stand-in as the zip, and refuses to write into it`() {
        val host = standIn()
        val b = blocks()
        val mounts = object : RangedMounts {
            override fun holds(host: File) = host.name.startsWith("ranged")
            override fun blocksOf(host: File) = b
        }
        val provider = ArchiveProvider(ranged = mounts)
        val root = ArchiveProvider.mount(host.absolutePath)
        val names = runBlocking { provider.list(root) }.map { it.name }.sorted()
        assertEquals(listOf("empty", "notes", "photos"), names)
        val inner = runBlocking { provider.list(root.child("photos")) }
        assertEquals(photos, inner.size)
        assertEquals("read me", runBlocking { provider.openRead(root.child("notes").child("readme.txt")) }.use { String(it.readBytes()) })

        assertTrue(provider.writeRefusal(root.child("notes"))!!.contains("read from another device"))
        assertEquals(setOf(Capability.READ), provider.capabilitiesFor(root.child("notes")))
    }

    @Test
    fun `a stand-in whose zip cannot be reached says so`() {
        val mounts = object : RangedMounts {
            override fun holds(host: File) = true
            override fun blocksOf(host: File): RangedBlocks? = null
        }
        val provider = ArchiveProvider(ranged = mounts)
        val failure = runCatching { runBlocking { provider.list(ArchiveProvider.mount(standIn().absolutePath)) } }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("Open it again from the drive"))
    }
}
