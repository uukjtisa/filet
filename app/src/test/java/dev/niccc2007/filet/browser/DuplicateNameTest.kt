package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateNameTest {

    @Test
    fun `the first copy is just the prefix`() {
        assertEquals("Copy of report.pdf", DuplicateName.of("report.pdf", emptySet()))
    }

    @Test
    fun `the extension stays last`() {
        // Put the counter after the extension and the file stops opening.
        val out = DuplicateName.of("report.pdf", setOf("Copy of report.pdf"))
        assertTrue(out, out.endsWith(".pdf"))
        assertEquals("Copy of report (2).pdf", out)
    }

    @Test
    fun `a second copy counts rather than prefixing again`() {
        // Prefixing again gives "Copy of Copy of report.pdf", and a third gives the joke.
        var taken = setOf("Copy of report.pdf")
        val second = DuplicateName.of("report.pdf", taken)
        taken = taken + second
        val third = DuplicateName.of("report.pdf", taken)
        assertEquals("Copy of report (2).pdf", second)
        assertEquals("Copy of report (3).pdf", third)
        assertTrue(third, !third.contains("Copy of Copy"))
    }

    @Test
    fun `a folder has no extension to protect`() {
        assertEquals("Copy of Downloads", DuplicateName.of("Downloads", emptySet()))
        assertEquals(
            "Copy of Downloads (2)",
            DuplicateName.of("Downloads", setOf("Copy of Downloads")),
        )
    }

    @Test
    fun `a dotfile keeps its whole name`() {
        // `.gitignore` is a name, not an extension. Splitting it would produce something that
        // is not the same kind of file.
        assertEquals("Copy of .gitignore", DuplicateName.of(".gitignore", emptySet()))
        assertEquals(
            "Copy of .gitignore (2)",
            DuplicateName.of(".gitignore", setOf("Copy of .gitignore")),
        )
    }

    @Test
    fun `only the last extension is treated as one`() {
        assertEquals("Copy of archive.tar.gz", DuplicateName.of("archive.tar.gz", emptySet()))
        assertEquals(
            "Copy of archive.tar (2).gz",
            DuplicateName.of("archive.tar.gz", setOf("Copy of archive.tar.gz")),
        )
    }

    @Test
    fun `a name that differs only in case still counts as taken`() {
        // The volumes this writes to are mostly case-insensitive, so handing back a name that
        // differs only in case is handing back the collision the caller asked to avoid.
        val out = DuplicateName.of("Report.PDF", setOf("copy of report.pdf"))
        assertEquals("Copy of Report (2).PDF", out)
    }

    @Test
    fun `it never returns a name that is already taken`() {
        // The property, over a folder that has accumulated a run of copies.
        var taken = setOf("report.pdf")
        repeat(25) {
            val next = DuplicateName.of("report.pdf", taken)
            assertTrue("$next was already there", next.lowercase() !in taken.map { t -> t.lowercase() })
            taken = taken + next
        }
        assertEquals(26, taken.size)
    }
}
