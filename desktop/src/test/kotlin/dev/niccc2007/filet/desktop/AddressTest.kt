package dev.niccc2007.filet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressTest {

    @Test fun `the address is the one string the phone takes`() {
        assertEquals(
            "http://192.168.100.17:8322/a/bcd234fgh5photos",
            Address.of("192.168.100.17", 8322, "bcd234fgh5photos"),
        )
    }

    @Test fun `an IPv6 host is bracketed`() {
        assertEquals("http://[fe80::1]:8322/a/abc", Address.of("fe80::1", 8322, "abc"))
        assertEquals("http://[fe80::1]:8322/", Address.base("fe80::1", 8322))
    }

    @Test fun `parse inverts construction`() {
        for ((h, p, c) in listOf(Triple("192.168.1.5", 8322, "k7p2photos"), Triple("fe80::1", 1024, "abc2"))) {
            assertEquals(Address.Parsed(h, p, c), Address.parse(Address.of(h, p, c)))
        }
    }

    @Test fun `parse refuses what is not an endpoint`() {
        assertNull(Address.parse("https://192.168.1.5:8322/a/abc"))
        assertNull(Address.parse("http://192.168.1.5/a/abc"))
        assertNull(Address.parse("http://192.168.1.5:99999/a/abc"))
        assertNull(Address.parse("http://192.168.1.5:8322/abc"))
        assertNull(Address.parse("http://192.168.1.5:8322/a/"))
        assertNull(Address.parse("http://192.168.1.5:8322/a/abc/sub"))
        assertNull(Address.parse("http://192.168.1.5:8322/a/ab-c"))
    }
}
