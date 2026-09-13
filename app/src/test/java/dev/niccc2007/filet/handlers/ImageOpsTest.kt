package dev.niccc2007.filet.handlers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image editor's arithmetic, checked against actual pixels.
 *
 * A rotation with a transposed index and a crop that is one pixel out both look plausible in
 * a screenshot. The buffers here are deliberately not square and not symmetric, because those
 * are the two shapes that hide an axis swap.
 */
class ImageOpsTest {

    /**
     * ```
     * a b
     * c d
     * e f
     * ```
     * Two wide, three tall, every value distinct.
     */
    private val tall = Pixels(2, 3, intArrayOf(A, B, C, D, E, F))

    // ── rotation ──

    @Test fun a_right_turn_moves_the_bottom_left_corner_to_the_top_left() {
        // e c a
        // f d b
        val out = tall.rotated(Turn.RIGHT)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(intArrayOf(E, C, A, F, D, B), out.argb)
    }

    @Test fun a_left_turn_moves_the_top_right_corner_to_the_top_left() {
        // b d f
        // a c e
        val out = tall.rotated(Turn.LEFT)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(intArrayOf(B, D, F, A, C, E), out.argb)
    }

    @Test fun a_half_turn_keeps_the_shape_and_reverses_the_buffer() {
        val out = tall.rotated(Turn.HALF)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        assertArrayEquals(intArrayOf(F, E, D, C, B, A), out.argb)
    }

    @Test fun four_right_turns_is_where_you_started() {
        var out = tall
        repeat(4) { out = out.rotated(Turn.RIGHT) }
        assertEquals(tall.width, out.width)
        assertEquals(tall.height, out.height)
        assertArrayEquals(tall.argb, out.argb)
    }

    @Test fun a_left_turn_undoes_a_right_turn() {
        val out = tall.rotated(Turn.RIGHT).rotated(Turn.LEFT)
        assertArrayEquals(tall.argb, out.argb)
    }

    // ── flips ──

    @Test fun a_horizontal_flip_swaps_columns_not_rows() {
        assertArrayEquals(intArrayOf(B, A, D, C, F, E), tall.flipped(horizontal = true).argb)
    }

    @Test fun a_vertical_flip_swaps_rows_not_columns() {
        assertArrayEquals(intArrayOf(E, F, C, D, A, B), tall.flipped(horizontal = false).argb)
    }

    @Test fun a_flip_is_its_own_undo() {
        assertArrayEquals(tall.argb, tall.flipped(true).flipped(true).argb)
        assertArrayEquals(tall.argb, tall.flipped(false).flipped(false).argb)
    }

    @Test fun a_flip_both_ways_is_a_half_turn() {
        assertArrayEquals(tall.rotated(Turn.HALF).argb, tall.flipped(true).flipped(false).argb)
    }

    // ── invert ──

    @Test fun invert_complements_the_colour_channels() {
        val out = Pixels(1, 1, intArrayOf(0xFF112233.toInt())).inverted()
        assertEquals(0xFFEEDDCC.toInt(), out.argb[0])
    }

    @Test fun invert_leaves_alpha_alone() {
        // The bug this guards: complementing all 32 bits turns a transparent PNG background
        // opaque and the subject invisible, which reads as a broken decode rather than a filter.
        val out = Pixels(1, 1, intArrayOf(0x00000000)).inverted()
        assertEquals(0x00, (out.argb[0] ushr 24) and 0xFF)
        assertEquals(0xFFFFFF, out.argb[0] and 0xFFFFFF)
    }

    @Test fun invert_twice_is_the_original() {
        assertArrayEquals(tall.argb, tall.inverted().inverted().argb)
    }

    @Test fun greyscale_weighs_green_over_blue() {
        val green = Pixels(1, 1, intArrayOf(0xFF00FF00.toInt())).greyscale().argb[0] and 0xFF
        val blue = Pixels(1, 1, intArrayOf(0xFF0000FF.toInt())).greyscale().argb[0] and 0xFF
        assertTrue("green $green should read far brighter than blue $blue", green > blue * 4)
    }

    // ── crop ──

    /** A 4x4 where every pixel encodes its own coordinates, so a shifted crop is obvious. */
    private val grid = Pixels.of(4, 4) { x, y -> 0xFF000000.toInt() or (x shl 8) or y }

    @Test fun a_crop_takes_exactly_the_rectangle_asked_for() {
        val out = grid.cropped(CropRect(left = 1, top = 2, width = 2, height = 2))
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(
            intArrayOf(px(1, 2), px(2, 2), px(1, 3), px(2, 3)),
            out.argb,
        )
    }

