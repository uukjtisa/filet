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
        // A single-slot clipboard meant copying from three folders was three round trips,
        // because each Copy threw the last away.
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

    private val out = VPath.of("local", "/out")

    @Test
    fun `a move goes to history holding the files' new paths`() {
        // The batch follows its files, so moving it again moves them from where they are now.
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", out, now = 5L)
        val b = tray.batch("a")!!
        assertFalse(b.fresh)
        assertEquals(listOf(p("one.jpg", "/out")), b.items)
        assertEquals(out, b.from)
        assertEquals(5L, b.usedAt)
        assertTrue(b.allPicked)
        assertTrue(tray.fresh.isEmpty())
        assertEquals(1, tray.history.size)
    }

    @Test
    fun `a move follows the name a clash gave it`() {
        // The destination folder plus the old name is wrong exactly when two files share a name.
        val renamed = p("one (2).jpg", "/out")
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", out, landed = mapOf(p("one.jpg") to renamed))
        assertEquals(listOf(renamed), tray.batch("a")!!.items)
    }

    @Test
    fun `a moved batch moves again from where it is now`() {
        val elsewhere = VPath.of("local", "/elsewhere")
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", out, now = 1L)
            .afterPaste("a", elsewhere, now = 2L)
        val b = tray.batch("a")!!
        assertEquals(listOf(p("one.jpg", "/elsewhere")), b.items)
        assertEquals(listOf(out, elsewhere), b.pastedTo)
        assertTrue(b.isIn(elsewhere))
        assertFalse(b.isIn(out))
    }

    @Test
    fun `a copy goes to history and records where it went`() {
        // A copy is pasteable into several places, and the row says how many.
        val tray = ClipTray().add(batch("a", "one.jpg")).tray
            .afterPaste("a", VPath.of("local", "/one"))
            .afterPaste("a", VPath.of("local", "/two"))
        val b = tray.batch("a")!!
        assertFalse(b.fresh)
        assertEquals(listOf(p("one.jpg")), b.items)
        assertEquals(listOf(VPath.of("local", "/one"), VPath.of("local", "/two")), b.pastedTo)
    }

    @Test
    fun `a half-failed move keeps the failed files new and the moved ones in history`() {
        // The rule that matters most. A clipboard that empties itself after a partial failure
        // is how somebody loses track of what actually moved.
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", out, failed = setOf(p("two.jpg")), splitId = "a2")
        val left = tray.batch("a")!!
        assertTrue(left.fresh)
        assertEquals(listOf(p("two.jpg")), left.items)
        assertTrue(left.allPicked)
        val done = tray.batch("a2")!!
        assertFalse(done.fresh)
        assertEquals(listOf(p("one.jpg", "/out")), done.items)
    }

    @Test
    fun `a move that failed outright changes nothing`() {
        val before = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
        val after = before.afterPaste("a", out, failed = setOf(p("one.jpg")))
        assertEquals(before, after)
    }

    @Test
    fun `a move of only the ticked files leaves the unticked ones new`() {
        // The unticked files are still on disk where they were, and still queued.
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg", op = PendingOp.MOVE)).tray
            .unpick("a", p("two.jpg"))
            .afterPaste("a", out, splitId = "a2")
        val left = tray.batch("a")!!
        assertTrue(left.fresh)
        assertEquals(listOf(p("two.jpg")), left.items)
        assertTrue(left.nothingPicked)
        assertEquals(listOf(p("one.jpg", "/out")), tray.batch("a2")!!.items)
    }

    @Test
    fun `copy here instead leaves a move's files where they were`() {
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterCopyInstead("a", out, now = 3L)
        val b = tray.batch("a")!!
        assertFalse(b.fresh)
        assertEquals(listOf(p("one.jpg")), b.items)
        assertEquals(listOf(out), b.pastedTo)
        assertFalse(b.isIn(out))
    }

    @Test
    fun `a copy that copied nothing stays new`() {
        val before = ClipTray().add(batch("a", "one.jpg")).tray
        assertEquals(before, before.afterPaste("a", out, failed = setOf(p("one.jpg"))))
        assertEquals(before, before.afterCopyInstead("a", out, failed = setOf(p("one.jpg"))))
    }

    @Test
    fun `history keeps the newest thirty and never drops a new batch`() {        var tray = ClipTray()
        for (i in 1..35) {
            tray = tray.add(batch("h$i", "f$i.jpg")).tray.afterPaste("h$i", out, now = i.toLong())
        }
        tray = tray.add(batch("new", "fresh.jpg")).tray
        assertEquals(ClipTray.HISTORY_MAX, tray.history.size)
        assertEquals("h35", tray.history.first().id)
        assertEquals("h6", tray.history.last().id)
        assertEquals(listOf("new"), tray.fresh.map { it.id })
    }

    @Test
    fun `a file in a moved batch can be queued to move again`() {
        // History is not queued: moving one of its files again is the ordinary next step.
        val tray = ClipTray().add(batch("a", "one.jpg", op = PendingOp.MOVE)).tray
            .afterPaste("a", out)
        val added = tray.add(batch("b", "one.jpg", op = PendingOp.MOVE, dir = "/out"))
        assertTrue(added.added)
        assertEquals(0, added.droppedAlreadyQueued)
    }

    @Test
    fun `the pill counts only new batches`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .afterPaste("a", out)
            .add(batch("b", "three.jpg", op = PendingOp.MOVE)).tray
        assertEquals(1, tray.freshPicked)
        assertTrue(tray.freshAllMoves)
        assertFalse(tray.worthPastingAll)
    }

    // ── surviving a restart ───────────────────────────────────────────────────

    @Test
    fun `the clipboard reads back exactly as it was saved`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two words, and a tab\t.jpg")).tray
            .unpick("a", p("one.jpg"))
            .add(batch("b", "three.pdf", op = PendingOp.MOVE, dir = "/docs")).tray
            .afterPaste("b", out, now = 9L)
            .add(ClipBatch("c", listOf(p("x.jpg"), p("y.jpg", "/other")), PendingOp.COPY, null, 4L)).tray
        assertEquals(tray, ClipStore.decode(ClipStore.encode(tray)))
    }

    @Test
    fun `an unreadable save is an empty clipboard, not a crash`() {
        assertTrue(ClipStore.decode(null).isEmpty)
        assertTrue(ClipStore.decode("").isEmpty)
        assertTrue(ClipStore.decode("clip0\u001Dwhatever").isEmpty)
        assertTrue(ClipStore.decode("clip1\u001Dbroken").isEmpty)
    }

    @Test
    fun `one bad row costs that row and keeps the rest`() {
        val good = ClipTray().add(batch("a", "one.jpg")).tray
        val raw = ClipStore.encode(good) + "\u001Dnot\u001Ea\u001Ebatch"
        assertEquals(good, ClipStore.decode(raw))
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
        assertEquals(tray, tray.afterCopyInstead("ghost", VPath.of("local", "/out")))
        assertEquals(tray, tray.remove("ghost"))
    }

    // ── the pill while a paste runs ───────────────────────────────────────────

    @Test
    fun `a batch leaves the pill the moment its paste starts`() {
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg")).tray
            .add(batch("b", "three.jpg", op = PendingOp.MOVE)).tray
            .startPaste(listOf("a"))
        assertEquals(listOf("b"), tray.waiting.map { it.id })
        assertEquals(1, tray.waitingPicked)
        assertTrue(tray.waitingAllMoves)
        // Still new until the paste says what it left: the record has not changed yet.
        assertEquals(listOf("a", "b"), tray.fresh.map { it.id })
        assertTrue(tray.startPaste(listOf("b")).waiting.isEmpty())
    }

    @Test
    fun `a paste that ends with the batch still new puts it back on the pill`() {
        val out = VPath.of("local", "/out")
        val started = ClipTray().add(batch("a", "one.jpg")).tray.startPaste(listOf("a"))
        // Nothing copied: afterPaste keeps it new, and ending the paste shows it again.
        val failed = started.afterPaste("a", out, failed = setOf(p("one.jpg"))).endPaste(listOf("a"))
        assertEquals(listOf("a"), failed.waiting.map { it.id })
        // A Stop: nothing after the copy ran, only the end of the paste.
        assertEquals(listOf("a"), started.endPaste(listOf("a")).waiting.map { it.id })
        // A paste that worked goes to history and stays off the pill.
        val done = started.afterPaste("a", out).endPaste(listOf("a"))
        assertTrue(done.waiting.isEmpty())
        assertEquals(listOf("a"), done.history.map { it.id })
    }

    @Test
    fun `the part of a move left behind comes back on the pill`() {
        val out = VPath.of("local", "/out")
        val tray = ClipTray().add(batch("a", "one.jpg", "two.jpg", op = PendingOp.MOVE)).tray
            .startPaste(listOf("a"))
            .afterPaste("a", out, failed = setOf(p("two.jpg")), splitId = "a-moved")
            .endPaste(listOf("a"))
        assertEquals(listOf("a"), tray.waiting.map { it.id })
        assertEquals(1, tray.waitingPicked)
    }

    @Test
    fun `being pasted is never written down`() {
        val tray = ClipTray().add(batch("a", "one.jpg")).tray.startPaste(listOf("a"))
        val back = ClipStore.decode(ClipStore.encode(tray))
        assertEquals(listOf("a"), back.waiting.map { it.id })
    }
}
