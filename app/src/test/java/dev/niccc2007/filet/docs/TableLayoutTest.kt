package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Column widths by the browser's rule for a table with none: widest if it fits, narrowest and
 * scrolled if even that does not, and in between the spare room shared by how much each wanted.
 */
class TableLayoutTest {

    private fun near(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("column $i", expected[i], actual[i], 0.01f)
    }

    @Test
    fun `a table that fits at its widest gets its widest and does not stretch`() {
        val w = TableLayout.fit(listOf(30f, 40f), listOf(60f, 80f), 400f)
        near(listOf(60f, 80f), w.columns)
        assertFalse(w.scrolls)
        assertEquals(140f, w.total, 0f)
    }

    @Test
    fun `a table too wide even at its narrowest still fits, its words breaking`() {
        // Bug identified here: these widths were kept and the table scrolled, so Fit scrolled.
        // Floors of 40 each, 220 spare, shared by how far each narrowest stood above its floor.
        val w = TableLayout.fit(listOf(200f, 250f), listOf(400f, 500f), 300f)
        assertFalse(w.scrolls)
        assertEquals(300f, w.total, 0.01f)
        near(listOf(40f + 160f * 220f / 370f, 40f + 210f * 220f / 370f), w.columns)
    }

    @Test
    fun `a column narrower than the floor keeps its own width when the rest shrink`() {
        val w = TableLayout.fit(listOf(20f, 300f), listOf(20f, 600f), 200f)
        assertEquals(20f, w.columns[0], 0.01f)
        assertEquals(200f, w.total, 0.01f)
    }

    @Test
    fun `only a table too wide at the floor scrolls`() {
        val w = TableLayout.fit(List(10) { 100f }, List(10) { 200f }, 300f)
        near(List(10) { TableLayout.FLOOR }, w.columns)
        assertTrue(w.scrolls)
    }

    @Test
    fun `exactly the narrowest fits without scrolling`() {
        val w = TableLayout.fit(listOf(100f, 200f), listOf(400f, 500f), 300f)
        near(listOf(100f, 200f), w.columns)
        assertFalse(w.scrolls)
    }

    @Test
    fun `the spare room goes by how much wider each column wanted`() {
        // Wants 50 and 150 more; 100 spare, so a quarter and three quarters of it.
        val w = TableLayout.fit(listOf(50f, 50f), listOf(100f, 200f), 200f)
        near(listOf(75f, 125f), w.columns)
        assertEquals(200f, w.total, 0.01f)
        assertFalse(w.scrolls)
    }

    @Test
    fun `a column of short numbers stays narrow beside a column of sentences`() {
        val w = TableLayout.fit(listOf(20f, 60f), listOf(20f, 900f), 300f)
        near(listOf(20f, 280f), w.columns)
    }

    @Test
    fun `comfortable is every column at its widest, scrolled when that is wider than the room`() {
        val w = TableLayout.comfortable(listOf(50f, 50f), listOf(100f, 200f), 200f)
        near(listOf(100f, 200f), w.columns)
        assertTrue(w.scrolls)
        assertFalse(TableLayout.comfortable(listOf(10f), listOf(20f), 200f).scrolls)
    }

    @Test
    fun `a merged cell widens only the columns it covers, and only by what they lack`() {
        val cells = listOf(
            listOf(10f to 40f, 10f to 40f, 10f to 40f),
            listOf(30f to 200f, 10f to 40f),
        )
        val spans = listOf(listOf(1, 1, 1), listOf(2, 1))
        val (lo, hi) = TableLayout.bounds(cells, spans, 3)
        // 30 narrowest over two columns of 10 each: 5 more each.
        near(listOf(15f, 15f, 10f), lo)
        // 200 widest over two columns of 40 each: 60 more each.
        near(listOf(100f, 100f, 40f), hi)
    }

    @Test
    fun `a ragged table is as wide as its widest row and a short row draws`() {
        assertEquals(3, TableLayout.columnsOf(listOf(listOf("a", "b", "c"), listOf("d")), emptyList()))
        assertEquals(4, TableLayout.columnsOf(listOf(listOf("a", "b")), listOf(listOf(3, 1))))
        val (lo, hi) = TableLayout.bounds(listOf(listOf(10f to 20f), listOf(10f to 20f, 5f to 9f, 1f to 2f)), emptyList(), 3)
        assertEquals(3, lo.size)
        assertTrue(hi.zip(lo).all { (h, l) -> h >= l })
    }

    @Test
    fun `the words worth measuring are the longest ones`() {
        assertEquals(listOf("internationalisation", "longer"), TableLayout.longestWords("a longer internationalisation of it"))
        assertEquals(emptyList<String>(), TableLayout.longestWords("   "))
    }
}
