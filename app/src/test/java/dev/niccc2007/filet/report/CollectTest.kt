package dev.niccc2007.filet.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Where the report form keeps its pictures, and which files it will touch: issue #22. */
class CollectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a picture is looked for where it is written`() {
        // The test that would have caught 0.2.0: the name a picture is written under and the
        // check every read, size and delete goes through, held to one another.
        val folder = Collect.folder(tmp.newFolder("cache")).apply { mkdirs() }
        val picture = Collect.picture(folder, index = 0, now = 1_730_000_000_000).apply { writeBytes(ByteArray(3)) }
        assertTrue(Collect.owns(folder, picture))
        assertEquals("in the app's own cache, as R3's exemption says", "report", folder.name)
    }

    @Test
    fun `nothing else is one of its pictures`() {
        val cache = tmp.newFolder("cache")
        val folder = Collect.folder(cache).apply { mkdirs() }
        val name = Collect.picture(folder, 1, 2L).name
        assertFalse("not bound yet", Collect.owns(null, File(folder, name)))
        assertFalse("where 0.2.0 put them", Collect.owns(folder, File(tmp.newFolder("Filet", "Reports"), name)))
        assertFalse("beside the folder", Collect.owns(folder, File(cache, name)))
        assertFalse("reached with ..", Collect.owns(folder, File(folder, "../$name")))
        assertFalse("below it", Collect.owns(folder, File(File(folder, "x"), name)))
        assertFalse("another file in it", Collect.owns(folder, File(folder, "crash-1.txt")))
    }

    @Test
    fun `an abandoned report's pictures go, and the open form's stay`() {
        val folder = Collect.folder(tmp.newFolder("cache")).apply { mkdirs() }
        val mine = Collect.picture(folder, 0, 10L).apply { writeBytes(ByteArray(1)) }
        val left = Collect.picture(folder, 0, 5L).apply { writeBytes(ByteArray(1)) }
        File(folder, "notes.txt").writeText("not a picture")
        assertEquals(listOf(left.name), Collect.orphans(folder, keep = listOf(mine.absolutePath)).map { it.name })
        assertEquals(
            "once a report is sent, every picture and nothing else",
            listOf(left.name, mine.name).sorted(),
            Collect.orphans(folder, keep = emptyList()).map { it.name }.sorted(),
        )
        assertEquals(emptyList<File>(), Collect.orphans(File(folder, "missing"), keep = emptyList()))
    }
}
