package dev.niccc2007.filet.handlers

import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hardware decoder's "past its limits" read as "handled", and nothing else touched. */
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

    @Test fun `the bits beside the answer survive the upgrade`() {
        // Adaptive, tunneling, hardware-acceleration and decoder-support flags ride in the same int.
        val others = RendererCapabilities.ADAPTIVE_SEAMLESS or
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED or
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        val upgraded = HardwareFirst.upgrade(others or C.FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(others, upgraded and support.inv())
        assertEquals(C.FORMAT_HANDLED, upgraded and support)
    }
}
