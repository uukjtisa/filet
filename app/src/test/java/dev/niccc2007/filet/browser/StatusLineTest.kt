package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the search says while it is still working. SEARCH.md §14.8.
 *
 * The defect: one row, a spinner that stopped, then more rows seconds later with nothing on
 * screen in between to say anything was still going.
 */
class StatusLineTest {

    private val phoneRoot = VPath.of("local", "/storage/emulated/0")
    private val nasRoot = VPath.of("smb", "/n1a2b3c")
    private val drives = listOf(
        ReachDrive(VolumeKey(phoneRoot), "Internal storage", remote = false, system = false),
        ReachDrive(VolumeKey(nasRoot), "C drive", remote = true, system = false),
    )

    @Test
    fun `it names the checker and every share being read`() {
        val line = SearchReach.statusLine(
            origin = "Index, and reading the folders",
            running = true,
            checking = 3,
            walked = mapOf(nasRoot to 1204, phoneRoot to 88),
            drives = drives,
        )
        assertTrue(line, line.startsWith("Index, and reading the folders"))
        assertTrue(line, line.contains("checking 3"))
        assertTrue(line, line.contains("reading C drive, 1") && line.contains("204 folders"))
    }

    @Test
    fun `a local walker is not named`() {
        // It finishes before anybody could read the line; naming it only makes the line flicker.
        val line = SearchReach.statusLine("Index", running = true, checking = 0, walked = mapOf(phoneRoot to 88), drives = drives)
        assertFalse(line, line.contains("Internal storage"))
    }

    @Test
    fun `once the sources are done, no share is still being read`() {
        val line = SearchReach.statusLine("Index", running = false, checking = 0, walked = mapOf(nasRoot to 1204), drives = drives)
        assertEquals("Index", line)
    }

    @Test
    fun `nothing working and no origin is an empty line, not a stale one`() {
        assertEquals("", SearchReach.statusLine("", running = false, checking = 0, walked = emptyMap(), drives = drives))
    }

    @Test
    fun `a share header counts the folders its walker has read`() {
        val hit = VNode(VPath.of("local", "/storage/emulated/0/a.zip"), false, 1, 0)
        val groups = ResultGroups.of(
            hits = listOf(hit),
            drives = drives,
            reach = Reach.Everywhere,
            running = true,
            unreachable = emptySet(),
            walked = mapOf(VolumeKey(nasRoot) to 1204),
        )!!
        val nas = groups.first { it.label == "C drive" }
        val caption = ResultGroups.caption(nas)
        assertTrue(caption, caption.startsWith("reading, 1") && caption.endsWith("204 folders"))
    }

    @Test
    fun `before its first folder a share just says searching`() {
        val groups = ResultGroups.of(emptyList(), drives, Reach.Everywhere, running = true, unreachable = emptySet())!!
        assertEquals("searching…", ResultGroups.caption(groups.first { it.label == "C drive" }))
    }
}
