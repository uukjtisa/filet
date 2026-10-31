package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What an edit will do to an archive, decided before any of it is rewritten. */
class ArchivePlanTest {

    private fun m(name: String, dir: Boolean = false) = Member(name, dir, if (dir) -1L else 10L, 0L)
    private val members = listOf(m("mimetype"), m("docs", dir = true), m("docs/a.txt"), m("docs/deep/b.txt"), m("top.txt"))

    private fun names(o: List<Outcome>) = o.map { it.name }

    private fun refused(why: PlanException.Why, block: () -> Unit) {
        try {
            block()
            fail("expected $why")
        } catch (e: PlanException) {
            assertEquals(why, e.why)
        }
    }

    @Test
    fun `a new file goes at the end and everything else stays where it was`() {
        val o = ArchivePlan.apply(members, listOf(EditOp.Put("new.txt", "/tmp/s", 3)))
        assertEquals(listOf("mimetype", "docs", "docs/a.txt", "docs/deep/b.txt", "top.txt", "new.txt"), names(o))
        // An EPUB's mimetype has to stay the first entry: kept members never move.
        assertTrue(o.first() is Outcome.Keep)
    }

    @Test
    fun `a replaced file keeps its place`() {
        val o = ArchivePlan.apply(members, listOf(EditOp.Put("docs/a.txt", "/tmp/s", 3)))
        assertEquals(names(ArchivePlan.apply(members, emptyList())), names(o))
        assertTrue(o[2] is Outcome.Fresh)
    }

    @Test
    fun `deleting a folder takes everything under it, implicit folders included`() {
        val o = ArchivePlan.apply(members, listOf(EditOp.Delete("docs")))
        assertEquals(listOf("mimetype", "top.txt"), names(o))
        // docs/deep has no entry of its own and is still a folder that can be deleted.
        assertEquals(4, ArchivePlan.apply(members, listOf(EditOp.Delete("docs/deep"))).size)
    }

    @Test
    fun `renaming a folder moves everything under it, in place, kept as stored`() {
        val o = ArchivePlan.apply(members, listOf(EditOp.Rename("docs", "papers")))
        assertEquals(listOf("mimetype", "papers", "papers/a.txt", "papers/deep/b.txt", "top.txt"), names(o))
        val moved = o[2] as Outcome.Keep
        assertEquals("docs/a.txt", moved.from)
    }

    @Test
    fun `clashes are refused, and a refused edit leaves the plan as it was`() {
        val s = ArchivePlan.State(members)
        refused(PlanException.Why.EXISTS) { s.add(EditOp.Mkdir("docs")) }
        refused(PlanException.Why.EXISTS) { s.add(EditOp.Rename("top.txt", "docs/a.txt")) }
        refused(PlanException.Why.EXISTS) { s.add(EditOp.Put("docs", "/tmp/s", 1)) }
        refused(PlanException.Why.NOT_FOUND) { s.add(EditOp.Delete("nothing.txt")) }
        refused(PlanException.Why.REFUSED) { s.add(EditOp.Rename("docs", "docs/inner")) }
        refused(PlanException.Why.REFUSED) { s.add(EditOp.Put("top.txt/x", "/tmp/s", 1)) }
        assertEquals(names(ArchivePlan.apply(members, emptyList())), names(s.outcomes))
    }

    @Test
    fun `edits in one batch see each other`() {
        val s = ArchivePlan.State(members)
        s.add(EditOp.Mkdir("new"))
        s.add(EditOp.Put("new/one.txt", "/tmp/1", 1))
        // The second file of a paste is checked against the first.
        refused(PlanException.Why.EXISTS) { s.add(EditOp.Mkdir("new/one.txt")) }
        val view = ArchivePlan.view(members, s.outcomes, now = 5L)
        assertTrue(view.any { it.name == "new/one.txt" && !it.isDir && it.size == 1L })
        assertTrue(view.any { it.name == "new" && it.isDir })
    }

    @Test
    fun `each family writes what it can, and RAR and a lone compressed file say why not`() {
        assertEquals(ArchiveWrites.Kind.ZIP, ArchiveWrites.kindOf(Archives.of("a.epub")))
        assertEquals(ArchiveWrites.Kind.TAR, ArchiveWrites.kindOf(Archives.of("a.tar.gz")))
        assertEquals(ArchiveWrites.Kind.SEVEN_ZIP, ArchiveWrites.kindOf(Archives.of("a.7z")))
        assertEquals(ArchiveWrites.Kind.STREAM, ArchiveWrites.kindOf(Archives.of("notes.txt.gz")))
        assertNull(ArchiveWrites.formatRefusal("a.zip"))
        assertTrue(ArchiveWrites.formatRefusal("photos.rar")!!.contains("RAR"))

        val one = listOf(m("notes.txt"))
        assertNull(ArchiveWrites.opRefusal(ArchiveWrites.Kind.STREAM, "Gzip", one, EditOp.Put("notes.txt", "/tmp/s", 1)))
        val add = ArchiveWrites.opRefusal(ArchiveWrites.Kind.STREAM, "Gzip", one, EditOp.Put("other.txt", "/tmp/s", 1))
        assertTrue(add!!.contains("Gzip"))
    }
}
