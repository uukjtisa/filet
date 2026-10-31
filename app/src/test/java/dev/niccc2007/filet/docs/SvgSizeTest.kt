package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size an SVG says it is, which is the size a badge is drawn at. See [SvgSize]. */
class SvgSizeTest {

    private fun size(svg: String) = SvgSize.declared(svg)

    @Test
    fun `a badge's own width and height are its size, with no density in them`() {
        // The shape of a shields.io badge's root tag. Bug identified here: this size was worked
        // back out of the decoded bitmap, and came out a third of it.
        val badge = """<svg xmlns="http://www.w3.org/2000/svg" width="90" height="20" role="img" aria-label="build: passing">"""
        assertEquals(90f to 20f, size(badge))
    }

    @Test
    fun `pixels are pixels and a viewBox fills in the side that is missing`() {
        assertEquals(96f to 48f, size("""<svg width="96px" height="48px">"""))
        assertEquals(200f to 100f, size("""<svg width="200" viewBox="0 0 40 20">"""))
        assertEquals(50f to 25f, size("""<svg height='25' viewBox='0,0,40,20'>"""))
        assertEquals(40f to 20f, size("""<svg viewBox="0 0 40 20">"""))
    }

    @Test
    fun `a size that is not in pixels says nothing, and the decoder's is used`() {
        assertNull(size("""<svg width="100%" height="2em">"""))
        assertNull(size("""<svg>"""))
        assertNull(size("not an svg"))
    }

    @Test
    fun `only the root tag counts, not a width further down`() {
        val svg = """<?xml version="1.0"?><svg viewBox="0 0 10 5"><rect width="300" height="300"/></svg>"""
        assertEquals(10f to 5f, size(svg))
    }

    @Test
    fun `a stroke width is not the width`() {
        assertEquals(30f to 15f, size("""<svg stroke-width="9" viewBox="0 0 30 15">"""))
    }
}
