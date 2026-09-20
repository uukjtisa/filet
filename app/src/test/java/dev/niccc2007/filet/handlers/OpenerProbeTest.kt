package dev.niccc2007.filet.handlers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not rebuilding the opener list every time the sheet opens.
 *
 * Bug identified: "open in another app" took seconds. Tapping "show all" ran the whole probe
 * inside `remember { }`, which executes during composition on the main thread - one
 * `queryIntentActivities` per probed type plus a label load per installed app, so roughly a
 * hundred and fifty inter-process calls, every time, for an answer that only changes when an
 * app is installed or removed.
 *
 * The probe itself needs a `PackageManager` and cannot run here. What can be checked is the
 * rule that decides whether to run it at all, and getting that wrong in either direction is
 * either the original slowness or a picker that never notices a newly installed app.
 */
class OpenerProbeTest {

    private val T = 1_700_000_000_000L

    @Test
    fun `a catalogue that has never been built is built`() {
        assertTrue(OpenerCatalog.stale(builtAt = 0, invalidatedAt = 0, now = T))
    }

    @Test
    fun `a fresh catalogue is reused`() {
        // The whole fix. Opening the sheet twice in a row must do the work once.
        assertFalse(OpenerCatalog.stale(builtAt = T, invalidatedAt = 0, now = T + 1000))
    }

    @Test
    fun `installing an app rebuilds it`() {
        // Otherwise an app installed a minute ago is missing from the picker with no way to
        // make it appear.
        assertTrue(OpenerCatalog.stale(builtAt = T, invalidatedAt = T + 500, now = T + 1000))
    }

    @Test
    fun `an invalidation from before the build does not force a rebuild`() {
        // The control for the test above: a package change already accounted for must not keep
        // rebuilding forever, which would be the original slowness wearing a cache.
        assertFalse(OpenerCatalog.stale(builtAt = T + 500, invalidatedAt = T, now = T + 1000))
    }

    @Test
    fun `it is rebuilt once the timeout passes`() {
        // A backstop for a broadcast that never arrived.
        assertTrue(OpenerCatalog.stale(builtAt = T, invalidatedAt = 0, now = T + OpenerCatalog.TTL_MS))
        assertFalse(OpenerCatalog.stale(builtAt = T, invalidatedAt = 0, now = T + OpenerCatalog.TTL_MS - 1))
    }

    @Test
    fun `a clock that goes backwards does not freeze the catalogue forever`() {
        // A manual time change would otherwise leave `now - builtAt` negative for as long as
        // the process lives, and the list would never rebuild again.
        assertTrue(OpenerCatalog.stale(builtAt = T, invalidatedAt = 0, now = T - 60_000))
    }

    @Test
    fun `the timeout is long enough to be a backstop rather than the mechanism`() {
        // If this were short the cache would be doing nothing and the sheet would be slow
        // again on a timer.
        assertTrue("a TTL under a minute is not a cache", OpenerCatalog.TTL_MS >= 60_000)
    }
}
