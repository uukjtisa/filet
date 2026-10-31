package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder Details arithmetic and the four states the size rows can be in.
 *
 * The walk and the index both feed [TallyBuilder], so these are the tests for both.
 */
class FolderTallyTest {

    private fun tally(build: TallyBuilder.() -> Unit) = TallyBuilder().apply(build).build()

    @Test
    fun `counts files and folders at every depth, and direct ones separately`() {
        val t = tally {
            add("a.txt", true, false, 10, 0, 0)
            add("Photos", true, true, -1, 0, 0)
            add("Photos", false, false, 100, 0, 0)
            add("Photos", false, true, -1, 0, 0)
            add("Photos", false, false, 5, 0, 0)
        }
        assertEquals(3L, t.files)
        assertEquals(2L, t.folders)
        assertEquals(115L, t.bytes)
        assertEquals(1, t.directFiles)
        assertEquals(1, t.directFolders)
    }

    @Test
    fun `a folder's own size field is never added - its bytes are its contents`() {
        val t = tally { add("d", true, true, 4096, 0, 0) }
        assertEquals(0L, t.bytes)
    }

    @Test
    fun `the largest child is the direct child with the most bytes under it`() {
        val t = tally {
            add("big.iso", true, false, 900, 0, 0)
            add("Music", true, true, -1, 0, 0)
            add("Music", false, false, 600, 0, 0)
            add("Music", false, false, 600, 0, 0)
        }
        assertEquals("Music", t.largestName)
        assertEquals(1200L, t.largestBytes)
        assertTrue(t.largestIsDir)
    }

    @Test
    fun `a tie names the same child every time`() {
        val t = tally {
            add("b", true, false, 5, 0, 0)
            add("a", true, false, 5, 0, 0)
        }
        assertEquals("a", t.largestName)
    }

    @Test
    fun `an empty folder has no largest child`() {
        assertNull(tally {}.largestName)
    }

    @Test
    fun `a hard link is counted as a file but its bytes only once`() {
        val t = tally {
            add("x", true, false, 1000, 77, 5)
            add("y", true, false, 1000, 77, 5)
        }
        assertEquals(2L, t.files)
        assertEquals(1000L, t.bytes)
        assertEquals(1, t.sharedLinks)
    }

    @Test
    fun `inode zero means no inode, not one shared file`() {
        val t = tally {
            add("x", true, false, 1000, 0, 5)
            add("y", true, false, 1000, 0, 5)
        }
        assertEquals(2000L, t.bytes)
        assertEquals(0, t.sharedLinks)
    }

    @Test
    fun `the same inode number on another mount is a different file`() {
        // Different size: inode numbers repeat across filesystems.
        val t = tally {
            add("x", true, false, 1000, 77, 5)
            add("y", true, false, 3, 77, 9)
        }
        assertEquals(1003L, t.bytes)
    }

    @Test
    fun `an unreadable folder makes every figure a floor`() {
        val t = tally { unreadable() }
        assertTrue(t.partial)
        assertEquals("nothing readable", FolderSizes.contentsLine(t))
    }

    // ── loop guard ──

    @Test
    fun `a folder reached twice by inode is walked once`() {
        val g = LoopGuard()
        assertTrue(g.enter(10, 0))
        assertTrue(g.enter(11, 1))
        assertFalse("a symlink back up to the root", g.enter(10, 2))
    }

    @Test
    fun `no-inode backends are not deduplicated, only depth-capped`() {
        val g = LoopGuard(maxDepth = 3)
        assertTrue(g.enter(0, 1))
        assertTrue(g.enter(0, 1))
        assertTrue(g.enter(0, 3))
        assertFalse(g.enter(0, 4))
    }

    // ── which cache, which state ──

    private val t1 = FolderTally.EMPTY.copy(files = 1)
    private val t2 = FolderTally.EMPTY.copy(files = 2)

    @Test
    fun `nothing cached and walking is Measuring, not Confirming`() {
        val v = FolderSizes.view(null, WalkState.Running(t1))
        assertTrue(v is SizeView.Measuring)
    }

    @Test
    fun `a cached figure stays on screen while the walk runs`() {
        val c = CachedTally(t1, TallySource.INDEX, 100)
        assertEquals(SizeView.Confirming(c), FolderSizes.view(c, WalkState.Running(t2)))
    }

    @Test
    fun `a finished walk wins over any cache`() {
        val c = CachedTally(t1, TallySource.INDEX, 100)
        assertEquals(SizeView.Confirmed(t2, 200), FolderSizes.view(c, WalkState.Done(t2, 200)))
    }

    @Test
    fun `a failed walk keeps the cached figure, marked unconfirmed`() {
        val c = CachedTally(t1, TallySource.WALK, 100)
        assertEquals(SizeView.Unconfirmed(c, "x"), FolderSizes.view(c, WalkState.Failed("x")))
    }

    @Test
    fun `a remote folder is not walked on open`() {
        assertFalse(FolderSizes.walksOnOpen(remote = true))
        assertTrue(FolderSizes.walksOnOpen(remote = false))
        assertEquals(SizeView.NotMeasured(null), FolderSizes.view(null, WalkState.OnRequest))
    }

    @Test
    fun `the newer cache is the one shown`() {
        val walk = CachedTally(t1, TallySource.WALK, 100)
        val oldIndex = CachedTally(t2, TallySource.INDEX, 50)
        val newIndex = CachedTally(t2, TallySource.INDEX, 150)
        assertEquals(walk, FolderSizes.pick(walk, oldIndex))
        assertEquals(newIndex, FolderSizes.pick(walk, newIndex))
        assertEquals(walk, FolderSizes.pick(walk, null))
        assertEquals(oldIndex, FolderSizes.pick(null, oldIndex))
    }

    // ── wording ──

    @Test
    fun `contents line`() {
        assertEquals("empty", FolderSizes.contentsLine(FolderTally.EMPTY))
        assertEquals("1 file", FolderSizes.contentsLine(FolderTally.EMPTY.copy(files = 1)))
        assertEquals("142 files in 9 folders", FolderSizes.contentsLine(FolderTally.EMPTY.copy(files = 142, folders = 9)))
        assertEquals("1,204 files in 1 folder", FolderSizes.contentsLine(FolderTally.EMPTY.copy(files = 1204, folders = 1)))
    }

    @Test
    fun `direct line`() {
        assertEquals("none", FolderSizes.directLine(FolderTally.EMPTY))
        assertEquals("3 folders, 1 file", FolderSizes.directLine(FolderTally.EMPTY.copy(directFolders = 3, directFiles = 1)))
    }

    @Test
    fun `grouping`() {
        assertEquals("999", FolderSizes.grouped(999))
        assertEquals("1,000", FolderSizes.grouped(1000))
        assertEquals("1,234,567", FolderSizes.grouped(1234567))
    }
}
