package dev.niccc2007.filet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {

    @Test
    fun `a blank box narrows nothing`() {
        assertTrue(TextMatch.matches("", "anything"))
        assertTrue(TextMatch.matches("   ", null))
    }

    @Test
    fun `case is ignored and any field will do`() {
        assertTrue(TextMatch.matches("whats", "WhatsApp", "com.whatsapp"))
        assertTrue(TextMatch.matches("com.wh", "WhatsApp", "com.whatsapp"))
        assertFalse(TextMatch.matches("signal", "WhatsApp", "com.whatsapp"))
    }

    @Test
    fun `every word has to be there, in any order and any field`() {
        assertTrue(TextMatch.matches("glow disc", "Disc glow", null))
        assertTrue(TextMatch.matches("nas media", "NAS", "smb://nas/media"))
        assertFalse(TextMatch.matches("glow beat", "Disc glow"))
    }

    @Test
    fun `spans mark every hit and merge the ones that touch`() {
        assertEquals(listOf(0..3, 9..12), TextMatch.spans("Disc and disc", "disc"))
        assertEquals(listOf(0..3, 5..8), TextMatch.spans("Disc glow!", "disc glow"))
        assertEquals(listOf(0..1, 3..5), TextMatch.spans("ab cdef", "ab c cde"))
        assertTrue(TextMatch.spans("Photos", "zip").isEmpty())
    }
}
