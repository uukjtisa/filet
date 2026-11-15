package dev.niccc2007.filet.locker

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** The Locker as a drive, over a real folder: what it keeps, what it refuses, what it leaves on disk. */
class LockerProviderTest {

    private val root: File = Files.createTempDirectory("locker").toFile()
    private val dir = File(root, "locker")
    private val scratch = File(root, "scratch")
    private fun vault() = LockerVault(dir, scratch, rounds = 1000)
    private fun p(s: String) = VPath(LockerPaths.SCHEME, s)

    @After fun clean() {
        root.deleteRecursively()
    }

    private fun write(fs: LockerProvider, path: String, bytes: ByteArray) = runBlocking {
        fs.openWrite(p(path), append = false).use { it.write(bytes) }
    }

    private fun read(fs: LockerProvider, path: String): ByteArray = runBlocking { fs.openRead(p(path)).use { it.readBytes() } }

    @Test fun `what goes in comes out, through folders, after a lock and an unlock`() = runBlocking {
        val v = vault()
        v.create("secret".toCharArray())
        val fs = LockerProvider(v)
        fs.create(p("/Trips"), isDir = true)
        val photo = Random(1).nextBytes(200_000)
        write(fs, "/Trips/a.jpg", photo)
        assertEquals(listOf("Trips"), fs.list(p("/")).map { it.name })
        assertEquals(200_000L, fs.stat(p("/Trips/a.jpg"))?.size)

        v.lock()
        assertFalse(v.open.value)
        assertTrue(runCatching { fs.list(p("/")) }.exceptionOrNull() is VfsException.AccessDenied)

        val again = vault()
        assertFalse(again.unlock("wrong".toCharArray()))
        assertTrue(again.unlock("secret".toCharArray()))
        assertArrayEquals(photo, read(LockerProvider(again), "/Trips/a.jpg"))
    }

    @Test fun `a seek reads from there`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        val bytes = Random(2).nextBytes(300_000)
        write(fs, "/v.mp4", bytes)
        val at = 150_123L
        assertArrayEquals(bytes.copyOfRange(at.toInt(), bytes.size), fs.openReadAt(p("/v.mp4"), at).use { it.readBytes() })
        assertArrayEquals(bytes.copyOfRange(10, 20), fs.readRange(p("/v.mp4"), 10, 20))
        assertEquals(0, fs.openReadAt(p("/v.mp4"), bytes.size.toLong()).use { it.readBytes() }.size)
    }

    @Test fun `the disk holds no name and no plain byte`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        fs.create(p("/Bank statements"), isDir = true)
        val text = "account number 12345678".repeat(50).toByteArray()
        write(fs, "/Bank statements/march.pdf", text)
        val everything = dir.walkTopDown().filter { it.isFile }.toList()
        val names = everything.joinToString(" ") { it.name }
        assertFalse(names.contains("march") || names.contains("Bank"))
        for (f in everything) {
            val s = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.name, s.contains("march") || s.contains("Bank") || s.contains("account number"))
        }
    }

    @Test fun `rename and move change only the index, delete takes the blobs`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        fs.create(p("/A"), isDir = true)
        fs.create(p("/B"), isDir = true)
        write(fs, "/A/x.txt", "x".toByteArray())
        val blobsBefore = File(dir, "files").list()!!.toSet()
        fs.rename(p("/A/x.txt"), "y.txt")
        fs.moveWithin(p("/A/y.txt"), p("/B/y.txt"))
        assertEquals(blobsBefore, File(dir, "files").list()!!.toSet())
        assertArrayEquals("x".toByteArray(), read(fs, "/B/y.txt"))
        fs.delete(p("/B"), recursive = true)
        assertNull(fs.stat(p("/B/y.txt")))
        assertEquals(0, File(dir, "files").list()!!.size)
    }

    @Test fun `a file written over keeps one blob, and an origin noted before arrival is kept`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        v.expectOrigin("/note.txt", "local:///storage/emulated/0/Download/note.txt")
        write(fs, "/note.txt", "one".toByteArray())
        write(fs, "/note.txt", "two".toByteArray())
        assertEquals(1, File(dir, "files").list()!!.size)
        assertEquals("local:///storage/emulated/0/Download/note.txt", v.read { it.originOf("/note.txt") })
        assertEquals("Download", v.read { it.history().first().place })
    }

    @Test fun `a lock asked for mid-copy waits for the copy`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        val out = fs.openWrite(p("/big.bin"), append = false)
        out.write(ByteArray(100_000))
        v.lock()
        assertTrue(v.open.value)
        out.close()
        assertFalse(v.open.value)
        assertTrue(vault().unlock("pw".toCharArray()))
    }

    @Test fun `a changed password opens and the old one does not`() {
        val v = vault().also { it.create("old".toCharArray()) }
        assertTrue(v.changePassword("old".toCharArray(), "new".toCharArray()))
        assertFalse(vault().unlock("old".toCharArray()))
        assertTrue(vault().unlock("new".toCharArray()))
    }

    @Test fun `a log line loses everything from a Locker path on`() {
        assertEquals("staging: fetching locker://…", LockerPaths.redact("staging: fetching locker:///Bank/march.pdf (12 bytes)"))
        assertEquals("nothing here", LockerPaths.redact("nothing here"))
        assertTrue(LockerPaths.isPrivate("locker:///Bank/march.pdf"))
        assertTrue(LockerPaths.isPrivate(VPath(LockerPaths.SCHEME, "/a")))
        assertFalse(LockerPaths.isPrivate(VPath("local", "/storage/emulated/0/locker")))
    }
}
