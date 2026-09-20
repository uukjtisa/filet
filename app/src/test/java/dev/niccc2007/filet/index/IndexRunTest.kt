package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Activity row says while the index is running.
 *
 * Reported as: it can appear stuck. The only live thing on the row was a count, and a count
 * moving in thousands is indistinguishable from a count that has stopped - so a long crawl
 * read as a hung app.
 */
class IndexRunTest {

    // ── the report ──

    @Test
    fun `the line names the folder being read, not just a number`() {
        val line = IndexRun.readingLine(12_480, "/storage/emulated/0/Download/games")
        assertTrue("no count in: $line", line.contains("12480"))
        assertTrue("no folder in: $line", line.contains("games"))
    }

    @Test
    fun `a count alone is still shown when there is no folder yet`() {
        // The first moments of a run, before any directory has been opened.
        assertEquals("0 files", IndexRun.readingLine(0, null))
        assertEquals("40 files", IndexRun.readingLine(40, ""))
    }

    // ── keeping the useful end ──

    @Test
    fun `a long path keeps its tail`() {
        // A crawl walks deep, so the part that changes is the end. Trimming from the right
        // would leave forty identical rows all reading the same prefix.
        val deep = "/storage/emulated/0/Android/data/com.example.something/files/cache/images"
        val short = IndexRun.shortPath(deep)
        assertTrue("lost the tail: $short", short.endsWith("images"))
        assertTrue("too long: ${short.length}", short.length <= 44)
    }

    @Test
    fun `a short path is left exactly as it is`() {
        assertEquals("/storage/emulated/0", IndexRun.shortPath("/storage/emulated/0"))
    }

    @Test
    fun `two different deep folders do not shorten to the same string`() {
        // The failure that makes a shortener pointless: every row identical again.
        val a = IndexRun.shortPath("/storage/emulated/0/Android/data/com.a.app/files/photos")
        val b = IndexRun.shortPath("/storage/emulated/0/Android/data/com.b.app/files/videos")
        assertTrue("$a == $b", a != b)
    }

    @Test
    fun `shortening never cuts mid-segment when it can avoid it`() {
        val s = IndexRun.shortPath("/storage/emulated/0/Download/a-very-long-folder-name-here/inner")
        assertTrue("cut mid-name: $s", s.startsWith("/") || s.startsWith("…/"))
    }

    // ── the run itself ──

    @Test
    fun `reading is carried on the run and shortened for display`() {
        val run = IndexRun(active = true, seen = 900, reading = "/storage/emulated/0/DCIM/Camera")
        assertEquals("/storage/emulated/0/DCIM/Camera", run.reading)
        assertTrue(run.readingShort.endsWith("Camera"))
    }

    @Test
    fun `a run that is not reading anything says nothing`() {
        assertEquals("", IndexRun().readingShort)
    }
}
