package dev.niccc2007.filet.index

import dev.niccc2007.filet.index.Relevance.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which names answer a query. SEARCH.md §14.4.
 *
 * The fixtures at the top are real: names that were shown as answers and should not have been.
 */
class RelevanceTest {

    private fun kindOf(query: String, name: String): Kind? =
        Relevance.judge(Relevance.wordsOf(query), name)?.hits?.single()?.kind

    private fun admits(query: String, name: String) = Relevance.judge(Relevance.wordsOf(query), name) != null

    // ── real junk, kept out ──

    @Test
    fun `scattered letters never admit a name`() {
        // The first hit for "minecraft" on the phone this was measured on. Every letter is there,
        // in order, spread across nine words.
        assertNull(kindOf("minecraft", "7_6M_views_51K_reactions_EPIC_spinning_backfire.jpg"))
    }

    @Test
    fun `a long name is not close to everything`() {
        // Typo distance is measured against a word, never the whole name.
        assertTrue(!admits("minecraft", "Microsoft Edge.lnk"))
        assertTrue(!admits("minecraft", "Screenshot_20261027_mining_crafts_tutorial.png"))
    }

    @Test
    fun `a short word gets no typo budget`() {
        assertTrue(!admits("cat", "car.jpg"))
        assertTrue(!admits("cat", "bat.png"))
    }

    // ── real matches, let in ──

    @Test
    fun `a windows package folder that really contains the word is a match`() {
        // Microsoft results for "minecraft" on a C drive are usually this: a real match inside a
        // misleading name. The highlight is what shows it.
        val v = Relevance.judge(Relevance.wordsOf("minecraft"), "Microsoft.MinecraftUWP_8wekyb3d8bbwe")
        assertNotNull(v)
        assertEquals(Kind.WORD_START, v!!.hits.single().kind)
        assertEquals(listOf(10..18), v.spans)
    }

    @Test
    fun `contiguous pieces are ranked by where they sit`() {
        assertEquals(Kind.EXACT, kindOf("minecraft", "Minecraft"))
        assertEquals(Kind.EXACT, kindOf("notes", "notes.txt"))
        assertEquals(Kind.PREFIX, kindOf("mine", "Minecraft_world.zip"))
        assertEquals(Kind.WORD_START, kindOf("craft", "Mine_craft.zip"))
        assertEquals(Kind.WORD_START, kindOf("craft", "MineCraft.zip"))
        assertEquals(Kind.CONTAINS, kindOf("craft", "Minecraft.zip"))
    }

    @Test
    fun `an acronym is the first letters of consecutive words`() {
        assertEquals(Kind.ACRONYM, kindOf("mcw", "MineCraft World.zip"))
        assertEquals(Kind.ACRONYM, kindOf("qr", "quarterly-report.pdf"))
        // Gaps between the words are not allowed - that is scattered letters again, a level up.
        assertNull(kindOf("mc", "My dog and the cat.jpg"))
    }

    @Test
    fun `one slip in a word still finds it`() {
        assertEquals(Kind.TYPO, kindOf("minecarft", "Minecraft.zip"))
        assertEquals(Kind.TYPO, kindOf("mincraft", "Minecraft_world.zip"))
        // A slip in a word typed before it was finished, where the name never split the two.
        assertEquals(Kind.TYPO, kindOf("minecarf", "minecraftworld.zip"))
    }

    @Test
    fun `every word of the query has to match`() {
        assertTrue(admits("quarterly recon", "quarterly-reconciliation-2026.xlsx"))
        assertTrue(!admits("quarterly invoice", "quarterly-reconciliation-2026.xlsx"))
    }

    @Test
    fun `the better match outweighs the worse`() {
        val exact = Relevance.judge(Relevance.wordsOf("report"), "report.pdf")!!.weight
        val prefix = Relevance.judge(Relevance.wordsOf("report"), "report-final.pdf")!!.weight
        val inside = Relevance.judge(Relevance.wordsOf("report"), "annualreport.pdf")!!.weight
        val slip = Relevance.judge(Relevance.wordsOf("reprot"), "report.pdf")!!.weight
        assertTrue(exact > prefix && prefix > inside && inside > slip)
    }

    @Test
    fun `accents fold away`() {
        assertEquals(Kind.EXACT, kindOf("resume", "Résumé.pdf"))
    }

    @Test
    fun `an empty query admits everything, for the field-only searches`() {
        // `type:image` with no words is a filter, and the gate must not be what empties it.
        assertNotNull(Relevance.judge(emptyList(), "anything.jpg"))
    }

    // ── words ──

    @Test
    fun `a name splits where a reader would say a new word starts`() {
        fun words(n: String) = Relevance.partsOf(n).map { n.substring(it.first, it.last + 1) }
        assertEquals(listOf("mine", "Craft"), words("mineCraft"))
        assertEquals(listOf("XML", "File", "2026"), words("XMLFile2026"))
        assertEquals(listOf("IMG", "20261027", "095237"), words("IMG_20261027_095237"))
        assertEquals(listOf("Microsoft", "Minecraft", "UWP", "8", "wekyb", "3", "d", "8", "bbwe"), words("Microsoft.MinecraftUWP_8wekyb3d8bbwe"))
    }
}
