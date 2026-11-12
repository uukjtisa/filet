package dev.niccc2007.filet.handlers

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities

/**
 * The phone's hardware decoder tried first, even on a file past what it declares - what VLC does.
 *
 * Bug identified here: a 2160 x 3402, 60 fps H.264 file played at a few frames a second with the
 * heavy-video notice up. The phone's hardware decoder declares a lower level than the file's, so it
 * reported the file as past its limits; FFmpeg reported it as handled; and the track selector,
 * which prefers "handled" over "past the limits", gave the picture to FFmpeg. In software, at that
 * size and rate, it drops most frames on any phone. Declared limits are conservative: a hardware
 * decoder usually plays a file somewhat past them, and VLC owes much of its smoothness to trying.
 *
 * So the hardware renderer's "past its limits" is read as "handled", and it wins on being hardware.
 * If it then fails to start, Media3 falls back to the next decoder; if it starts and draws nothing,
 * the first-frame watch moves the video to [VideoRung.FFMPEG]. Trying costs a moment; not trying
 * cost every frame.
 */
@OptIn(UnstableApi::class)
internal object HardwareFirst {

    /** [caps] with "past its limits" read as "handled"; every other answer, and every other bit, as it was. */
    fun upgrade(caps: Int): Int =
        if (caps and RendererCapabilities.FORMAT_SUPPORT_MASK == C.FORMAT_EXCEEDS_CAPABILITIES) {
            (caps and RendererCapabilities.FORMAT_SUPPORT_MASK.inv()) or C.FORMAT_HANDLED
        } else {
            caps
        }

    /** [renderer], answering for formats through [upgrade]. Everything else goes straight through. */
    fun wrap(renderer: Renderer): Renderer = object : ForwardingRenderer(renderer) {
        private val inner = renderer.capabilities
        private val caps = object : RendererCapabilities by inner {
            override fun supportsFormat(format: Format): Int = upgrade(inner.supportsFormat(format))
            override fun setListener(listener: RendererCapabilities.Listener) = inner.setListener(listener)
            override fun clearListener() = inner.clearListener()
        }

        override fun getCapabilities(): RendererCapabilities = caps
    }
}
