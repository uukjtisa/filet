package dev.niccc2007.filet.ops

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard model.
 *
 * Every rule here is one that silently does the WRONG thing rather than failing loudly, which
 * is why the model is pure and tested rather than living in the ViewModel: a clipboard that
 * quietly drops a file, or quietly keeps a moved one, looks exactly like a clipboard that
 * worked.
 */
class ClipTrayTest {

    private fun p(name: String, dir: String = "/dcim") = VPath.of("local", "$dir/$name")

    private fun batch(
        id: String,
        vararg names: String,
        op: PendingOp = PendingOp.COPY,
        dir: String = "/dcim",
    ) = ClipBatch(
        id = id,
        items = names.map { p(it, dir) },
        op = op,
        from = VPath.of("local", dir),
        addedAt = 0L,
    )

    // ── accumulating ──────────────────────────────────────────────────────────

    @Test
    fun `batches stack instead of replacing`() {
        // The whole request. A single-slot clipboard meant copying from three folders was
        // three round trips, because each Copy threw the last away.
        val tray = ClipTray()
            .add(batch("a", "one.jpg")).tray
            .add(batch("b", "two.pdf", dir = "/docs")).tray
        assertEquals(2, tray.size)
        assertEquals(2, tray.totalItems)
    }

    @Test
    fun `oldest first, so a paste-all runs in the order they were picked up`() {
        val tray = ClipTray()
            .add(batch("a", "one.jpg")).tray
            .add(batch("b", "two.jpg")).tray
            .add(batch("c", "three.jpg")).tray
        assertEquals(listOf("a", "b", "c"), tray.batches.map { it.id })
    }

