package dev.niccc2007.filet.webdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pull of the index may be served again.
 *
 * The cache exists because building the views was re-querying 4000 rows and resolving a path per
 * row on every request, which took 3.3s to return nine folder entries. These are the edges of the
 * one decision that can be wrong.
 */
class DavViewsSnapshotTest {

    private val ttl = DavViews.SNAPSHOT_MS

    @Test
    fun `a snapshot just taken is fresh`() {
        assertTrue(DavViews.snapshotFresh(takenAt = 1_000_000, now = 1_000_000))
        assertTrue(DavViews.snapshotFresh(takenAt = 1_000_000, now = 1_000_000 + ttl / 2))
    }

    @Test
    fun `it goes stale exactly at the window, not after it`() {
        assertTrue(DavViews.snapshotFresh(1_000_000, 1_000_000 + ttl - 1))
        assertFalse(DavViews.snapshotFresh(1_000_000, 1_000_000 + ttl))
        assertFalse(DavViews.snapshotFresh(1_000_000, 1_000_000 + ttl + 1))
    }

    @Test
    fun `nothing pulled yet is never fresh`() {
        // An uninitialised cache would otherwise read as a valid empty snapshot, and the views
        // would serve nothing at all until the clock happened to move past the window.
        assertFalse(DavViews.snapshotFresh(takenAt = 0L, now = 0L))
        assertFalse(DavViews.snapshotFresh(takenAt = 0L, now = 5_000_000))
    }

    @Test
    fun `a clock that went backwards is not fresh`() {
        // Time can move backwards on a phone - an NTP correction, or the user changing it. The
        // arithmetic would otherwise give a negative age, which is below any window and would pin
        // a stale snapshot as fresh forever.
        assertFalse(DavViews.snapshotFresh(takenAt = 5_000_000, now = 4_000_000))
    }

    @Test
    fun `the window is long enough for one navigation and short enough to notice a new file`() {
        // Explorer fires several PROPFINDs per click, so the window has to outlast a burst; and a
        // file added while somebody browses has to appear without a remount.
        assertTrue("a burst of requests within a second must all hit", ttl >= 1_000L)
        assertTrue("a new file must not stay hidden for long", ttl <= 15_000L)
    }

    @Test
    fun `a caller can pass its own window`() {
        assertTrue(DavViews.snapshotFresh(100, 150, ttl = 100))
        assertFalse(DavViews.snapshotFresh(100, 250, ttl = 100))
    }
}
