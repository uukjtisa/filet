package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The access-code rule the phone and the PC share. */
class AccessCodeTest {

    @Test
    fun `any ASCII letters and digits, from one character, case kept`() {
        assertTrue(AccessCode.acceptable("a"))
        assertTrue(AccessCode.acceptable("Ab12"))
        assertTrue(AccessCode.acceptable("x".repeat(64)))
        assertEquals("Ab12", AccessCode.clean(" Ab 12 "))
    }

    @Test
    fun `nothing else passes`() {
        assertFalse(AccessCode.acceptable(""))
        assertFalse(AccessCode.acceptable(null))
        assertFalse(AccessCode.acceptable("x".repeat(65)))
        assertFalse(AccessCode.acceptable("ab-12"))
        // A letter, but not one a UNC path or a URL carries intact.
        assertFalse(AccessCode.acceptable("café"))
        assertNull(AccessCode.clean("  "))
    }

    @Test
    fun `a field filters to what can pass`() {
        assertEquals("caf12", AccessCode.typing("café-12"))
        assertEquals(64, AccessCode.typing("a".repeat(80)).length)
    }

    @Test
    fun `every refusal says why`() {
        assertNotNull(AccessCode.problem(""))
        assertNotNull(AccessCode.problem("a b"))
        assertNotNull(AccessCode.problem("x".repeat(65)))
        assertNull(AccessCode.problem("Q"))
    }

    @Test
    fun `generated codes stay in the readable alphabet, and pass`() {
        val c = AccessCode.generate(200, Random(1))
        assertTrue(c.all { it in AccessCode.READABLE })
        assertFalse(AccessCode.acceptable(c))
        assertTrue(AccessCode.acceptable(AccessCode.generate(10, Random(2))))
    }
}