    @Test
    fun `everything is picked when a batch arrives`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
        assertTrue(tray.batch("a")!!.allPicked)
        assertEquals(2, tray.totalPicked)
    }

    // ── the two collisions ────────────────────────────────────────────────────

    @Test
    fun `a path already queued to move cannot be queued to move twice`() {
        // The first paste takes it away and the second fails with "no longer there", which
        // reads as a bug in Filet rather than as two instructions that could not both be true.
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
        val added = tray.add(batch("b", "one.jpg", "two.jpg", op = PendingOp.MOVE))
        assertTrue(added.added)
        assertEquals(1, added.droppedAlreadyQueued)
        assertEquals(listOf(p("two.jpg")), added.tray.batch("b")!!.items)
    }

    @Test
    fun `a batch left with nothing is not added at all`() {
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
        val added = tray.add(batch("b", "one.jpg", op = PendingOp.MOVE))
        assertFalse(added.added)
        assertEquals(1, added.droppedAlreadyQueued)
        assertEquals(1, added.tray.size)
        assertNull(added.tray.batch("b"))
    }

    @Test
    fun `the same path in two copies is left alone`() {
        // Copying one file to two places is the feature, not a mistake.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
        val added = tray.add(batch("b", "one.jpg"))
        assertTrue(added.added)
        assertEquals(0, added.droppedAlreadyQueued)
        assertEquals(2, added.tray.totalItems)
    }

    @Test
    fun `a copy of something queued to move is left alone`() {
        // Only a second MOVE is impossible. Copying a file that is also going to be moved is
        // odd but well defined, and refusing it would be the clipboard inventing a rule.
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
        val added = tray.add(batch("b", "one.jpg", op = PendingOp.COPY))
        assertTrue(added.added)
        assertEquals(0, added.droppedAlreadyQueued)
    }

    // ── picking ───────────────────────────────────────────────────────────────

    @Test
    fun `unticking keeps the file and changes only what the next paste carries`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .unpick("a", p("one.jpg"))
        val b = tray.batch("a")!!
        assertEquals(2, b.size)
        assertEquals(1, b.pickedCount)
        assertEquals(listOf(p("two.jpg")), b.payload)
    }

    @Test
    fun `a batch with nothing ticked stays in the tray`() {
        // A batch that vanished because its last tick came off would look like a bug.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray.pickNone("a")
        assertEquals(1, tray.size)
        assertTrue(tray.batch("a")!!.nothingPicked)
        assertEquals(0, tray.totalPicked)
    }

    @Test
    fun `pick all puts every file back, including ones unticked one at a time`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .unpick("a", p("one.jpg"))
            .unpick("a", p("two.jpg"))
            .pickAll("a")
        assertTrue(tray.batch("a")!!.allPicked)
    }

    @Test
    fun `payload keeps the batch's own order, not the order things were ticked`() {
        val tray = ClipTray().add(batch("a", "a.jpg", "b.jpg", "c.jpg")).tray
            .pickNone("a")
            .pick("a", p("c.jpg"))
            .pick("a", p("a.jpg"))
        assertEquals(listOf(p("a.jpg"), p("c.jpg")), tray.batch("a")!!.payload)
    }

    @Test
    fun `removing the last file removes the batch`() {
        // A batch of nothing is a row that cannot act.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray.without("a", p("one.jpg"))
        assertTrue(tray.isEmpty)
    }

    @Test
    fun `removing a file is not the same as unticking it`() {
        val unticked = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .unpick("a", p("one.jpg"))
        val removed = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .without("a", p("one.jpg"))
        assertEquals(2, unticked.batch("a")!!.size)
        assertEquals(1, removed.batch("a")!!.size)
    }

    // ── what a paste leaves behind ────────────────────────────────────────────

    @Test
    fun `a move is consumed`() {
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", VPath.of("local", "/out"))
        assertTrue(tray.isEmpty)
    }

    @Test
    fun `a copy survives and records where it went`() {
        // Asked for directly: a copy should be pasteable into several places.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
            .afterPaste("a", VPath.of("local", "/one"))
            .afterPaste("a", VPath.of("local", "/two"))
        assertEquals(1, tray.size)
        assertEquals(
            listOf(VPath.of("local", "/one"), VPath.of("local", "/two")),
            tray.batch("a")!!.pastedTo,
        )
    }

    @Test
    fun `a half-failed move keeps exactly what is still there to move`() {
        // The rule that matters most. A clipboard that empties itself after a partial failure
        // is how somebody loses track of what actually moved.
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", VPath.of("local", "/out"), failed = setOf(p("two.jpg")))
        assertEquals(listOf(p("two.jpg")), tray.batch("a")!!.items)
        assertTrue(tray.batch("a")!!.allPicked)
    }

    @Test
    fun `a move that failed outright is not consumed`() {
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", VPath.of("local", "/out"), failed = setOf(p("one.jpg")))
        assertEquals(1, tray.size)
    }

    @Test
    fun `a move of only the ticked files consumes only the batch it emptied`() {
        // Pasting a partially ticked MOVE takes away what it took; the rest is still on disk
        // and still queued, so the batch has to survive with the untouched files in it.
        val before = ClipTray().add(batch("a", "one.jpg", "two.jpg", op = PendingOp.MOVE)).tray
            .unpick("a", p("two.jpg"))
        val after = before.afterPaste("a", VPath.of("local", "/out"))
        // Nothing failed, so the whole batch goes - which is the honest reading of the rule as
        // written, and is pinned here so a change to it is a decision rather than a surprise.
        assertTrue(after.isEmpty)
    }

    // ── what the tray says about itself ───────────────────────────────────────

    @Test
    fun `paste-all is not offered for a single batch`() {
        // It would be the same press as that batch's own button, twice.
        val one = ClipTray().add(batch("a", "one.jpg")).tray
        assertFalse(one.worthPastingAll)
        val two = one.add(batch("b", "two.jpg")).tray
        assertTrue(two.worthPastingAll)
    }

    @Test
    fun `paste-all ignores batches with nothing ticked`() {
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
            .add(batch("b", "two.jpg")).tray
            .pickNone("b")
        assertFalse(tray.worthPastingAll)
    }

    @Test
    fun `the summary counts the two verbs separately`() {
        // "Paste 11" over a tray holding a move is a promise about three files that is not a
        // copy.
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .add(batch("b", "three.pdf", op = PendingOp.MOVE, dir = "/docs")).tray
        assertEquals("2 copied, 1 moved", tray.allSummary())
    }

    @Test
    fun `the summary leaves out a verb with nothing behind it`() {
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
        assertEquals("1 copied", tray.allSummary())
    }

    @Test
    fun `clear empties everything`() {
        val tray = ClipTray().add(batch("a", "one.jpg")).tray.clear()
        assertTrue(tray.isEmpty)
        assertEquals(0, tray.totalPicked)
    }

    @Test
    fun `an unknown id changes nothing rather than throwing`() {
        // Every mutator is reachable from a tray row that may have been removed under it by a
        // paste finishing elsewhere.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
        assertEquals(tray, tray.pick("ghost", p("x")))
        assertEquals(tray, tray.unpick("ghost", p("x")))
        assertEquals(tray, tray.without("ghost", p("x")))
        assertEquals(tray, tray.afterPaste("ghost", VPath.of("local", "/out")))
        assertEquals(tray, tray.remove("ghost"))
    }
}
