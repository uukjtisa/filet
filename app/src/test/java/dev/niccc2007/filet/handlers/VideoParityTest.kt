package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The features a player behind the video viewer must keep, and the gate on the default. */
class VideoParityTest {

    @Test
    fun `the list is the spec's ten, so none can be dropped from it quietly`() {
        assertEquals(
            listOf(
                "seek", "speed", "frame step", "double-tap skip", "the sound effects", "8D pan",
                "nightcore and vaporwave rate", "picture-in-picture", "the tap-to-play preview",
                "resume points",
            ),
            VideoFeature.entries.map { it.label },
        )
    }

    @Test
    fun `the engine every video opens in passes every feature`() {
        assertTrue(
            "the default lacks ${VideoParity.missing(VideoParity.DEFAULT).map { it.label }}",
            VideoParity.canBeDefault(VideoParity.DEFAULT),
        )
    }

    @Test
    fun `Media3 cannot be the default until it passes every feature`() {
        assertEquals(EngineKind.PLATFORM, VideoParity.DEFAULT)
        assertTrue(VideoFeature.PAN_8D in VideoParity.missing(EngineKind.MEDIA3))
        assertEquals(false, VideoParity.canBeDefault(EngineKind.MEDIA3))
    }

    @Test
    fun `every engine answers for every feature`() {
        // Exhaustive in the code; here, that no answer throws or is skipped.
        for (engine in EngineKind.entries) {
            for (feature in VideoFeature.entries) VideoParity.passes(engine, feature)
            assertEquals(
                VideoFeature.entries.size,
                VideoFeature.entries.count { VideoParity.passes(engine, it) } + VideoParity.missing(engine).size,
            )
        }
    }
}
