package dev.niccc2007.filet.icons

import androidx.compose.ui.graphics.vector.PathParser
import dev.niccc2007.filet.vfs.types.FileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The art tables are generated from the icon mock, and the code names things in them by string.
 * A name that is in one place and not the other draws nothing, or the wrong thing, without
 * failing anywhere else.
 */
class IconArtTest {

    @Test fun every_file_type_icon_has_a_family_drawn() {
        for (t in FileTypes.ALL) assertTrue("${t.ext} -> ${t.icon}", t.icon in TYPE_FAMILIES)
        assertTrue("other" in TYPE_FAMILIES)
    }

    @Test fun every_family_names_a_glyph_and_an_accent_that_exist() {
        for ((id, f) in TYPE_FAMILIES) {
            assertTrue("$id accent ${f.accent}", f.accent in ACCENTS)
            f.glyph?.let { assertTrue("$id glyph $it", it in TYPE_GLYPHS) }
        }
    }

    @Test fun every_standard_folder_wears_an_emblem_and_a_colour_that_exist() {
        val ids = EMBLEMS.map { it.id }.toSet()
        for (d in DEFAULT_FOLDER_ART) {
            assertTrue("${d.place} emblem ${d.emblem}", d.emblem in ids)
            assertTrue("${d.place} colour ${d.colour}", d.colour in ACCENTS)
        }
    }

    @Test fun every_drawn_emblem_is_an_emblem_and_emblem_ids_are_unique() {
        val ids = EMBLEMS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in RICH_EMBLEMS.keys) assertTrue(id, id in ids)
    }

    @Test fun the_gradients_the_drawing_asks_for_are_there() {
        for (g in listOf("gSlate", "gDeep", "gSteel", "gPaper", "gSubBack", "gSubFront")) {
            assertTrue(g, g in GRADIENTS)
        }
    }

    @Test fun the_slots_the_drawing_asks_for_are_there() {
        for (n in 1..3) for (lone in listOf(true, false)) {
            for (s in slotsFor(n, lone)) assertTrue("$n $lone $s", s in FOLDER_SLOTS)
        }
    }

    @Test fun one_thing_alone_peeks_and_a_cover_stands_up() {
        assertEquals(listOf('P'), slotsFor(1, lone = true))
        assertEquals(listOf('M'), slotsFor(1, lone = false))
        assertEquals(listOf('L', 'R'), slotsFor(2, lone = true))
        assertEquals(listOf('M', 'L', 'R'), slotsFor(3, lone = true))
    }

    @Test fun every_path_parses() {
        val all = buildList {
            addAll(listOf(FOLDER_BACK, FOLDER_FRONT, SUB_BACK, SUB_SHEET, SUB_FRONT, SUB_LIP, SUB_TAB_LIP))
            addAll(SUB_LINES)
            for (g in TYPE_GLYPHS.values) g.forEach { add(it.d) }
            for (e in EMBLEMS) e.parts.forEach { add(it.d) }
            for (r in RICH_EMBLEMS.values) r.forEach { add(it.d) }
        }
        for (d in all) {
            val nodes = runCatching { PathParser().parsePathString(d).toNodes() }.getOrNull()
            assertTrue("does not parse: $d", !nodes.isNullOrEmpty())
        }
    }
}
