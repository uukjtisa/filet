package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Home categories card's column count, at the widths the card actually has.
 *
 * The card sits 10dp in from each edge with a 1dp border, and its tiles 10dp in from that, so a
 * 360dp phone leaves the grid 318dp.
 */
class CategoryGridTest {

    private val six = MediaCategory.entries.size

    private fun inner(screenDp: Float) = screenDp - 2 * 10f - 2 * 1f - 2 * CategoryGrid.EDGE_DP

    @Test
    fun `three across on a 360dp phone`() = assertEquals(3, CategoryGrid.columns(inner(360f), six))

    @Test
    fun `four across on a tablet`() = assertEquals(4, CategoryGrid.columns(inner(600f), six))

    @Test
    fun `never a lone tile on the last row`() {
        for (w in 200..1400 step 10) {
            val cols = CategoryGrid.columns(inner(w.toFloat()), six)
            val last = six % cols
            assertNotEquals("width $w -> $cols columns", 1, last)
        }
    }

    @Test
    fun `never fewer than two columns, even in a narrow split`() =
        assertEquals(2, CategoryGrid.columns(120f, six))

    @Test
    fun `everything on one row when there is room`() =
        assertEquals(6, CategoryGrid.columns(inner(1200f), six))

    // ── the two triangles ──

    private fun at(cols: Int) = CategoryGrid.layout(cols).map { row -> row.map { it.category to it.at } }

    @Test
    fun `every column count shows each category exactly once`() {
        for (cols in listOf(2, 3, 4, 6)) {
            val shown = CategoryGrid.layout(cols).flatten().map { it.category }
            assertEquals("$cols across", MediaCategory.entries.toSet(), shown.toSet())
            assertEquals("$cols across", six, shown.size)
        }
    }

    @Test
    fun `six across is one row, media then files`() = assertEquals(
        listOf(
            listOf(
                MediaCategory.IMAGES to 0f, MediaCategory.VIDEOS to 1f, MediaCategory.AUDIO to 2f,
                MediaCategory.DOCUMENTS to 3f, MediaCategory.ARCHIVES to 4f, MediaCategory.APPS to 5f,
            ),
        ),
        at(6),
    )

    @Test
    fun `four across puts the third of each half across its own pair`() {
        assertEquals(
            listOf(
                listOf(
                    MediaCategory.IMAGES to 0f, MediaCategory.VIDEOS to 1f,
                    MediaCategory.DOCUMENTS to 2f, MediaCategory.ARCHIVES to 3f,
                ),
                listOf(MediaCategory.AUDIO to 0f, MediaCategory.APPS to 2f),
            ),
            at(4),
        )
        assertEquals(listOf(2f, 2f), CategoryGrid.layout(4)[1].map { it.span })
    }

    @Test
    fun `two across gives the third of each half the whole row`() {
        val rows = CategoryGrid.layout(2)
        assertEquals(listOf(2, 1, 2, 1), rows.map { it.size })
        assertEquals(listOf(MediaCategory.AUDIO, MediaCategory.APPS), rows.filter { it.size == 1 }.map { it[0].category })
        assertEquals(true, rows.filter { it.size == 1 }.all { it[0].at == 0f && it[0].span == 2f })
    }

    @Test
    fun `every row fills its width, with no gap anywhere`() {
        for (cols in listOf(2, 3, 4, 6)) {
            for ((r, row) in CategoryGrid.layout(cols).withIndex()) {
                assertEquals("$cols across, row $r", cols.toFloat(), row.sumOf { it.span.toDouble() }.toFloat())
                // Edge to edge: each tile starts where the one before it ends.
                var next = 0f
                for (slot in row) {
                    assertEquals("$cols across, row $r, ${slot.category}", next, slot.at)
                    next = slot.at + slot.span
                }
            }
        }
    }

    @Test
    fun `three across is a media row over a files row`() = assertEquals(
        listOf(
            listOf(MediaCategory.IMAGES to 0f, MediaCategory.VIDEOS to 1f, MediaCategory.AUDIO to 2f),
            listOf(MediaCategory.DOCUMENTS to 0f, MediaCategory.ARCHIVES to 1f, MediaCategory.APPS to 2f),
        ),
        at(3),
    )

    @Test
    fun `a phone gets the three-row form and a tablet the triangles`() {
        assertEquals(2, CategoryGrid.layout(CategoryGrid.columns(inner(360f), six)).size)
        assertEquals(listOf(4, 2), CategoryGrid.layout(CategoryGrid.columns(inner(600f), six)).map { it.size })
    }

    @Test
    fun `no tile ever runs past the last column`() {
        for (cols in listOf(2, 3, 4, 6)) {
            for (slot in CategoryGrid.layout(cols).flatten()) {
                assertEquals("${slot.category} at $cols", true, slot.at >= 0f && slot.at + slot.span <= cols)
            }
        }
    }
}
