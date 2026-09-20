package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share button inside a viewer.
 *
 * Reported: sharing from the built-in image, video, audio and code viewers only ever offered
 * other apps, so Filet's own network share - the one that needs nothing installed at the other
 * end - was unreachable from the app's own share button.
 */
class ShareTargetTest {

    @Test
    fun `a local file can be shared over the network`() {
        assertTrue(shareTargets(local = true).contains(ShareTarget.NETWORK))
    }

    @Test
    fun `handing it to another app stays first`() {
        // Still right most of the time. This is a choice, not a replacement.
        assertEquals(ShareTarget.APPS, shareTargets(local = true).first())
    }

    @Test
    fun `a file with no path on this device offers only apps`() {
        // Inside an archive, or on a remote. There is nothing to serve, so offering to serve
        // it would be a control that cannot act.
        assertEquals(listOf(ShareTarget.APPS), shareTargets(local = false))
    }

    @Test
    fun `a single option is acted on rather than presented`() {
        // A menu over one item is a tap nobody asked for.
        assertFalse(needsShareChoice(local = false))
        assertTrue(needsShareChoice(local = true))
    }

    @Test
    fun `every target says what it is`() {
        for (t in ShareTarget.entries) {
            assertTrue(t.name, t.label.isNotBlank() && t.detail.isNotBlank())
        }
    }

    @Test
    fun `nothing is offered twice`() {
        for (local in listOf(true, false)) {
            val t = shareTargets(local)
            assertEquals(t.size, t.distinct().size)
        }
    }
}
