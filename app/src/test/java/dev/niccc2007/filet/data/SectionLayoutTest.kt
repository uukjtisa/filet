package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionLayoutTest {

    private val defaults = listOf("storage", "arrived", "categories", "opened", "cache")

    @Test
    fun `nothing stored is the default order with nothing hidden`() {
        val l = SectionLayout.of(defaults, emptyList(), emptySet())
        assertEquals(defaults, l.visible)
        assertTrue(l.hiddenOnes.isEmpty())
    }

    @Test
    fun `a stored order is kept`() = assertEquals(
        listOf("opened", "storage", "arrived", "categories", "cache"),
        SectionLayout.of(defaults, listOf("opened", "storage", "arrived", "categories", "cache"), emptySet()).order,
    )

    @Test
    fun `a card the stored order never heard of goes to its default place`() {
        // Stored by a build without "categories": it lands after "arrived", where it belongs.
        val l = SectionLayout.of(defaults, listOf("opened", "storage", "arrived", "cache"), emptySet())
        assertEquals(listOf("opened", "storage", "arrived", "categories", "cache"), l.order)
        // And a new first card is first.
        assertEquals("storage", SectionLayout.of(defaults, listOf("arrived", "categories", "opened", "cache"), emptySet()).order[0])
    }

    @Test
    fun `ids this build does not draw are dropped, from the order and from hidden`() {
        val l = SectionLayout.of(defaults, listOf("gone", "storage", "storage"), setOf("gone", "cache"))
        assertEquals(defaults, l.order)
        assertEquals(setOf("cache"), l.hidden)
    }

    @Test
    fun `a locked card is never hidden, even if stored hidden`() {
        val bar = listOf("quick", "recent", "drives", "collections", "tools", "filet")
        val l = SectionLayout.of(bar, emptyList(), setOf("filet", "tools"), locked = setOf("filet"))
        assertEquals(setOf("tools"), l.hidden)
        assertEquals(l, l.hide("filet", locked = setOf("filet")))
    }

    @Test
    fun `moving a card reorders the visible ones and keeps hidden after them`() {
        val l = SectionLayout.of(defaults, emptyList(), setOf("categories"))
        val moved = l.move(from = 3, to = 0)
        assertEquals(listOf("cache", "storage", "arrived", "opened"), moved.visible)
        assertEquals(listOf("cache", "storage", "arrived", "opened", "categories"), moved.order)
    }

    @Test
    fun `hiding and showing puts a card back at the end of what is showing`() {
        val l = SectionLayout.of(defaults, emptyList(), emptySet()).hide("arrived", emptySet())
        assertEquals(listOf("storage", "categories", "opened", "cache"), l.visible)
        assertEquals(listOf("arrived"), l.hiddenOnes)
        val back = l.show("arrived")
        assertEquals(listOf("storage", "categories", "opened", "cache", "arrived"), back.visible)
        assertTrue(back.hidden.isEmpty())
    }

    @Test
    fun `the stored form survives the trip`() {
        assertEquals(defaults, SectionLayout.decode(SectionLayout.encode(defaults)))
        assertTrue(SectionLayout.decode(null).isEmpty())
        assertTrue(SectionLayout.decode(" , ").isEmpty())
    }
}
