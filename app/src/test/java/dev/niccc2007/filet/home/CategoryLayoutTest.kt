package dev.niccc2007.filet.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryLayoutTest {

    @Test
    fun `only the categories with pictures offer a gallery`() {
        val offered = MediaCategory.entries.filter(CategoryLayout::offeredFor).toSet()
        assertEquals(setOf(MediaCategory.IMAGES, MediaCategory.VIDEOS, MediaCategory.AUDIO), offered)
    }

    @Test
    fun `nothing stored is the list everywhere`() {
        for (c in MediaCategory.entries) assertEquals(CategoryLayout.LIST, CategoryLayout.of(emptyMap(), c))
    }

    @Test
    fun `each category keeps its own choice`() {
        val stored = CategoryLayout.parse("images=gallery,audio=list")
        assertEquals(CategoryLayout.GALLERY, CategoryLayout.of(stored, MediaCategory.IMAGES))
        assertEquals(CategoryLayout.LIST, CategoryLayout.of(stored, MediaCategory.AUDIO))
        assertEquals(CategoryLayout.LIST, CategoryLayout.of(stored, MediaCategory.VIDEOS))
    }

    @Test
    fun `a category that offers no gallery is never drawn as one, whatever is stored`() {
        val stored = mapOf(MediaCategory.DOCUMENTS.id to CategoryLayout.GALLERY)
        assertEquals(CategoryLayout.LIST, CategoryLayout.of(stored, MediaCategory.DOCUMENTS))
    }

    @Test
    fun `it round trips`() {
        val map = mapOf("videos" to CategoryLayout.GALLERY, "images" to CategoryLayout.LIST)
        assertEquals(map, CategoryLayout.parse(CategoryLayout.encode(map)))
    }

    @Test
    fun `values from a newer Filet read as defaults rather than failing`() {
        val stored = CategoryLayout.parse("images=mosaic,documents=gallery,nonsense,=,videos=gallery")
        assertEquals(mapOf("videos" to CategoryLayout.GALLERY), stored)
    }

    // ── the gallery's arithmetic ──

    @Test
    fun `a phone held upright gets three pictures across and two covers`() {
        val pictures = GalleryCells.grid(360.dp, captioned = false)
        assertEquals(3, pictures.columns)
        val covers = GalleryCells.grid(360.dp, captioned = true)
        assertEquals(2, covers.columns)
    }

    @Test
    fun `the tiles and gaps fill the row exactly`() {
        for (w in listOf(320, 360, 411, 600, 900)) {
            for (cap in listOf(true, false)) {
                val g = GalleryCells.grid(w.dp, cap)
                val used = g.tile * g.columns + g.gap * (g.columns - 1) + GalleryCells.EDGE * 2
                assertEquals("width $w captioned $cap", w.toFloat(), used.value, 0.5f)
            }
        }
    }

    @Test
    fun `never fewer than two columns, even in a narrow split`() {
        assertEquals(2, GalleryCells.grid(120.dp, captioned = true).columns)
    }

    @Test
    fun `the warm-up asks for the same pixels the tile draws`() {
        // The FileThumb sum: a mismatch here is a decode per tile that nobody uses.
        assertEquals(250, GalleryCells.decodePx(100.dp))
        assertEquals(320, GalleryCells.decodePx(400.dp))
        assertEquals(48, GalleryCells.decodePx(10.dp))
    }

    private fun entry(path: String) = HistoryEntry(path = path, name = path, bytes = 1, added = 0, modified = 0)

    @Test
    fun `a folded day keeps its header and drops its tiles`() {
        val groups = listOf(
            DayGroup("2026-10-26", "Today", listOf(entry("/a.jpg"), entry("/b.jpg"))),
            DayGroup("2026-10-25", "Yesterday", listOf(entry("/c.jpg"))),
        )
        val open = GalleryCells.of(groups, emptySet())
        assertEquals(5, open.size)
        val folded = GalleryCells.of(groups, setOf("2026-10-26"))
        assertEquals(listOf("h:2026-10-26", "h:2026-10-25", "e:2026-10-25:/c.jpg"), folded.map { it.key })
        assertTrue(folded[0] is GalleryCell.Day)
        assertFalse(folded[2] is GalleryCell.Day)
    }
}
