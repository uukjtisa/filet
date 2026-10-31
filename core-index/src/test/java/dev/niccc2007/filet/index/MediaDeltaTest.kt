package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index kept fresh by the media store's generations. SEARCH.md §14.6. */
class MediaDeltaTest {

    @Test
    fun `a first look is a baseline, not a read of everything`() {
        assertEquals(MediaDelta.Plan.BASELINE, MediaDelta.plan(stored = -1, current = 48_211))
    }

    @Test
    fun `nothing written is nothing read`() {
        assertEquals(MediaDelta.Plan.NOTHING, MediaDelta.plan(stored = 48_211, current = 48_211))
    }

    @Test
    fun `a newer generation is a delta`() {
        assertEquals(MediaDelta.Plan.DELTA, MediaDelta.plan(stored = 48_211, current = 48_230))
    }

    @Test
    fun `a generation going backwards is a rebuilt media store`() {
        assertEquals(MediaDelta.Plan.RESET, MediaDelta.plan(stored = 48_211, current = 12))
    }

    @Test
    fun `a burst in one folder is one folder to read`() {
        val shots = (1..40).asSequence().map { "/storage/emulated/0/DCIM/Camera/IMG_$it.jpg" }
        assertEquals(listOf("/storage/emulated/0/DCIM/Camera"), MediaDelta.folders(shots))
    }

    @Test
    fun `too many folders hands the job to the bounded pass`() {
        val restore = (1..500).asSequence().map { "/storage/emulated/0/Backup/$it/a.jpg" }
        assertNull(MediaDelta.folders(restore))
        assertEquals(3, MediaDelta.folders(restore.take(3))!!.size)
    }

    @Test
    fun `a path with no folder is not read as the root`() {
        assertEquals(emptyList<String>(), MediaDelta.folders(sequenceOf("orphan.jpg", "/x.jpg")))
    }

    @Test
    fun `the window is the number the setting shows`() {
        val at = 1_000_000L
        assertEquals(at + 10 * 60_000L, ListenWindow.releaseAt(at, 10))
        assertEquals(at + 2 * 60_000L, ListenWindow.releaseAt(at, 2))
        assertEquals(at, ListenWindow.releaseAt(at, 0))
    }

    @Test
    fun `an unknown stored value is the default`() {
        assertEquals(ListenWindow.DEFAULT, ListenWindow.normalise(7))
        assertEquals("10 min", ListenWindow.label(99))
        assertEquals("Off", ListenWindow.label(0))
    }

    @Test
    fun `the sentence says what the setting does and promises nothing after`() {
        assertTrue(ListenWindow.describe(5).contains("5 minutes"))
        assertTrue(ListenWindow.describe(0).contains("on return"))
    }
}
