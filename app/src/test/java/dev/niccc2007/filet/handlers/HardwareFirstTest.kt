package dev.niccc2007.filet.handlers

import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hardware decoder's "past its limits" read as "handled", and hardware put first in the renderer's own list. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HardwareFirstTest {

    private val support = RendererCapabilities.FORMAT_SUPPORT_MASK

    @Test fun `past its limits becomes handled`() {
        assertEquals(C.FORMAT_HANDLED, HardwareFirst.upgrade(C.FORMAT_EXCEEDS_CAPABILITIES) and support)
    }

    @Test fun `every other answer is left as it was`() {
        for (answer in listOf(
            C.FORMAT_HANDLED,
            C.FORMAT_UNSUPPORTED_TYPE,
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            C.FORMAT_UNSUPPORTED_DRM,
        )) {
            assertEquals(answer, HardwareFirst.upgrade(answer))
        }
    }

    /** A decoder as the renderer lists it: its name, whether it is hardware, whether it knows the profile. */
    private data class D(val name: String, val hw: Boolean, val decodes: Boolean = true)

    private fun order(vararg d: D) = HardwareFirst.order(d.toList(), { it.hw }, { it.decodes }).map { it.name }

    @Test fun `the hardware decoder goes ahead of the software one that declared the file within its limits`() {
        // The nova 10's list for the 2160 x 3402, 60 fps H.264 file, as Media3 sorts it: Google's
        // software decoder first, because it alone declares the file within its limits.
        assertEquals(
            listOf("c2.qti.avc.decoder", "c2.qti.avc.decoder.low_latency", "c2.android.avc.decoder"),
            order(
                D("c2.android.avc.decoder", hw = false),
                D("c2.qti.avc.decoder", hw = true),
                D("c2.qti.avc.decoder.low_latency", hw = true),
            ),
        )
    }

    @Test fun `a hardware decoder that does not know the profile stays where the renderer put it`() {
        // 10-bit H.264: most hardware draws garbage for it rather than failing, so software leads.
        assertEquals(
            listOf("c2.android.avc.decoder", "c2.qti.avc.decoder"),
            order(D("c2.android.avc.decoder", hw = false), D("c2.qti.avc.decoder", hw = true, decodes = false)),
        )
    }

    @Test fun `each half keeps the renderer's order, and a list already hardware-first is left alone`() {
        assertEquals(
            listOf("hw.a", "hw.b", "sw.a", "sw.b"),
            order(D("sw.a", false), D("hw.a", true), D("sw.b", false), D("hw.b", true)),
        )
        assertEquals(listOf("hw.a", "sw.a"), order(D("hw.a", true), D("sw.a", false)))
        assertEquals(emptyList<String>(), order())
    }

    @Test fun `the bits beside the answer survive the upgrade`() {
        // Adaptive, tunneling, hardware-acceleration and decoder-support flags ride in the same int.
        val others = RendererCapabilities.ADAPTIVE_SEAMLESS or
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED or
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        val upgraded = HardwareFirst.upgrade(others or C.FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(others, upgraded and support.inv())
        assertEquals(C.FORMAT_HANDLED, upgraded and support)
    }

    @Test fun `a test start is one question per decoder, kind of file, size and rate`() {
        val a = HardwareProbe.key("c2.qti.avc.decoder", "video/avc", 2160, 3402, 60.000004f)
        assertEquals("c2.qti.avc.decoder|video/avc|2160x3402|60", a)
        // A frame rate a hair off is the same question; another size or decoder is not.
        assertEquals(a, HardwareProbe.key("c2.qti.avc.decoder", "video/avc", 2160, 3402, 59.94f))
        assertTrue(a != HardwareProbe.key("c2.qti.avc.decoder", "video/avc", 1080, 1920, 60f))
        assertTrue(a != HardwareProbe.key("c2.qti.avc.decoder.low_latency", "video/avc", 2160, 3402, 60f))
        assertEquals("d|null|0x0|0", HardwareProbe.key("d", null, 0, 0, -1f))
    }

    @Test fun `an answer is kept for the run, a busy one only for the opening that got it`() {
        assertTrue(HardwareProbe.keep(busy = false, ageMs = 0))
        assertTrue(HardwareProbe.keep(busy = false, ageMs = 3L * 24 * 60 * 60 * 1000))
        assertTrue(HardwareProbe.keep(busy = true, ageMs = 1_500))
        assertFalse(HardwareProbe.keep(busy = true, ageMs = HardwareProbe.BUSY_KEEP_MS))
        // A clock that went back is not a reason to trust a busy answer.
        assertFalse(HardwareProbe.keep(busy = true, ageMs = -5))
    }

    @Test fun `a refusal is said in words, with the platform's code read`() {
        assertEquals(
            "not enough resources (the decoder is busy): Error 0xfffffff4",
            HardwareFirst.reasonWords("CodecException", "Error 0xfffffff4\nmore detail", 1100),
        )
        assertEquals("error 7", HardwareFirst.reasonWords("CodecException", null, 7))
        assertEquals("configure failed", HardwareFirst.reasonWords("IllegalArgumentException", "configure failed", null))
        assertEquals("IllegalStateException", HardwareFirst.reasonWords("IllegalStateException", "  ", null))
    }
}
