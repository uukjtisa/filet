package dev.niccc2007.filet.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shortcut is listed when the launcher confirms it, and not before.
 *
 * The bug: every request was written to the list the moment it was made, so Cancel and Back on
 * the launcher's dialog left a row for an icon that never existed.
 */
class PinReconcileTest {

    private fun rec(id: String, unconfirmed: Boolean = false) =
        ShortcutRecord(id, ShortcutKind.TAB, id, id, createdAt = 0L, unconfirmed = unconfirmed)

    private fun ask(id: String, at: Long) = PinReconcile.Pending(rec(id), at)

    private fun decide(
        records: List<ShortcutRecord> = emptyList(),
        pending: List<PinReconcile.Pending> = emptyList(),
        pinned: Set<String> = emptySet(),
        reports: Boolean = true,
        now: Long,
    ) = PinReconcile.decide(records, pending, pinned, reports, now, graceMs = 2_000L)

    @Test
    fun `cancel leaves nothing`() {
        val out = decide(pending = listOf(ask("tab:playlists", at = 0)), now = 2_500)
        assertTrue(out.records.isEmpty())
        assertTrue(out.pending.isEmpty())
    }

    @Test
    fun `add is listed as soon as the launcher shows it pinned`() {
        val out = decide(pending = listOf(ask("tab:playlists", at = 0)), pinned = setOf("tab:playlists"), now = 100)
        assertEquals(listOf("tab:playlists"), out.records.map { it.id })
        assertFalse(out.records.single().unconfirmed)
        assertTrue(out.pending.isEmpty())
    }

    @Test
    fun `inside the grace time a request waits, neither listed nor dropped`() {
        val out = decide(pending = listOf(ask("tab:playlists", at = 0)), now = 1_000)
        assertTrue(out.records.isEmpty())
        assertEquals(listOf("tab:playlists"), out.pending.map { it.record.id })
    }

    @Test
    fun `an icon taken off the home screen leaves the list`() {
        val out = decide(records = listOf(rec("tab:home"), rec("tab:apps")), pinned = setOf("tab:apps"), now = 0)
        assertEquals(listOf("tab:apps"), out.records.map { it.id })
    }

    @Test
    fun `a launcher that never reports keeps the row and marks it`() {
        val out = decide(pending = listOf(ask("tab:apps", at = 0)), reports = false, now = 5_000)
        assertTrue(out.records.single().unconfirmed)
    }

    @Test
    fun `a launcher that never reports never loses a row it already had`() {
        val out = decide(records = listOf(rec("tab:home")), reports = false, now = 0)
        assertEquals(listOf("tab:home"), out.records.map { it.id })
    }

    @Test
    fun `an unconfirmed row the launcher later shows is confirmed`() {
        val out = decide(records = listOf(rec("tab:apps", unconfirmed = true)), pinned = setOf("tab:apps"), now = 0)
        assertFalse(out.records.single().unconfirmed)
    }

    @Test
    fun `an unconfirmed row goes once the launcher is known to report and does not show it`() {
        // A first Cancel on a phone with no pins yet cannot be told from a silent launcher. The
        // next confirmed pin settles it, and the stray row goes.
        val out = decide(records = listOf(rec("tab:apps", unconfirmed = true)), pinned = setOf("tab:home"), now = 0)
        assertTrue(out.records.none { it.id == "tab:apps" })
    }

    @Test
    fun `asking again for a listed shortcut does not drop it while the answer is awaited`() {
        val out = decide(records = listOf(rec("tab:home")), pending = listOf(ask("tab:home", at = 0)), now = 500)
        assertEquals(listOf("tab:home"), out.records.map { it.id })
    }

    @Test
    fun `a fresh request replaces the old row rather than doubling it`() {
        val renamed = PinReconcile.Pending(rec("tab:home").copy(label = "Start"), 0)
        val out = decide(records = listOf(rec("tab:home")), pending = listOf(renamed), pinned = setOf("tab:home"), now = 10)
        assertEquals(listOf("Start"), out.records.map { it.label })
    }

    @Test
    fun `other rows are untouched by one request`() {
        val out = decide(
            records = listOf(rec("tab:home"), rec("action:SEARCH")),
            pending = listOf(ask("tab:apps", at = 0)),
            pinned = setOf("tab:home", "action:SEARCH"),
            now = 5_000,
        )
        assertEquals(setOf("tab:home", "action:SEARCH"), out.records.map { it.id }.toSet())
    }
}
