package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub's heading anchors, which a README's own links were written against - so the rule is
 * copied oddities and all, and these pin the oddities.
 */
class DocOutlineTest {

    @Test
    fun `a heading is lower-cased and its spaces become hyphens`() {
        assertEquals("getting-started", DocOutline.slug("Getting Started"))
    }

    @Test
    fun `punctuation is dropped but hyphens and underscores stay`() {
        assertEquals("whats-new-in-v20", DocOutline.slug("What's new in v2.0?"))
        assertEquals("snake_case-and-kebab-case", DocOutline.slug("snake_case and kebab-case"))
    }

    @Test
    fun `nothing is collapsed, which is GitHub's rule`() {
        // `&` drops out and leaves both its spaces behind.
        assertEquals("setup--use", DocOutline.slug("Setup & Use"))
        assertEquals("-lead", DocOutline.slug(" Lead"))
    }

    @Test
    fun `letters outside ASCII are kept and emoji are not`() {
        assertEquals("café-ñandú", DocOutline.slug("Café Ñandú"))
        assertEquals("-rocket", DocOutline.slug("🚀 Rocket"))
    }

    @Test
    fun `repeats are numbered from one, the way GitHub numbers them`() {
        val s = DocOutline.Slugger()
        assertEquals(listOf("faq", "faq-1", "faq-2"), listOf(s.next("FAQ"), s.next("FAQ"), s.next("faq")))
        // A heading that is itself "faq-1" after one has been handed out takes the next free one.
        val t = DocOutline.Slugger()
        assertEquals(listOf("a", "a-1", "a-1-1"), listOf(t.next("a"), t.next("a"), t.next("a-1")))
    }

    @Test
    fun `a fragment is found as written, lower-cased, without the user-content prefix, or decoded`() {
        val anchors = mapOf("install" to 3, "café" to 7)
        assertEquals(3, DocOutline.find(anchors, "#install"))
        assertEquals(3, DocOutline.find(anchors, "#Install"))
        assertEquals(3, DocOutline.find(anchors, "#user-content-install"))
        assertEquals(7, DocOutline.find(anchors, "#caf%C3%A9"))
        assertNull(DocOutline.find(anchors, "#nowhere"))
        assertNull(DocOutline.find(anchors, "#"))
    }
}
