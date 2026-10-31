package dev.niccc2007.filet.browser

import dev.niccc2007.filet.browser.ResultGroups.State
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When results split by drive, and what each header says. SEARCH.md §13.5.
 */
class ResultGroupsTest {

    private fun drive(scheme: String, path: String, label: String, remote: Boolean = false, system: Boolean = false) =
        ReachDrive(VolumeKey(VPath.of(scheme, path)), label, remote, system)

    private val internal = drive("local", "/storage/emulated/0", "Internal storage")
    private val card = drive("local", "/storage/1A2B-3C4D", "SD card")
    private val nas = drive("smb", "/n1a2b3c", "Workshop NAS", remote = true)
    private val tablet = drive("sftp", "/n9z8y7", "Spare tablet", remote = true)
    private val all = listOf(internal, card, nas, tablet)

    private fun file(scheme: String, path: String) = VNode(VPath.of(scheme, path), isDir = false, size = 1, mtime = 0)

    private val onPhone = file("local", "/storage/emulated/0/Download/a.pdf")
    private val onCard = file("local", "/storage/1A2B-3C4D/DCIM/b.jpg")
    private val onNas = file("smb", "/n1a2b3c/Projects/c.pdf")

    // ── when it groups at all ──

    @Test
    fun `a local-only reach stays one flat list`() {
        // Internal, the card and a stick cost the same to open and never fail differently, so
        // splitting them would cost the ranking and buy nothing.
        val phone = Reach.Drives(setOf(internal.key, card.key))
        assertNull(ResultGroups.of(listOf(onPhone, onCard), all, phone, running = false, unreachable = emptySet()))
        assertNull(ResultGroups.of(listOf(onPhone), all, Reach.Subtree, running = false, unreachable = emptySet()))
        assertNull(ResultGroups.of(listOf(onPhone), all, Reach.Folder, running = false, unreachable = emptySet()))
    }

    @Test
    fun `everywhere with a share saved groups, phone first`() {
        val groups = ResultGroups.of(listOf(onNas, onPhone, onCard), all, Reach.Everywhere, false, emptySet())!!
        assertEquals(listOf("This phone", "Workshop NAS", "Spare tablet"), groups.map { it.label })
        assertEquals(listOf(onPhone, onCard), groups[0].nodes)
        assertEquals(listOf(onNas), groups[1].nodes)
    }

    @Test
    fun `the ranking survives inside each group`() {
        // The order within a drive is still right; it just should not mix across drives.
        val second = file("local", "/storage/emulated/0/z.pdf")
        val groups = ResultGroups.of(listOf(second, onNas, onPhone), all, Reach.Everywhere, false, emptySet())!!
        assertEquals(listOf(second, onPhone), groups[0].nodes)
    }

    @Test
    fun `a reach of one share has no phone group`() {
        // A header announcing that the phone found nothing would answer a question nobody asked.
        val groups = ResultGroups.of(listOf(onNas), all, Reach.Drives(setOf(nas.key)), false, emptySet())!!
        assertEquals(listOf("Workshop NAS"), groups.map { it.label })
    }

    @Test
    fun `one local drive is named rather than called this phone`() {
        val drives = listOf(internal, nas)
        val groups = ResultGroups.of(listOf(onPhone), drives, Reach.Everywhere, false, emptySet())!!
        assertEquals("Internal storage", groups[0].label)
    }

    // ── what each header says ──

    @Test
    fun `a share that cannot be reached says so instead of no matches`() {
        // THE rule. Silence from an unreachable share is indistinguishable from "nothing there",
        // which is the one answer a search must never fake.
        val groups = ResultGroups.of(listOf(onPhone), all, Reach.Everywhere, running = false, unreachable = setOf(tablet.key))!!
        val t = groups.first { it.label == "Spare tablet" }
        assertEquals(State.UNREACHABLE, t.state)
        assertEquals("unreachable", ResultGroups.caption(t))
    }

    @Test
    fun `a share still being asked says searching, not no matches`() {
        val groups = ResultGroups.of(listOf(onPhone), all, Reach.Everywhere, running = true, unreachable = emptySet())!!
        assertEquals(State.SEARCHING, groups.first { it.label == "Workshop NAS" }.state)
    }

    @Test
    fun `a share that answered with nothing says no matches`() {
        val groups = ResultGroups.of(listOf(onPhone), all, Reach.Everywhere, running = false, unreachable = emptySet())!!
        val n = groups.first { it.label == "Workshop NAS" }
        assertEquals(State.NONE, n.state)
        assertEquals("no matches", ResultGroups.caption(n))
    }

    @Test
    fun `rows from a share that then failed are still shown, with their count`() {
        // An opted-in share can answer from the index while its live walk fails. The rows are
        // real; they are drawn as still being confirmed.
        val groups = ResultGroups.of(listOf(onNas), all, Reach.Everywhere, running = false, unreachable = setOf(nas.key))!!
        assertEquals(State.FOUND, groups.first { it.label == "Workshop NAS" }.state)
    }

    @Test
    fun `counts are grouped with separators`() {
        val many = (1..2314).map { file("local", "/storage/emulated/0/f$it") }
        val groups = ResultGroups.of(many, all, Reach.Everywhere, false, emptySet())!!
        assertTrue(ResultGroups.caption(groups[0]), ResultGroups.caption(groups[0]).matches(Regex("2.314")))
    }

    @Test
    fun `every hit lands in exactly one group`() {
        val hits = listOf(onPhone, onCard, onNas, file("zip", "/x/y.zip/z"))
        val groups = ResultGroups.of(hits, all, Reach.Everywhere, false, emptySet())!!
        assertEquals(hits.size, groups.sumOf { it.nodes.size })
    }
}
