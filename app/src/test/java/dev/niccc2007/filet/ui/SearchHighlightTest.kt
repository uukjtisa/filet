package dev.niccc2007.filet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import dev.niccc2007.filet.ui.theme.PaletteSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A search match must be visible, and still readable, on every palette in both themes. */
class SearchHighlightTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private val sides = PaletteSpec.entries.flatMap { listOf("${it.name} light" to it.light, "${it.name} dark" to it.dark) }

    @Test fun the_wash_stands_out_from_the_ground_it_sits_on() {
        for ((name, side) in sides) {
            for ((ground, colour) in listOf("surface" to side.scheme.surface, "background" to side.scheme.background, "selected row" to side.extras.sel)) {
                val wash = matchWash(side.extras.accent).compositeOver(colour)
                val c = contrast(wash, colour)
                assertTrue("$name on $ground: the wash is only $c:1 from the ground", c >= 1.25f)
            }
        }
    }

    @Test fun the_matched_letters_stay_readable_on_the_wash() {
        // 4.5:1 is WCAG AA for ordinary text, which a file name is.
        for ((name, side) in sides) {
            val wash = matchWash(side.extras.accent).compositeOver(side.scheme.surface)
            for ((what, ink) in listOf("text" to side.scheme.onSurface, "secondary text" to side.extras.fg2)) {
                val c = contrast(ink, wash)
                val need = if (what == "text") 4.5f else 3f
                assertTrue("$name: $what on the wash is $c:1", c >= need)
            }
        }
    }

    @Test fun a_match_is_marked_by_its_ground_and_keeps_the_rows_own_letter_colour() {
        val ink = PaletteSpec.INK.light.extras.accent
        val s = highlighted("Holiday photos", "photo", ink)
        assertEquals(1, s.spanStyles.size)
        val span = s.spanStyles[0]
        assertEquals(8, span.start)
        assertEquals(13, span.end)
        assertEquals(matchWash(ink), span.item.background)
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals(Color.Unspecified, span.item.color)
    }

    @Test fun nothing_is_marked_when_nothing_matches() {
        assertTrue(highlighted("Holiday photos", "xyz", Color.Red).spanStyles.isEmpty())
        assertNull(highlighted("Holiday photos", "", Color.Red).spanStyles.firstOrNull())
    }
}
