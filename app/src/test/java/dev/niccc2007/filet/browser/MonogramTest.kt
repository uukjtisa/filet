package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an extension may stand in for an icon.
 *
 * The interesting cases are all refusals. Anything that produces a token is easy and obviously
 * right; the value of the function is the set of things it declines to guess at, because each of
 * those would otherwise put a confident wrong word on a row.
 */
class MonogramTest {

    @Test
    fun `an ordinary extension becomes its own uppercase token`() {
        assertEquals("PDF", Monogram.of("pdf"))
        assertEquals("ISO", Monogram.of("iso"))
        assertEquals("SAV", Monogram.of("sav"))
    }

    @Test
    fun `case does not matter coming in`() {
        assertEquals("PDF", Monogram.of("PDF"))
        assertEquals("PDF", Monogram.of("Pdf"))
    }

    @Test
    fun `whitespace around it is ignored`() {
        assertEquals("BIN", Monogram.of("  bin "))
    }

    @Test
    fun `a digit inside one is kept`() {
        // .mp4 is previewable so never reaches here, but .m4b and .7z do.
        assertEquals("7Z", Monogram.of("7z"))
        assertEquals("M4B", Monogram.of("m4b"))
    }

    @Test
    fun `five characters is the longest that is drawn`() {
        assertEquals("LATEX", Monogram.of("latex"))
        assertEquals(5, Monogram.MAX)
    }

    @Test
    fun `an extension too long to draw gets no token`() {
        // The reported case. Cutting it to MCAD would name a type that does not exist, and
        // shrinking it further makes it unreadable - so the generic glyph stands instead.
        assertNull(Monogram.of("mcaddon"))
        assertNull(Monogram.of("appxbundle"))
    }

    @Test
    fun `no extension at all gets no token`() {
        assertNull(Monogram.of(""))
        assertNull(Monogram.of("   "))
    }

    @Test
    fun `punctuation is declined rather than stripped`() {
        // Stripping would turn .c++ into "C" and claim a C source file.
        assertNull(Monogram.of("c++"))
        assertNull(Monogram.of("tar.gz"))
        assertNull(Monogram.of("a-b"))
    }

    @Test
    fun `a purely numeric extension gets no token`() {
        // A split archive's .001 and a rotated log's .7 say nothing about what the file is.
        assertNull(Monogram.of("001"))
        assertNull(Monogram.of("7"))
    }

    @Test
    fun `every drawable token has a size and it shrinks as it lengthens`() {
        val sizes = listOf("A", "AB", "ABC", "ABCD", "ABCDE").map { Monogram.sizeSp(it) }
        assertEquals(5, sizes.size)
        for (i in 1 until sizes.size) {
            assertTrue("size must not grow at length ${i + 1}", sizes[i] <= sizes[i - 1])
        }
        assertTrue("five characters must still be given a positive size", sizes.last() > 0f)
    }

    @Test
    fun `stripping instead of declining is the fault being prevented`() {
        // The negative control: what a lenient version would produce, stated as the thing the
        // real one must not return.
        val lenient = { s: String -> s.filter { it.isLetterOrDigit() }.take(4).uppercase() }
        assertEquals("MCAD", lenient("mcaddon"))
        assertEquals("C", lenient("c++"))
        assertNull(Monogram.of("mcaddon"))
        assertNull(Monogram.of("c++"))
    }
}
