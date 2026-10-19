package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Test

class WebClientLimitTest {

    @Test
    fun `absent value is the 50 MB default, and capped`() =
        assertEquals(WebClientLimit.Verdict.Capped(50_000_000L), WebClientLimit.verdict(true, null))

    @Test
    fun `a raised DWORD arrives signed and still reads as the maximum`() =
        assertEquals(WebClientLimit.Verdict.Fine(0xFFFF_FFFFL), WebClientLimit.verdict(true, -1))

    @Test
    fun `a value between is still capped and reported unsigned`() {
        assertEquals(WebClientLimit.Verdict.Capped(2_000_000_000L), WebClientLimit.verdict(true, 2_000_000_000))
        // 0xC0000000 - above Int.MAX_VALUE, so negative as a signed int.
        assertEquals(WebClientLimit.Verdict.Capped(0xC000_0000L), WebClientLimit.verdict(true, 0xC000_0000L.toInt()))
    }

    @Test
    fun `no key means no WebClient service`() =
        assertEquals(WebClientLimit.Verdict.NoWebClient, WebClientLimit.verdict(false, null))
}
