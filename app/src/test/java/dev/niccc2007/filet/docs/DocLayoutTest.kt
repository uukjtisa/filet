package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.DocLayout.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's decisions that can be wrong, and the package plumbing the readers share. */
class DocLayoutTest {

    @Test
    fun `each slide is one card from its marker to the next`() {
        val blocks = listOf(
            Block.Para("loose"),
            Block.Slide(1), Block.Para("a"), Block.SlideNotes("n"),
            Block.Slide(2),
            Block.Slide(3), Block.Para("b"),
        )
        assertEquals(
            listOf(Frame.NONE, Frame.TOP, Frame.MIDDLE, Frame.BOTTOM, Frame.ONLY, Frame.TOP, Frame.BOTTOM),
            DocLayout.slideFrames(blocks),
        )
    }

    @Test
    fun `a document with no slides has no cards`() {
        assertTrue(DocLayout.slideFrames(listOf(Block.Para("x"), Block.Rule)).all { it == Frame.NONE })
    }

    @Test
    fun `paper colours that vanish on a dark ground are refused, real colours kept`() {
        val dark = 0xFF1B1B1DL
        // Black body text set explicitly, which Word does constantly.
        assertFalse(DocLayout.readable(0xFF000000L, dark))
        // Navy: a heading colour chosen for white paper.
        assertFalse(DocLayout.readable(0xFF1F3864L, dark))
        // A red that is emphasis, and reads on both.
        assertTrue(DocLayout.readable(0xFFFF5050L, dark))
        // And on a light ground the same black is fine.
        assertTrue(DocLayout.readable(0xFF000000L, 0xFFF5F4F0L))
    }

    @Test
    fun `a highlighter gets ink that reads on it`() {
        assertEquals(0xFF000000L, DocLayout.inkOn(0xFFFFFF00L))
        assertEquals(0xFFFFFFFFL, DocLayout.inkOn(0xFF000080L))
    }

    @Test
    fun `a large picture is decoded at a power-of-two step under the cap`() {
        assertEquals(1, DocLayout.sampleSize(800, 1440))
        assertEquals(2, DocLayout.sampleSize(4000, 1440))
        assertEquals(4, DocLayout.sampleSize(6000, 1440))
    }

    @Test
    fun `a picture's placed width is converted from EMU`() {
        // 914400 EMU is an inch, which Office draws as 96 px at 100%.
        assertEquals(96f, DocLayout.pictureWidthDp(914_400), 0.01f)
        assertEquals(0f, DocLayout.pictureWidthDp(0), 0.0f)
    }

    // ── zoom ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a pinch is clamped at both bounds`() {
        assertEquals(DocZoom.MAX, DocZoom.pinch(2.9f, 1.5f), 0.0001f)
        assertEquals(DocZoom.MIN, DocZoom.pinch(0.8f, 0.5f), 0.0001f)
        assertEquals(1.1f, DocZoom.pinch(1f, 1.1f), 0.0001f)
        // Already at a bound, pushing further changes nothing - so no scroll is triggered.
        assertEquals(DocZoom.MAX, DocZoom.pinch(DocZoom.MAX, 1.2f), 0.0001f)
    }

    @Test
    fun `double-tap steps up and then comes home`() {
        assertEquals(1.5f, DocZoom.doubleTap(1f), 0.0001f)
        assertEquals(2.25f, DocZoom.doubleTap(1.5f), 0.0001f)
        assertEquals(1f, DocZoom.doubleTap(2.25f), 0.0001f)
        // From a pinched level past the last step, home rather than further in.
        assertEquals(1f, DocZoom.doubleTap(2.8f), 0.0001f)
        // From between steps, the next step up.
        assertEquals(2.25f, DocZoom.doubleTap(1.8f), 0.0001f)
    }

    @Test
    fun `the content under the fingers stays under the fingers`() {
        val focal = 600f
        val itemTop = 200f
        for (ratio in floatArrayOf(0.5f, 1.25f, 2f, 3f)) {
            val newTop = DocZoom.anchoredTop(focal, itemTop, ratio)
            // The point was (focal - itemTop) into the item; scaled, it is that times the ratio
            // into it - and that must land back on the focal line.
            assertEquals(focal, newTop + (focal - itemTop) * ratio, 0.001f)
        }
        // A pinch centred on an item's top edge leaves that edge where it is.
        assertEquals(itemTop, DocZoom.anchoredTop(itemTop, itemTop, 2f), 0.001f)
    }

    // ── the package ───────────────────────────────────────────────────────────────

    @Test
    fun `relationship targets resolve the three ways producers write them`() {
        assertEquals("ppt/media/image1.png", Ooxml.resolve("ppt/slides/slide1.xml", "../media/image1.png"))
        assertEquals("word/media/image1.png", Ooxml.resolve("word/document.xml", "media/image1.png"))
        assertEquals("word/media/image1.png", Ooxml.resolve("word/document.xml", "/word/media/image1.png"))
        assertEquals("word/_rels/document.xml.rels", Ooxml.relsPathOf("word/document.xml"))
    }

    @Test
    fun `an external relationship is kept as written`() {
        val rels = Ooxml.rels(
            OfficeFixtures.rels(
                Triple("rId1", "image", "media/a.png"),
                Triple("rId2", "hyperlink", "https://example.org/x"),
            ),
            "word/document.xml",
        )
        assertEquals(Ooxml.Rel("word/media/a.png", "image", external = false), rels["rId1"])
        assertEquals(Ooxml.Rel("https://example.org/x", "hyperlink", external = true), rels["rId2"])
        assertEquals(setOf("word/media/a.png"), Ooxml.imageTargets(rels))
    }

    @Test
    fun `list markers follow the file's own formats`() {
        assertEquals("iv", WordReader.formatNumber(4, "lowerRoman"))
        assertEquals("AA", WordReader.formatNumber(27, "upperLetter"))
        assertEquals("(3)", SlideReader.autoMarker("arabicParenBoth", 3))
        assertEquals("c)", SlideReader.autoMarker("alphaLcParenR", 3))
        val levels = mapOf(
            0 to ListLevel("decimal", "%1.", 1),
            1 to ListLevel("decimal", "%1.%2.", 1),
        )
        assertEquals("2.3.", WordReader.marker(levels, 1, intArrayOf(2, 3, 0, 0, 0, 0, 0, 0, 0)))
    }
}
