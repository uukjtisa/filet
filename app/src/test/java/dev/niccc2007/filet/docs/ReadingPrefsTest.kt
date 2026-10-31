package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading settings' arithmetic: the A- and A+ steps, the pinch's bounds, and what a stored
 * value that is not one of ours reads as. The storage is `Prefs`, which needs a device; what
 * it stores and reads back goes through these.
 */
class ReadingPrefsTest {

    @Test
    fun `the steps go up and down by ten between 80 and 160`() {
        assertEquals(110, Reading.larger(100))
        assertEquals(90, Reading.smaller(100))
        assertNull(Reading.larger(160))
        assertNull(Reading.smaller(80))
    }

    @Test
    fun `a size between steps steps to the next one either way`() {
        // Where a pinch left it: A+ and A- move to the steps either side, not by ten from there.
        assertEquals(140, Reading.larger(137))
        assertEquals(130, Reading.smaller(137))
        // Past the last step from a pinch, A- comes back into the steps and A+ has nowhere to go.
        assertEquals(160, Reading.smaller(240))
        assertNull(Reading.larger(240))
        assertEquals(80, Reading.larger(75))
    }

    @Test
    fun `the pinch and the stored size are the same number`() {
        assertEquals(137, Reading.sizeOf(1.37f))
        assertEquals(1.37f, ReadingSettings(size = 137).zoom, 0.0001f)
        // Kept to what the pinch can reach, whatever a hand-edited settings file says.
        assertEquals((DocZoom.MAX * 100).toInt(), Reading.clampSize(9000))
        assertEquals((DocZoom.MIN * 100).toInt(), Reading.clampSize(-5))
        assertEquals(Reading.clampSize(9000), Reading.sizeOf(40f))
    }

    @Test
    fun `a table mode that is not one of ours reads as fit`() {
        assertEquals(TableMode.FIT, TableMode.of(null))
        assertEquals(TableMode.FIT, TableMode.of("SIDEWAYS"))
        assertEquals(TableMode.COMPACT, TableMode.of("COMPACT"))
    }

    @Test
    fun `the defaults are the readable ones`() {
        val r = ReadingSettings()
        assertEquals(100, r.size)
        assertEquals(true, r.column)
        assertEquals(TableMode.FIT, r.tables)
        assertEquals(true, r.webPictures)
    }
}
