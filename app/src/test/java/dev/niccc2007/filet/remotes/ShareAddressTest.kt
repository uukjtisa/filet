package dev.niccc2007.filet.remotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAddressTest {

    @Test
    fun `a discovered address splits into the known part and an empty code`() {
        // What discovery actually hands over, and the state the form used to present as finished.
        val parts = ShareAddress.split("http://192.168.100.14:22222/a/")
        assertEquals("http://192.168.100.14:22222/a/", parts.prefix)
        assertEquals("", parts.code)
    }

    @Test
    fun `a complete address splits at the code`() {
        val parts = ShareAddress.split("http://192.168.100.14:22222/a/sarah8")
        assertEquals("http://192.168.100.14:22222/a/", parts.prefix)
        assertEquals("sarah8", parts.code)
    }

    @Test
    fun `splitting and joining is a round trip`() {
        // The whole basis for showing two boxes over one stored value: if this is ever lossy, the
        // two editors disagree and the saved address is whichever was touched last.
        for (address in listOf(
            "http://192.168.100.14:22222/a/sarah8",
            "http://phone:8321/a/KTR9",
            "http://10.0.0.2/a/",
        )) {
            val p = ShareAddress.split(address)
            assertEquals(address, ShareAddress.join(p.prefix, p.code))
        }
    }

    @Test
    fun `something that is not a share address is left alone`() {
        val parts = ShareAddress.split("192.168.1.5")
        assertEquals("192.168.1.5", parts.prefix)
        assertEquals("", parts.code)
        assertEquals("", ShareAddress.split("").code)
    }

    @Test
    fun `joining never doubles or drops the separator`() {
        // Both boxes write through join, and each can supply its own slash.
        assertEquals("http://p:1/a/CODE", ShareAddress.join("http://p:1/a/", "CODE"))
        assertEquals("http://p:1/a/CODE", ShareAddress.join("http://p:1/a", "CODE"))
        assertEquals("http://p:1/a/CODE", ShareAddress.join("http://p:1/a/", "/CODE"))
        assertEquals("http://p:1/a/CODE", ShareAddress.join("http://p:1/a/", "  CODE  "))
    }

    @Test
    fun `an empty code leaves the address ready for one`() {
        assertEquals("http://p:1/a/", ShareAddress.join("http://p:1/a/", ""))
    }

    @Test
    fun `a path with more than one marker splits at the last`() {
        // Contrived, but the rule has to be decided rather than discovered later: the code is the
        // tail, so the split is the LAST marker.
        val parts = ShareAddress.split("http://p:1/a/x/a/CODE")
        assertEquals("CODE", parts.code)
    }

    @Test
    fun `waiting for a code is told apart from not being an address`() {
        // A form somebody has not finished is not a form somebody has got wrong, and showing the
        // second for the first is how a setup screen feels hostile.
        assertTrue(ShareAddress.awaitingCode("http://192.168.100.14:22222/a/"))
        assertFalse(ShareAddress.awaitingCode("http://192.168.100.14:22222/a/sarah8"))
        assertFalse(ShareAddress.awaitingCode("192.168.1.5"))
        assertFalse(ShareAddress.awaitingCode(""))
    }
}
