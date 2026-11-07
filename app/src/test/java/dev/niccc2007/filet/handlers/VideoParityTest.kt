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
