package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveLettersTest {

    @Test
    fun `starts at Z`() = assertEquals('Z', DriveLetters.pick(setOf('C')))

    @Test
    fun `skips taken letters downwards`() = assertEquals('X', DriveLetters.pick(setOf('C', 'Z', 'Y')))

    @Test
    fun `taken letters are compared case-insensitively`() = assertEquals('Y', DriveLetters.pick(setOf('z')))

    @Test
    fun `never offers A B or C`() {
        val allButLow = ('D'..'Z').toSet()
        assertNull(DriveLetters.pick(allButLow))
    }

    @Test
    fun `last free letter is D`() = assertEquals('D', DriveLetters.pick(('E'..'Z').toSet() + 'C'))

    @Test
    fun `letterOf reads drive names and refuses anything else`() {
        assertEquals('Z', DriveLetters.letterOf("Z:"))
        assertEquals('Z', DriveLetters.letterOf("z:\\"))
        assertNull(DriveLetters.letterOf(null))
        assertNull(DriveLetters.letterOf(""))
        assertNull(DriveLetters.letterOf("\\\\host\\share"))
        assertNull(DriveLetters.letterOf("1:"))
    }
}
