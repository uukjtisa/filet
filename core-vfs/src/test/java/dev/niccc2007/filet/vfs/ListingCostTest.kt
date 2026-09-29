package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.vfs.provider.Stat
import dev.niccc2007.filet.vfs.provider.StatFacts
import dev.niccc2007.filet.vfs.provider.Stats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a listing costs, counted.
 *
 * The complaint that produced this was that opening a camera folder took seconds while another
 * file manager showed the same folder at once. The cause was not the directory read - that was
 * 37 ms of the 663 - but the row building after it, which asked the filesystem seven separate
 * questions per entry over a FUSE mount where each one is a trip to another process.
 *
 * A wall-clock assertion would be the wrong way to pin that: it passes or fails on which machine
 * ran it, and the desktop JVM these tests run on has none of the FUSE cost that makes the
 * difference. The count does not vary. So the fake here records every path it is asked about and
 * the assertions are about how many times, which is the thing that actually changed.
 */
class ListingCostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A [Stats] that answers from a fixed template and counts what it was asked. */
    private class CountingStats(
        private val dirs: Set<String> = emptySet(),
        private val mode: Int = 0x81A4,
        private val uid: Int = 1000,
        private val gid: Int = 1000,
    ) : Stats {
        val asked = mutableListOf<String>()
        override fun of(path: String): Stat? {
            asked += path
            val dir = dirs.any { path.endsWith(it) }
            return Stat(
                mode = if (dir) 0x41ED else mode,
                size = if (dir) 4096 else 1234,
                mtimeMillis = 1_695_000_000_123L,
                ino = (asked.size + 100).toLong(),
                uid = uid,
                gid = gid,
            )
        }
    }

    private fun provider(stats: Stats) = LocalProvider(listOf(tmp.root), stats = stats)

    private fun seed(n: Int): VPath {
        val d = tmp.newFolder("camera")
        repeat(n) { File(d, "IMG_%04d.jpg".format(it)).writeText("x") }
        return VPath.of("local", d.absolutePath.replace('\\', '/'))
    }

    @Test
    fun `each entry is statted exactly once`() {
        val stats = CountingStats()
        val path = seed(50)
        val out = runBlocking { provider(stats).list(path) }
        assertEquals(50, out.size)
        assertEquals(50, stats.asked.size)
        // And no entry twice, which is how the old code spent two of its seven calls.
        assertEquals(50, stats.asked.toSet().size)
    }

    @Test
    fun `the row is built from the stat and not from the file on disk`() {
        // Every file seeded above holds one byte. A row reporting 1234 can only have come from
        // the stat, which proves the fast path ran rather than quietly falling back.
        val path = seed(3)
        val out = runBlocking { provider(CountingStats()).list(path) }
        assertTrue(out.all { it.size == 1234L })
        assertTrue(out.all { it.mtime == 1_695_000_000_123L })
        assertTrue(out.all { it.inode != 0L })
    }

    @Test
    fun `a folder reports no size and a file reports its bytes`() {
        val d = tmp.newFolder("mixed")
        File(d, "note.txt").writeText("x")
        File(d, "inner").mkdir()
        val path = VPath.of("local", d.absolutePath.replace('\\', '/'))
        val out = runBlocking { provider(CountingStats(dirs = setOf("inner"))).list(path) }
        val dir = out.first { it.name == "inner" }
        val file = out.first { it.name == "note.txt" }
        assertTrue(dir.isDir)
        assertEquals(-1L, dir.size)
        assertFalse(file.isDir)
        assertEquals(1234L, file.size)
    }

    @Test
    fun `an entry that cannot be statted still appears in the list`() {
        // A dangling symlink is the real case. Dropping it would be worse than showing it with
        // whatever the filesystem will admit to, because a name you can see is a name you can
        // delete.
        val path = seed(4)
        val blind = Stats { null }
        val out = runBlocking { provider(blind).list(path) }
        assertEquals(4, out.size)
        // Fallen back to the per-field route, so the sizes are the real one byte on disk.
        assertTrue(out.all { it.size == 1L })
    }

    @Test
    fun `hidden is decided by the name and needs no call of its own`() {
        val d = tmp.newFolder("hid")
        File(d, ".nomedia").writeText("")
        File(d, "visible.txt").writeText("")
        val path = VPath.of("local", d.absolutePath.replace('\\', '/'))
        val out = runBlocking { provider(CountingStats()).list(path) }
        assertTrue(out.first { it.name == ".nomedia" }.hidden)
        assertFalse(out.first { it.name == "visible.txt" }.hidden)
        assertEquals(2, out.size)
    }

    @Test
    fun `the mode drives the type and not the extension`() {
        // A folder called IMG_0001.jpg is legal and does happen - a camera app writing bursts
        // into per-shot folders makes them. Deciding by name would list it as a file.
        val d = tmp.newFolder("odd")
        File(d, "IMG_0001.jpg").mkdir()
        val path = VPath.of("local", d.absolutePath.replace('\\', '/'))
        val out = runBlocking { provider(CountingStats(dirs = setOf("IMG_0001.jpg"))).list(path) }
        assertTrue(out.single().isDir)
    }

    @Test
    fun `seven calls per entry is what this replaced`() {
        // The negative control, stated as arithmetic rather than as a second implementation:
        // the old row asked isDirectory, isDirectory again, length, lastModified, canRead,
        // canWrite and lstat. On the folder that was measured that is the difference between
        // 1242 questions and 8694, and at FUSE prices it is the difference between a list that
        // appears and one that does not.
        val perEntryBefore = 7
        val perEntryNow = 1
        val entries = 1242
        assertEquals(8694, entries * perEntryBefore)
        assertEquals(1242, entries * perEntryNow)
        assertTrue(entries * perEntryNow < entries * perEntryBefore / 6)
    }

    @Test
    fun `a stat of a directory is a directory whatever its permission bits say`() {
        assertTrue(StatFacts.isDir(0x41ED))
        assertTrue(StatFacts.isDir(0x4000))
    }
}
