package dev.niccc2007.filet.locker

import dev.niccc2007.filet.vfs.VPath
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

/** What a lock cuts, what a lost or damaged file costs, and that nothing is deleted unasked. */
class LockerSafetyTest {

    private val root: File = Files.createTempDirectory("locker-safety").toFile()
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

    private fun sealedFiles() = File(dir, "files").list()?.filterNot { it.endsWith(".part") }.orEmpty()

    @Test fun `a lock cuts a file being read at once`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        write(fs, "/v.mp4", Random(1).nextBytes(400_000))
        val reading = fs.openRead(p("/v.mp4"))
        reading.read(ByteArray(1000))
        v.lock()
        assertFalse(v.open.value)
        assertTrue(runCatching { reading.read(ByteArray(1000)) }.exceptionOrNull() is LockerVault.Locked)
        reading.close()
    }

    @Test fun `a copy holds the lock off to its end, and a reader is cut when it lands`() = runBlocking {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        write(fs, "/a.bin", Random(2).nextBytes(200_000))
        val held = v.hold()!!
        val reading = fs.openRead(p("/a.bin"))
        v.lock()
        assertTrue(v.open.value)
        assertTrue(v.pending.value)
        // The copy goes on reading, and can start its next file.
        reading.read(ByteArray(1000))
        write(fs, "/b.bin", ByteArray(10))
        held.close()
        assertFalse(v.open.value)
        assertFalse(v.pending.value)
        assertTrue(runCatching { reading.read(ByteArray(10)) }.exceptionOrNull() is LockerVault.Locked)
        // Closing twice changes nothing.
        held.close()
        assertTrue(vault().unlock("pw".toCharArray()))
    }

    @Test fun `a missing key file is put back from its copy`() {
        vault().create("pw".toCharArray())
        File(dir, "key").delete()
        val v = vault()
        assertEquals(LockerVault.Health.OK, v.health())
        assertTrue(v.unlock("pw".toCharArray()))
    }

    @Test fun `with the key and its copy gone, nothing is set up over the sealed files and nothing is deleted`() {
        val v = vault().also { it.create("pw".toCharArray()) }
        write(LockerProvider(v), "/a.jpg", ByteArray(5000))
        v.lock()
        File(dir, "key").delete()
        File(dir, "key.bak").delete()

        val again = vault()
        assertEquals(LockerVault.Health.KEY_MISSING, again.health())
        assertFalse(again.made)
        assertTrue(runCatching { again.create("new".toCharArray()) }.isFailure)
        assertEquals(1, sealedFiles().size)

        assertTrue(LockerVault.eraseConfirmed("  Erase "))
        assertFalse(LockerVault.eraseConfirmed("eras"))
        again.erase()
        assertEquals(LockerVault.Health.NONE, again.health())
    }

    @Test fun `a damaged list opens from its copy, and files added since come back under Recovered`() {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        val first = Random(3).nextBytes(3000)
        write(fs, "/first.jpg", first)
        v.lock()
        // An unlock keeps the copy of the list as it is now: with first.jpg.
        val v2 = vault().also { assertTrue(it.unlock("pw".toCharArray())) }
        write(LockerProvider(v2), "/second.jpg", ByteArray(2000))
        v2.lock()
        File(dir, "index").writeBytes(ByteArray(64))

        val v3 = vault()
        assertTrue(v3.unlock("pw".toCharArray()))
        v3.sweep()
        assertEquals(2, sealedFiles().size)
        assertArrayEquals(first, runBlocking { LockerProvider(v3).openRead(p("/first.jpg")).use { it.readBytes() } })
        assertEquals(listOf("Recovered file 1"), v3.read { i -> i.children(LockerVault.RECOVERED).map { it.name } })
        assertEquals(2000L, v3.read { it.get("/Recovered/Recovered file 1")?.size })
        assertTrue(v3.takeNotice()!!.contains("copy"))
        assertNull(v3.takeNotice())
    }

    @Test fun `with the list and its copy both damaged, the right password still reaches every file`() {
        val v = vault().also { it.create("pw".toCharArray()) }
        val fs = LockerProvider(v)
        write(fs, "/a.jpg", ByteArray(1000))
        write(fs, "/b.jpg", ByteArray(2000))
        v.lock()
        File(dir, "index").writeBytes(ByteArray(64))
        File(dir, "index.bak").delete()

        val wrong = vault()
        assertFalse(wrong.unlock("nope".toCharArray()))
        assertEquals(LockerVault.Failure.WRONG_PASSWORD, wrong.failure)

        val v2 = vault()
        assertTrue(v2.unlock("pw".toCharArray()))
        v2.sweep()
        assertEquals(2, v2.read { it.children(LockerVault.RECOVERED).size })
        assertEquals(2, sealedFiles().size)
        assertTrue(File(dir, "index.damaged").isFile)
    }

    @Test fun `a key file that is not a key at all says so, rather than that the password is wrong`() {
        vault().create("pw".toCharArray())
        File(dir, "key").writeBytes(ByteArray(8))
        File(dir, "key.bak").writeBytes(ByteArray(8))
        val v = vault()
        assertFalse(v.unlock("pw".toCharArray()))
        assertEquals(LockerVault.Failure.KEY_DAMAGED, v.failure)
    }

    @Test fun `a damaged key file with a whole copy still opens`() {
        vault().create("pw".toCharArray())
        File(dir, "key").writeBytes(ByteArray(8))
        assertTrue(vault().unlock("pw".toCharArray()))
    }
}