    @Test fun a_crop_of_the_whole_image_changes_nothing() {
        val out = grid.cropped(CropRect(0, 0, 4, 4))
        assertArrayEquals(grid.argb, out.argb)
    }

    @Test fun dragged_corners_in_any_order_give_the_same_rectangle() {
        val forwards = cropRect(1f, 2f, 3f, 4f, 4, 4)
        val backwards = cropRect(3f, 4f, 1f, 2f, 4, 4)
        assertEquals(forwards, backwards)
        assertEquals(CropRect(1, 2, 2, 2), forwards)
    }

    @Test fun a_drag_off_the_edge_is_pulled_back_inside() {
        val r = cropRect(-40f, -12f, 400f, 900f, 4, 4)
        assertEquals(CropRect(0, 0, 4, 4), r)
        grid.cropped(r)   // must not throw
    }

    @Test fun a_tap_rather_than_a_drag_still_gives_a_croppable_rectangle() {
        // Zero area would pass every bounds check and then crash the allocation.
        val r = cropRect(2f, 2f, 2f, 2f, 4, 4)
        assertEquals(1, r.width)
        assertEquals(1, r.height)
        assertArrayEquals(intArrayOf(px(2, 2)), grid.cropped(r).argb)
    }

    @Test fun a_rectangle_outside_the_image_is_refused_rather_than_silently_shifted() {
        val thrown = runCatching { grid.cropped(CropRect(3, 3, 4, 4)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
    }

    // ── stretch ──

    @Test fun stretching_keeps_the_corner_pixels_exactly() {
        val out = grid.stretched(9, 9)
        assertEquals(9, out.width)
        assertEquals(px(0, 0), out[0, 0])
        assertEquals(px(3, 0), out[8, 0])
        assertEquals(px(0, 3), out[0, 8])
        assertEquals(px(3, 3), out[8, 8])
    }

    @Test fun stretching_to_the_same_size_is_a_no_op() {
        assertArrayEquals(grid.argb, grid.stretched(4, 4).argb)
    }

    @Test fun stretching_down_to_one_pixel_does_not_divide_by_zero() {
        val out = grid.stretched(1, 1)
        assertEquals(1, out.argb.size)
    }

    @Test fun a_squash_on_one_axis_leaves_the_other_alone() {
        val out = grid.stretched(4, 2)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        assertEquals(px(0, 0), out[0, 0])
        assertEquals(px(3, 3), out[3, 1])
    }

    // ── naming ──

    @Test fun an_edit_never_takes_the_name_of_the_original() {
        assertNotEquals("photo.png", editedName("photo.png") { false })
        assertEquals("photo-edit.png", editedName("photo.png") { false })
    }

    @Test fun an_edit_steps_past_a_name_that_is_already_there() {
        val there = setOf("photo-edit.png", "photo-edit-2.png")
        assertEquals("photo-edit-3.png", editedName("photo.png") { it in there })
    }

    @Test fun editing_an_edit_does_not_grow_the_suffix() {
        assertEquals("photo-edit-2.png", editedName("photo-edit.png") { false })
        assertEquals("photo-edit-3.png", editedName("photo-edit-2.png") { it == "photo-edit.png" })
    }

    @Test fun a_re_encoded_edit_is_named_for_what_it_actually_is() {
        // A .heic cannot be written back as HEIC here, so it comes out as JPEG - and a JPEG
        // called photo-edit.heic is a file nothing on the phone will open.
        assertEquals("photo-edit.jpg", editedName("photo.heic", extension = "jpg") { false })
        assertEquals("shot-edit.png", editedName("shot.webp", extension = ".png") { false })
    }

    @Test fun the_extension_survives_and_a_file_without_one_still_gets_a_name() {
        assertEquals("scan-edit.jpeg", editedName("scan.jpeg") { false })
        assertEquals("Makefile-edit", editedName("Makefile") { false })
        // A leading dot is the whole name, not an extension: ".gitignore" has no stem to split.
        assertEquals(".gitignore-edit", editedName(".gitignore") { false })
    }

    @Test fun a_folder_full_of_edits_still_resolves() {
        val there = (1..300).map { if (it == 1) "p-edit.png" else "p-edit-$it.png" }.toSet()
        val name = editedName("p.png") { it in there }
        assertEquals("p-edit-301.png", name)
    }

    // ── pan clamping, shared with the viewer ──

    @Test fun panning_is_dead_until_you_are_zoomed_in() {
        assertEquals(0f, clampPan(900f, scale = 1f, viewportSize = 1000f), 0f)
    }

    @Test fun panning_stops_at_the_edge_of_what_is_off_screen() {
        // At 2x on a 1000px viewport, 1000px of image is off screen, 500 on each side.
        assertEquals(500f, clampPan(5000f, scale = 2f, viewportSize = 1000f), 0.01f)
        assertEquals(-500f, clampPan(-5000f, scale = 2f, viewportSize = 1000f), 0.01f)
        assertEquals(120f, clampPan(120f, scale = 2f, viewportSize = 1000f), 0.01f)
    }

    // -- fitting the picture into the view --

    @Test fun a_wide_image_in_a_square_box_gets_bars_top_and_bottom() {
        val fit = fitInside(boxWidth = 400f, boxHeight = 400f, imageWidth = 200, imageHeight = 100)
        assertEquals(2f, fit.scale, 0f)
        assertEquals(0f, fit.left, 0f)
        assertEquals(100f, fit.top, 0f)
        assertEquals(400f, fit.width, 0f)
        assertEquals(200f, fit.height, 0f)
    }

    @Test fun a_tall_image_in_a_square_box_gets_bars_left_and_right() {
        val fit = fitInside(400f, 400f, 100, 200)
        assertEquals(2f, fit.scale, 0f)
        assertEquals(100f, fit.left, 0f)
        assertEquals(0f, fit.top, 0f)
    }

    @Test fun a_touch_in_the_letterbox_pins_to_the_edge_instead_of_going_negative() {
        // The finger leaves the picture while dragging a crop corner. Pinning keeps the drag
        // alive; a negative image coordinate would crop from outside the buffer.
        val fit = fitInside(400f, 400f, 200, 100)
        assertEquals(0f, fit.toImageY(0f, 100), 0f)
        assertEquals(100f, fit.toImageY(399f, 100), 0f)
        assertEquals(0f, fit.toImageX(-50f, 200), 0f)
        assertEquals(200f, fit.toImageX(900f, 200), 0f)
    }

    @Test fun view_and_image_coordinates_are_inverses_of_each_other() {
        val fit = fitInside(400f, 400f, 200, 100)
        for (ix in intArrayOf(0, 37, 199)) {
            assertEquals(ix.toFloat(), fit.toImageX(fit.toViewX(ix.toFloat()), 200), 0.001f)
        }
        for (iy in intArrayOf(0, 12, 99)) {
            assertEquals(iy.toFloat(), fit.toImageY(fit.toViewY(iy.toFloat()), 100), 0.001f)
        }
    }

    @Test fun a_box_with_no_size_yet_does_not_produce_infinities() {
        // Compose reports 0x0 for one frame before layout lands, and every gesture handler
        // runs against whatever that frame left behind.
        val fit = fitInside(0f, 0f, 200, 100)
        assertFalse("an unmeasured box must not claim to be ready", fit.ready)
        assertEquals(0f, fit.scale, 0f)
        assertEquals(0f, fit.toImageX(120f, 200), 0f)
        assertEquals(0f, fit.toImageY(120f, 100), 0f)
    }

    @Test fun a_crop_drawn_on_screen_lands_on_the_pixels_under_it() {
        // The whole point of the conversion: the middle quarter of a displayed 4x4 image.
        val fit = fitInside(800f, 800f, 4, 4)
        val r = cropRect(
            fit.toImageX(fit.toViewX(1f), 4), fit.toImageY(fit.toViewY(1f), 4),
            fit.toImageX(fit.toViewX(3f), 4), fit.toImageY(fit.toViewY(3f), 4),
            4, 4,
        )
        assertEquals(CropRect(1, 1, 2, 2), r)
        assertArrayEquals(intArrayOf(px(1, 1), px(2, 1), px(1, 2), px(2, 2)), grid.cropped(r).argb)
    }

    private fun px(x: Int, y: Int): Int = 0xFF000000.toInt() or (x shl 8) or y

    private companion object {
        const val A = 0xFF0A0A0A.toInt()
        const val B = 0xFF0B0B0B.toInt()
        const val C = 0xFF0C0C0C.toInt()
        const val D = 0xFF0D0D0D.toInt()
        const val E = 0xFF0E0E0E.toInt()
        const val F = 0xFF0F0F0F.toInt()
    }
}
