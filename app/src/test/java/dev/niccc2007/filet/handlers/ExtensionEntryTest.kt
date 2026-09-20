package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typing an extension, including the ways people get it slightly wrong.
 */
class ExtensionEntryTest {

    @Test
    fun `a plain extension is taken as typed`() {
        val v = ExtensionEntry.inspect("mcaddon")
        assertEquals("mcaddon", v.cleaned)
        assertEquals(0, v.leadingDots)
        assertFalse("nothing to ask about", v.askAboutDots)
        assertTrue(v.valid)
    }

    @Test
    fun `case and whitespace are tidied without comment`() {
        assertEquals("mcaddon", ExtensionEntry.inspect("  MCAddon ").cleaned)
    }

    @Test
    fun `one leading dot is how everybody writes it and is not worth a question`() {
        val v = ExtensionEntry.inspect(".mcaddon")
        assertEquals("mcaddon", v.cleaned)
        assertEquals(1, v.leadingDots)
        assertFalse("asking here would be nagging", v.askAboutDots)
    }

    @Test
    fun `several leading dots are raised rather than silently eaten`() {
        // The reported case. Stripping them silently is right nearly always and wrong
        // invisibly when it is wrong.
        val v = ExtensionEntry.inspect("...mcaddon")
        assertEquals("mcaddon", v.cleaned)
        assertEquals(3, v.leadingDots)
        assertTrue(v.askAboutDots)
    }

    @Test
    fun `all three answers are available and produce different results`() {
        val v = ExtensionEntry.inspect("..mcaddon")
        assertEquals("mcaddon", ExtensionEntry.resolve(v, ExtensionEntry.Choice.DROP_DOTS))
        assertEquals(".mcaddon", ExtensionEntry.resolve(v, ExtensionEntry.Choice.ONE_DOT))
        assertEquals("..mcaddon", ExtensionEntry.resolve(v, ExtensionEntry.Choice.KEEP))
    }

    @Test
    fun `the question says what was typed and what is usual`() {
        val q = ExtensionEntry.dotQuestion(ExtensionEntry.inspect("..mcaddon"))
        assertTrue(q, q.contains("2 dots"))
        assertTrue(q, q.contains("mcaddon"))
        assertTrue("it has to say what the normal answer is", q.contains("without"))
    }

    @Test
    fun `an inner dot is left alone, because a compound extension is real`() {
        // `tar.gz` typed in full is not a slip and must not become `targz`.
        val v = ExtensionEntry.inspect("tar.gz")
        assertEquals("tar.gz", v.cleaned)
        assertFalse(v.askAboutDots)
    }

    @Test
    fun `dots and nothing else is not an extension`() {
        val v = ExtensionEntry.inspect("...")
        assertFalse(v.valid)
        assertFalse("there is nothing to ask about an empty name", v.askAboutDots)
    }

    @Test
    fun `blank input is refused`() {
        assertFalse(ExtensionEntry.inspect("   ").valid)
        assertFalse(ExtensionEntry.inspect("").valid)
    }

    @Test
    fun `an absurdly long entry is bounded`() {
        val v = ExtensionEntry.inspect("a".repeat(200))
        assertEquals(ExtensionEntry.MAX, v.cleaned.length)
    }

    @Test
    fun `real compound and long extensions still fit`() {
        for (ext in listOf("sqlite3", "webmanifest", "mcaddon", "tar.gz", "apkm")) {
            assertEquals(ext, ExtensionEntry.inspect(ext).cleaned)
        }
    }
}
