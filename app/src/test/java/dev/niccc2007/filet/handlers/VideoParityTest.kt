package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The features a player behind the video viewer must keep, and which one a video opens in. */
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
    fun `a video opens in Filet's player unless another is chosen`() {
        assertEquals(EngineKind.MEDIA3, VideoParity.DEFAULT)
    }

    @Test
    fun `the phone's player, one tap away in the menu, passes every feature`() {
        assertTrue(
            "the phone's player lacks ${VideoParity.missing(EngineKind.PLATFORM).map { it.label }}",
            VideoParity.canBeDefault(EngineKind.PLATFORM),
        )
    }

    @Test
    fun `what Filet's player still lacks stays on the list`() {
        // 8D's sweep needs an audio processor this player does not have yet.
        assertTrue(VideoFeature.PAN_8D in VideoParity.missing(EngineKind.MEDIA3))
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
