package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retrieval for a mistyped word. See [TypoRetrieval]. */
class TypoRetrievalTest {

    /** Whether some piece survives as a contiguous run of [target], i.e. LIKE would retrieve it. */
    private fun retrieves(query: String, target: String) =
        TypoRetrieval.pieces(query).any { target.contains(it) }

    @Test
    fun `a transposed pair still retrieves the word`() {
        assertTrue(retrieves("minecarft", "minecraft_world.zip"))
    }

    @Test
    fun `a swap on a piece boundary is caught by the shifted split`() {
        // Six letters, one edit allowed, split "fod|ler": the swapped d and l straddle the cut and
        // break both halves. The shifted split, "fodl|er", keeps a piece whole.
        assertFalse("folder".contains("fod") || "folder".contains("ler"))
        assertTrue(retrieves("fodler", "folder_backup"))
    }

    @Test
    fun `a dropped and a doubled letter retrieve`() {
        assertTrue(retrieves("screnshot", "screenshot_2026.png"))
        assertTrue(retrieves("receeipt", "receipt.pdf"))
    }

    @Test
    fun `retrieval narrows, it does not admit`() {
        // Scattered letters may well share a piece with something. The gate is what refuses it;
        // the test that the junk row from the measured defect never shows is RelevanceTest's.
        assertNull(Relevance.judge(listOf("minecraft"), "7_6M_views_51K_reactions_EPIC_spinning_backfire.jpg"))
    }

    @Test
    fun `short words get no typo retrieval`() {
        assertTrue(TypoRetrieval.pieces("cat").isEmpty())
        assertNull(TypoRetrieval.ftsExpression(listOf("cat", "pdf")))
        assertFalse(TypoRetrieval.wanted(listOf("cat"), found = 0))
    }

    @Test
    fun `it only runs when the strict answer is thin`() {
        assertTrue(TypoRetrieval.wanted(listOf("minecarft"), found = 0))
        assertFalse(TypoRetrieval.wanted(listOf("minecraft"), found = TypoRetrieval.FEW))
    }

    @Test
    fun `the trigram expression quotes every gram once`() {
        assertEquals("\"mai\" OR \"ain\"", TypoRetrieval.ftsExpression(listOf("main")))
        assertEquals(listOf("aaa"), TypoRetrieval.trigrams("aaaaa"))
    }

    @Test
    fun `a one letter slip leaves most trigrams shared`() {
        // The property bm25 ranking relies on.
        val q = TypoRetrieval.trigrams("minecarft").toSet()
        val t = TypoRetrieval.trigrams("minecraft").toSet()
        assertTrue((q intersect t).size >= 3)
    }
}
