package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind Filet's memory: what it records, what may carry a history, and what the
 * "On this phone since" row says.
 */
class FileMemoryRulesTest {

    private val own = listOf("local:///storage/emulated/0/Filet")

    // ── what is recorded ──

    @Test
    fun `Filet's own working storage is never recorded`() {
        assertFalse(MemoryRules.records("local:///storage/emulated/0/Filet", own))
        assertFalse(MemoryRules.records("local:///storage/emulated/0/Filet/.cache/beats/x.bin", own))
        assertFalse("the memory's own file", MemoryRules.records("local:///storage/emulated/0/Filet/Memory/filet-memory.db", own))
    }

    @Test
    fun `a sibling that only shares the prefix is a user's folder`() {
        assertTrue(MemoryRules.records("local:///storage/emulated/0/Filet backups/a.zip", own))
        assertTrue(MemoryRules.records("local:///storage/emulated/0/Filets/a.zip", own))
        assertTrue(MemoryRules.records("local:///storage/emulated/0/Download/a.zip", own))
    }

    // ── what may carry a lineage ──

    @Test
    fun `only an inode match is certain`() {
        assertTrue(MemoryRules.relinkIsCertain(42, 42))
        assertFalse("no inodes: it was a name-size-time guess", MemoryRules.relinkIsCertain(0, 0))
        assertFalse(MemoryRules.relinkIsCertain(42, 43))
    }

    @Test
    fun `same folder is a rename, anywhere else is a move`() {
        assertEquals(MemoryEventKind.RENAMED, MemoryRules.kindOf("local:///a/x.txt", "local:///a/y.txt"))
        assertEquals(MemoryEventKind.MOVED, MemoryRules.kindOf("local:///a/x.txt", "local:///b/x.txt"))
    }

    @Test
    fun `a folder pattern escapes LIKE wildcards in the name`() {
        assertEquals("local:///a/100\\%\\_x/%", MemoryRules.likeUnder("local:///a/100%_x/"))
    }

    // ── On this phone since ──

    private fun mem(at: Long, found: Boolean) = ItemMemory(at, found, false, emptyList())

    @Test
    fun `an edit that resets the system date does not move the arrival`() {
        // Arrived Oct 7, Filet saw it; an editor re-saved it on Oct 10 and the system now says 10.
        val oct7 = 7L; val oct10 = 10L
        assertEquals(PhoneSince.At(oct7, bySystem = false), PhoneSinceRule.of(mem(oct7, found = false), oct10))
    }

    @Test
    fun `an older system record beats a younger Filet one`() {
        // Filet is new; the file has been here since long before it.
        assertEquals(PhoneSince.At(3, bySystem = true), PhoneSinceRule.of(mem(9, found = true), 3))
    }

    @Test
    fun `already here when Filet looked, and no system record, reads as before`() {
        assertEquals(PhoneSince.Before(9), PhoneSinceRule.of(mem(9, found = true), null))
    }

    @Test
    fun `no record anywhere is said, not invented`() {
        assertEquals(PhoneSince.NotYetSeen, PhoneSinceRule.of(null, null))
        assertEquals(PhoneSince.NotYetSeen, PhoneSinceRule.of(null, 0))
    }

    @Test
    fun `a system date alone is shown as the system's`() {
        assertEquals(PhoneSince.At(5, bySystem = true), PhoneSinceRule.of(null, 5))
    }

    @Test
    fun `a folder takes the earliest of itself and its contents`() {
        val own = PhoneSince.At(20, bySystem = false)
        assertEquals(PhoneSince.Before(4), PhoneSinceRule.merge(own, PhoneSince.Before(4)))
        assertEquals(own, PhoneSinceRule.merge(own, PhoneSince.NotYetSeen))
        assertEquals(PhoneSince.At(1, true), PhoneSinceRule.merge(PhoneSince.NotYetSeen, PhoneSince.At(1, true)))
    }
}
