package dev.niccc2007.filet.handlers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The parts of drawing an SVG in the picture viewer that are decisions rather than drawing. */
class SvgPictureTest {

    @Test fun only_a_vector_format_is_drawn_rather_than_decoded() {
        assertTrue(SvgPicture.handles("svg"))
        assertTrue(SvgPicture.handles("SVG"))
        for (e in listOf("png", "jpg", "gif", "webp", "heic", "xml")) assertFalse(e, SvgPicture.handles(e))
    }

    @Test fun a_small_icon_is_drawn_large_so_zooming_stays_sharp() {
        assertEquals(2400 to 2400, SvgPicture.rasterSize(24f to 24f, 2400))
        assertEquals(2400 to 1200, SvgPicture.rasterSize(48f to 24f, 2400))
    }

    @Test fun a_huge_drawing_is_brought_down_to_the_ceiling_keeping_its_shape() {
        assertEquals(1200 to 2400, SvgPicture.rasterSize(5000f to 10000f, 2400))
    }

    @Test fun a_drawing_that_declares_no_usable_size_is_drawn_square() {
        assertEquals(2400 to 2400, SvgPicture.rasterSize(null, 2400))
        assertEquals(2400 to 2400, SvgPicture.rasterSize(0f to 10f, 2400))
    }

    @Test fun a_very_thin_drawing_still_gets_a_pixel_on_its_short_side() {
        val (w, h) = SvgPicture.rasterSize(10000f to 1f, 2400)
        assertEquals(2400, w)
        assertEquals(1, h)
    }

    @Test fun the_whole_file_is_read_up_to_the_cap_and_refused_past_it() {
        val small = ByteArray(100_000) { (it % 251).toByte() }
        assertArrayEquals(small, SvgPicture.readCapped(ByteArrayInputStream(small)))
        val big = object : java.io.InputStream() {
            var left = SvgPicture.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        assertNull(SvgPicture.readCapped(big))
    }
}
