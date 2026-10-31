package dev.niccc2007.filet.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceShapeTest {

    @Test fun `a phone on its side gets two columns`() {
        // The reported case: every part stacked into 360dp of height, so the artwork became a
        // stamp and the transport went off the bottom.
        assertTrue(FaceShape.sideBySide(780f, 360f))
        assertTrue(FaceShape.sideBySide(892f, 412f))
    }

    @Test fun `a phone upright keeps stacking`() {
        assertFalse(FaceShape.sideBySide(360f, 780f))
        assertFalse(FaceShape.sideBySide(412f, 892f))
    }

    @Test fun `a large tablet in landscape gets two columns too`() {
        // Stacking here was the portrait layout squashed vertically. The shape decides.
        assertTrue(FaceShape.sideBySide(1280f, 800f))
        assertTrue(FaceShape.sideBySide(1024f, 768f))
        // And on its end, it stacks.
        assertFalse(FaceShape.sideBySide(800f, 1280f))
    }

    @Test fun `a small tablet on its side still gets two columns`() {
        // Landscape and under the height a stacked player needs, so the fault applies here too.
        assertTrue(FaceShape.sideBySide(960f, 540f))
    }

    @Test fun `a square screen stacks`() {
        // Not wider than it is tall, so there is nothing to gain and a Row would be arbitrary.
        assertFalse(FaceShape.sideBySide(600f, 600f))
    }

    @Test fun `a nearly square window stacks`() {
        // A foldable's inner screen, or a split window near 1:1: two columns would be arbitrary.
        assertFalse(FaceShape.sideBySide(884f, 832f))
        assertTrue(FaceShape.sideBySide(FaceShape.LANDSCAPE_RATIO * 500f, 500f))
        assertFalse(FaceShape.sideBySide(FaceShape.LANDSCAPE_RATIO * 500f - 1f, 500f))
    }

    @Test fun `the artwork takes slightly under half the width`() {
        assertTrue(FaceShape.ART_SHARE > 0.4f)
        assertTrue(FaceShape.ART_SHARE < 0.5f)
    }
}
