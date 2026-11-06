package dev.niccc2007.filet.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JustRemovedTest {
    private val disk = HashSet<String>()
    private var now = 1_000_000L
    private val gone = JustRemoved(exists = { it in disk }, clock = { now })

    @Test fun aDeletedFileIsHeldTheMomentItIsNoted() {
        gone.noted(listOf("/s/DCIM/a.jpg"))
        assertEquals(setOf("/s/DCIM/a.jpg"), gone.paths.value)
    }

    @Test fun aWrittenFileIsNotHeld() {
        disk += "/s/DCIM/b.jpg"
        gone.noted(listOf("/s/DCIM/b.jpg"))
        assertTrue(gone.paths.value.isEmpty())
    }

    @Test fun aMoveHoldsTheSourceAndNotTheDestination() {
        disk += "/s/Pictures/a.jpg"
        gone.noted(listOf("/s/DCIM/a.jpg", "/s/Pictures/a.jpg"))
        assertEquals(setOf("/s/DCIM/a.jpg"), gone.paths.value)
    }

    @Test fun writingBackToTheSamePlaceReleasesIt() {
        gone.noted(listOf("/s/DCIM/a.jpg"))
        disk += "/s/DCIM/a.jpg"
        gone.noted(listOf("/s/DCIM/a.jpg"))
        assertTrue(gone.paths.value.isEmpty())
    }

    @Test fun itsOwnScanDoesNotReleaseItWhileItIsStillAbsent() {
        // The lists re-read after the scan, and releasing first would redraw the deleted row.
        gone.noted(listOf("/s/DCIM/a.jpg"))
        gone.recheck()
        assertEquals(setOf("/s/DCIM/a.jpg"), gone.paths.value)
    }

    @Test fun aRecheckReleasesWhatAnotherAppPutBack() {
        gone.noted(listOf("/s/DCIM/a.jpg", "/s/DCIM/c.jpg"))
        disk += "/s/DCIM/c.jpg"
        gone.recheck()
        assertEquals(setOf("/s/DCIM/a.jpg"), gone.paths.value)
    }

    @Test fun oldEntriesExpire() {
        gone.noted(listOf("/s/DCIM/a.jpg"))
        now += JustRemoved.KEEP_MS + 1
        gone.recheck()
        assertTrue(gone.paths.value.isEmpty())
    }

    @Test fun theSetIsBoundedOldestFirst() {
        gone.noted((0..JustRemoved.MAX).map { "/s/f$it" })
        assertEquals(JustRemoved.MAX, gone.paths.value.size)
        assertFalse("/s/f0" in gone.paths.value)
        assertTrue("/s/f${JustRemoved.MAX}" in gone.paths.value)
    }

    @Test fun aFolderCoversWhatIsInsideIt() {
        val g = setOf("/s/DCIM/Trip")
        assertTrue(JustRemoved.covers(g, "/s/DCIM/Trip"))
        assertTrue(JustRemoved.covers(g, "/s/DCIM/Trip/day1/a.jpg"))
        assertFalse(JustRemoved.covers(g, "/s/DCIM/Trip2/a.jpg"))
        assertFalse(JustRemoved.covers(g, "/s/DCIM"))
        assertFalse(JustRemoved.covers(emptySet(), "/s/DCIM/Trip/a.jpg"))
    }

    @Test fun aCategoryRowsUriMatchesThePlainPathHeld() {
        val uri = dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/0/DCIM/a.jpg").toString()
        assertEquals("/storage/emulated/0/DCIM/a.jpg", JustRemoved.plain(uri))
        assertTrue(JustRemoved.covers(setOf("/storage/emulated/0/DCIM"), JustRemoved.plain(uri)))
        assertFalse(JustRemoved.covers(setOf("/x"), JustRemoved.plain("smb://host/x/a.jpg")))
    }

    @Test fun nothingGoneIsTheSameListUntouched() {
        val rows = listOf("/s/a", "/s/b")
        assertSame(rows, JustRemoved.without(rows, emptySet()) { it })
        assertEquals(listOf("/s/b"), JustRemoved.without(rows, setOf("/s/a")) { it })
    }
}
