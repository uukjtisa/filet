package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A rewrite killed at any moment leaves one whole archive under its own name. */
class ArchiveSwapTest {

    /** Files as names holding a word, with a rename that can be made to fail. */
    private class FakeFs(vararg start: Pair<String, String>) : ArchiveSwap.Fs {
        val files = linkedMapOf(*start)
        var failRenameTo: String? = null
        override fun exists(name: String) = name in files
        override fun rename(from: String, to: String): Boolean {
            if (to == failRenameTo || from !in files) return false
            files[to] = files.remove(from)!!
            return true
        }
        override fun delete(name: String) = files.remove(name) != null
    }

    private val t = "a.zip"

    @Test
    fun `a finished rewrite takes the archive's place and leaves nothing beside it`() {
        val fs = FakeFs(t to "old", ArchiveSwap.editing(t) to "new")
        ArchiveSwap.swap(fs, t)
        assertEquals(mapOf(t to "new"), fs.files)
    }

    @Test
    fun `a rename that fails leaves the original recoverable under its name`() {
        val fs = FakeFs(t to "old", ArchiveSwap.editing(t) to "new")
        fs.failRenameTo = t
        try {
            ArchiveSwap.swap(fs, t)
            fail("the swap reported success")
        } catch (expected: IOException) {
        }
        // Whatever the failing filesystem allowed, the next open puts the original back under its
        // name. The edit is lost; the archive is not.
        fs.failRenameTo = null
        ArchiveSwap.recover(fs, t)
        assertEquals(mapOf(t to "old"), fs.files)
    }

    @Test
    fun `killed while writing, the part goes and the original is untouched`() {
        val fs = FakeFs(t to "old", ArchiveSwap.editing(t) to "half")
        assertEquals(ArchiveSwap.Recovery.DROPPED_PART, ArchiveSwap.recover(fs, t))
        assertEquals(mapOf(t to "old"), fs.files)
    }

    @Test
    fun `killed with the original moved aside, the original comes back`() {
        val fs = FakeFs(ArchiveSwap.previous(t) to "old", ArchiveSwap.editing(t) to "new")
        assertEquals(ArchiveSwap.Recovery.RESTORED_ORIGINAL, ArchiveSwap.recover(fs, t))
        assertEquals(mapOf(t to "old"), fs.files)
    }

    @Test
    fun `killed after the new one went in, the new one stays`() {
        val fs = FakeFs(t to "new", ArchiveSwap.previous(t) to "old")
        assertEquals(ArchiveSwap.Recovery.KEPT_NEW, ArchiveSwap.recover(fs, t))
        assertEquals(mapOf(t to "new"), fs.files)
    }

    @Test
    fun `nothing left behind, nothing done`() {
        val fs = FakeFs(t to "old")
        assertEquals(ArchiveSwap.Recovery.NOTHING, ArchiveSwap.recover(fs, t))
        assertTrue(fs.exists(t))
        assertFalse(fs.exists(ArchiveSwap.previous(t)))
    }
}
