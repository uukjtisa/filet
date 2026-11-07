package dev.niccc2007.filet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a list has a back-to-top pill. */
class BackToTopTest {

    @Test
    fun `a list under three screens long never has one`() {
        // 15 rows to a screen, 44 rows: scrolled to its very end.
        assertFalse(BackToTop.shows(first = 29, visible = 15, total = 44))
    }

    @Test
    fun `a list three screens long has one once past its first screen`() {
        assertFalse(BackToTop.shows(first = 14, visible = 15, total = 45))
        assertTrue(BackToTop.shows(first = 15, visible = 15, total = 45))
    }

    @Test
    fun `a short long list counts, not only a long one`() {
        // A category of 60 photos, 20 cells to a screen, scrolled down a little over a screen.
        assertTrue(BackToTop.shows(first = 21, visible = 20, total = 60))
    }

    @Test
    fun `at the top, or before anything is laid out, there is none`() {
        assertFalse(BackToTop.shows(first = 0, visible = 15, total = 900))
        assertFalse(BackToTop.shows(first = 0, visible = 0, total = 0))
        assertFalse(BackToTop.shows(first = 40, visible = 0, total = 900))
    }
}
