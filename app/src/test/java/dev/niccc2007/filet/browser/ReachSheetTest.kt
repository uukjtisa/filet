package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the drives sheet opens with, and how it sorts what it offers. SEARCH.md §13.3. */
class ReachSheetTest {

    private fun drive(scheme: String, path: String, label: String, remote: Boolean = false, system: Boolean = false) =
        ReachDrive(VolumeKey(VPath.of(scheme, path)), label, remote, system)

    private val internal = drive("local", "/storage/emulated/0", "Internal storage")
    private val card = drive("local", "/storage/1A2B-3C4D", "SD card")
    private val nas = drive("smb", "/n1a2b3c", "Workshop NAS", remote = true)
    private val system = drive("root", "/", "System", system = true)
    private val all = listOf(internal, card, nas, system)

    @Test
    fun `it opens on what the current reach already covers`() {
        // A refinement of what is on screen - untick one share, keep the rest - not a blank form.
        val two = Reach.Drives(setOf(internal.key, nas.key))
        assertEquals(two.keys, ReachSheetModel.ticked(two, all))
    }

    @Test
    fun `everywhere opens with everything except the system volumes`() {
        val ticked = ReachSheetModel.ticked(Reach.Everywhere, all)
        assertEquals(setOf(internal.key, card.key, nas.key), ticked)
        assertFalse("no rung includes /data, so opening the sheet must not either", system.key in ticked)
    }

    @Test
    fun `a folder reach opens on the phone`() {
        assertEquals(setOf(internal.key, card.key), ReachSheetModel.ticked(Reach.Folder, all))
        assertEquals(setOf(internal.key, card.key), ReachSheetModel.ticked(Reach.Subtree, all))
    }

    @Test
    fun `drives are offered local first, then shares, then system`() {
        // The order of how much each costs to ask.
        val (phone, shares, sys) = ReachSheetModel.sections(listOf(system, nas, card, internal))
        assertEquals(listOf(card, internal), phone)
        assertEquals(listOf(nas), shares)
        assertEquals(listOf(system), sys)
    }

    @Test
    fun `the system volumes are offered here and only here`() {
        // The other half of keeping them out of every rung: they must still be reachable, or
        // root search would simply have been removed.
        val (_, _, sys) = ReachSheetModel.sections(all)
        assertTrue(system in sys)
        assertFalse(ReachLadder.rungsFor(null, all).any { system.key in (it.reach.volumes() ?: emptySet()) })
    }

    @Test
    fun `the confirm says how many drives it will search`() {
        assertEquals("Search", ReachSheetModel.confirmLabel(0))
        assertEquals("Search 1 drive", ReachSheetModel.confirmLabel(1))
        assertEquals("Search 3 drives", ReachSheetModel.confirmLabel(3))
    }
}
