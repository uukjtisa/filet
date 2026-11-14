package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastLinkTest {
    @Test fun `a file, a folder and several files are each a fast link`() {
        assertEquals(FastLink.Parsed("http://192.168.1.23:8080", "192.168.1.23", "kaR7m4"), FastLink.parse("http://192.168.1.23:8080/f/Ta9fQ2?k=kaR7m4"))
        assertEquals("k1", FastLink.parse("http://10.0.0.5:8080/zd/abc?k=k1")?.key)
        assertEquals("k2", FastLink.parse(" http://172.20.1.9:8081/z?k=k2 ")?.key)
    }

    @Test fun `anything else is not`() {
        for (t in listOf(
            "http://192.168.1.23:8080/f/Ta9fQ2", "https://192.168.1.23:8080/f/a?k=b", "http://example.com:8080/f/a?k=b",
            "http://8.8.8.8:8080/f/a?k=b", "http://192.168.1.23/f/a?k=b", "http://192.168.1.23:8080/a/abc/?k=b",
            "http://192.168.1.23:8080/f/a?k=", "not a link",
        )) assertNull(t, FastLink.parse(t))
    }

    @Test fun `push answers and pop-up settings`() {
        assertEquals(PushAnswer.BLOCKED, PushRules.answer(blocked = true, always = true, paired = true, popupsOn = true))
        assertEquals(PushAnswer.ACCEPTED, PushRules.answer(blocked = false, always = true, paired = true, popupsOn = false))
        assertEquals(PushAnswer.ASK, PushRules.answer(blocked = false, always = true, paired = false, popupsOn = true))
        assertEquals(PushAnswer.HELD, PushRules.answer(blocked = false, always = false, paired = false, popupsOn = false))
        assertEquals(Popups.ON, PushRules.popups(null, 10))
        assertEquals(Popups.OFF, PushRules.popups("off", 10))
        assertEquals(Popups.OFF_HOUR, PushRules.popups("until:20", 10))
        assertEquals(Popups.ON, PushRules.popups("until:20", 30))
    }

    @Test fun `a waiting push goes stale after ten minutes or two missed checks`() {
        assertEquals(false, PushRules.stale(0, PushRules.STALE_MS, 1))
        assertEquals(true, PushRules.stale(0, PushRules.STALE_MS + 1, 0))
        assertEquals(true, PushRules.stale(0, 1, PushRules.MISSES))
    }

    @Test fun `a landing name is safe and clear of what is there`() {
        assertEquals("a_b.jpg", PushRules.landName("a/b.jpg") { false })
        assertEquals("Photo (2).jpg", PushRules.landName("Photo.jpg") { it == "Photo.jpg" })
        assertEquals("notes (3)", PushRules.landName("notes") { it == "notes" || it == "notes (2)" })
    }
}
