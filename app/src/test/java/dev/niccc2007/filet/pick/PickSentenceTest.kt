package dev.niccc2007.filet.pick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording, pinned.
 *
 * It is tested because the wording IS the feature: a picker whose sentence does not say what
 * pressing the button will do is the defect this replaced, and the two directions - Filet
 * asking itself, another app asking Filet - used to read completely differently.
 */
class PickSentenceTest {

    private fun of(
        want: PickSpec.Want,
        verb: String = "",
        subject: String = "",
        asker: String? = null,
        kind: String = "",
    ) = PickSentence.of(want, verb, subject, asker, kind)

    @Test fun `a folder pick names the operation and what it acts on`() {
        val s = of(PickSpec.Want.FOLDER, verb = "extract", subject = "Photos.zip")
        assertEquals("Choose a folder to extract Photos.zip", s.headline)
        assertEquals("Extract into this", s.confirm)
    }

    @Test fun `a folder pick with no subject still names the operation`() {
        assertEquals("Choose a folder to share", of(PickSpec.Want.FOLDER, verb = "share").headline)
    }

    @Test fun `a folder pick with neither falls back to the plain question`() {
        val s = of(PickSpec.Want.FOLDER)
        assertEquals("Choose a folder", s.headline)
        assertEquals("Use this folder", s.confirm)
    }

    @Test fun `another app is named rather than described`() {
        // "Choose an image" does not tell anybody why their file browser just changed. The
        // asking app's name does.
        val s = of(PickSpec.Want.FILE, asker = "Instagram", kind = "an image")
        assertEquals("Choose an image to submit — Instagram", s.headline)
    }

    @Test fun `several files for another app says so`() {
        val s = of(PickSpec.Want.FILES, asker = "Instagram", kind = "an image")
        assertEquals("Choose files to submit — Instagram", s.headline)
        assertEquals("Use these", s.confirm)
    }

    @Test fun `the app's own file pick names what it is for`() {
        val s = of(PickSpec.Want.FILE, subject = "song.mp3", kind = "a picture")
        assertEquals("Choose a picture for song.mp3", s.headline)
    }

    @Test fun `an unstated kind never leaves a hole in the sentence`() {
        val s = of(PickSpec.Want.FILE)
        assertEquals("Choose a file", s.headline)
        assertEquals("Tap a file", s.hint)
    }

    @Test fun `every case says what to do next`() {
        for (want in PickSpec.Want.entries) {
            assertTrue(want.name, of(want).hint.isNotBlank())
            assertTrue(want.name, of(want).confirm.isNotBlank())
            assertTrue(want.name, of(want).headline.isNotBlank())
        }
    }
}
