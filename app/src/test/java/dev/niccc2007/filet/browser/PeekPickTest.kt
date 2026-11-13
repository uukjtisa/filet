package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule for what stands in a folder's icon. */
class PeekPickTest {

    /** A child: its kind and its type, by name. `p.jpg` is media, `d/` a folder, `a.pdf` a file. */
    private data class Kid(val name: String) {
        val kind = when {
            name.endsWith("/") -> PeekKind.FOLDER
            name.substringAfterLast('.') in setOf("jpg", "png", "mp4", "mp3") -> PeekKind.MEDIA
            else -> PeekKind.FILE
        }
        val type = if (name.endsWith("/")) "dir" else name.substringAfterLast('.')
    }

    private fun pick(vararg names: String) =
        pickPeek(names.map(::Kid), { it.kind }, { it.type }).map { it.name }

    @Test
    fun `an empty folder shows nothing`() {
        assertEquals(emptyList<String>(), pick())
    }

    @Test
    fun `only media shows three of them`() {
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), pick("1.jpg", "2.jpg", "3.jpg", "4.jpg"))
    }

    @Test
    fun `photos and videos show one of each before a second photo`() {
        assertEquals(listOf("1.jpg", "v.mp4", "2.jpg"), pick("1.jpg", "2.jpg", "3.jpg", "v.mp4"))
    }

    @Test
    fun `only files shows three files, different types first`() {
        assertEquals(listOf("a.pdf", "c.docx", "d.zip"), pick("a.pdf", "b.pdf", "c.docx", "d.zip", "e.txt"))
    }

    @Test
    fun `only folders shows three folders`() {
        assertEquals(listOf("a/", "b/", "c/"), pick("a/", "b/", "c/", "d/"))
    }

    @Test
    fun `a mix of all three shows one of each, media in front`() {
        assertEquals(listOf("p.jpg", "d/", "a.pdf"), pick("a.pdf", "b.txt", "d/", "p.jpg", "q.jpg"))
    }

    @Test
    fun `two kinds fill the third place by taking turns, media first`() {
        assertEquals(listOf("p.jpg", "a.pdf", "q.jpg"), pick("a.pdf", "b.txt", "p.jpg", "q.jpg"))
        assertEquals(listOf("d/", "a.pdf", "e/"), pick("a.pdf", "b.txt", "d/", "e/"))
    }

    @Test
    fun `two kinds with one each show just the two`() {
        assertEquals(listOf("p.jpg", "a.pdf"), pick("a.pdf", "p.jpg"))
    }

    @Test
    fun `one thing shows one thing`() {
        assertEquals(listOf("v.mp4"), pick("v.mp4"))
    }

    @Test
    fun `the same folder always draws the same picture`() {
        val names = arrayOf("z.txt", "y/", "x.png", "w.zip", "v/", "u.mp4")
        assertEquals(pick(*names), pick(*names))
    }

    @Test
    fun `never more than asked for`() {
        val kids = (1..50).map { Kid("$it.jpg") } + (1..50).map { Kid("$it/") }
        for (show in 0..4) {
            assertTrue(pickPeek(kids, { it.kind }, { it.type }, show).size <= show)
        }
    }
}
