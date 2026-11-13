package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecLogTest {

    private val dump = listOf(
        "--------- beginning of main",
        "11-13 16:01:52.650 I/OpenGLRenderer(17196): gpu complete fence is not signaled",
        "11-13 16:01:52.657 D/CCodec  (17196): allocate(c2.qti.avc.decoder)",
        "11-13 16:01:52.690 E/CCodec  (17196): [c2.qti.avc.decoder] start: component start failed (C2_NO_MEMORY)",
        "11-13 16:01:52.691 W/MediaCodec(17196): start failed with -12",
        "11-13 16:01:52.692 I/CCodecConfig(17196): query failed after returning 12 values (BAD_INDEX)",
        "11-13 16:01:52.700 D/CCodecBufferChannel(17196): [c2.android.avc.decoder#818] Created input block pool",
        "11-13 16:01:52.701 E/ExoPlayerImpl(17196): something else entirely",
        "11-13 16:01:52.702 W/MediaCodecRenderer(17196): \tat androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.initCodec",
        "11-13 16:01:52.703 E/MediaCodecVideoRenderer(17196): Video codec error",
    )

    @Test fun `the codec layer's warnings, errors and failures are kept, in order`() {
        assertEquals(
            listOf(
                "11-13 16:01:52.690 E/CCodec  (17196): [c2.qti.avc.decoder] start: component start failed (C2_NO_MEMORY)",
                "11-13 16:01:52.691 W/MediaCodec(17196): start failed with -12",
                "11-13 16:01:52.692 I/CCodecConfig(17196): query failed after returning 12 values (BAD_INDEX)",
            ),
            CodecLog.relevant(dump),
        )
    }

    @Test fun `Media3's own renderers are not the codec layer, though their tags start the same`() {
        // The two lines at the end of the dump: a stack trace's frame and Media3's error line.
        // Matched as codec lines, a trace filled the whole capture.
        assertEquals(3, CodecLog.relevant(dump).size)
    }

    @Test fun `only the newest lines are kept when there are many`() {
        val many = (1..100).map { "11-13 16:01:52.${it.toString().padStart(3, '0')} E/CCodec  (1): line $it" }
        val kept = CodecLog.relevant(many, max = 5)
        assertEquals(5, kept.size)
        assertEquals("11-13 16:01:52.100 E/CCodec  (1): line 100", kept.last())
    }

    @Test fun `a tag is read from a time line, and a header line has none`() {
        assertEquals("CCodec", CodecLog.tagOf("11-13 16:01:52.690 E/CCodec  (17196): x"))
        assertEquals("MediaCodec", CodecLog.tagOf("11-13 16:01:52.691 W/MediaCodec(17196): y"))
        assertNull(CodecLog.tagOf("--------- beginning of main"))
    }
}
