package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Details over several selected items: what they come to and what they share. */
class SelectionSummaryTest {

    private fun p(path: String) = VPath.of("local", path)
    private fun file(path: String, size: Long = 100, mtime: Long = 0) = VNode(p(path), isDir = false, size = size, mtime = mtime)
    private fun dir(path: String) = VNode(p(path), isDir = true, size = -1, mtime = 0)

    @Test
    fun `photos from one folder share their type and their folder`() {
        val s = SelectionSummary.of(
            listOf(
                file("/sdcard/DCIM/a.jpg", 1_000, mtime = 3_000),
                file("/sdcard/DCIM/b.jpg", 2_000, mtime = 1_000),
                file("/sdcard/DCIM/c.png", 3_000, mtime = 2_000),
            ),
        )
        assertEquals("3 files", s.countLine)
        assertEquals(6_000L, s.fileBytes)
        assertEquals("image", s.typeLine)
        assertEquals(p("/sdcard/DCIM"), s.parent)
        assertEquals("a.jpg", s.newest?.name)
        assertEquals("b.jpg", s.oldest?.name)
    }

    @Test
    fun `a mix names its types commonest first, and several folders no one folder`() {
        val s = SelectionSummary.of(
            listOf(
                file("/sdcard/A/x.mp4"),
                file("/sdcard/B/y.mp4"),
                file("/sdcard/B/z.jpg"),
                dir("/sdcard/B/sub"),
            ),
        )
        assertEquals("3 files, 1 folder", s.countLine)
        assertEquals("mixed: video, folder, image", s.typeLine.let { it })
        assertNull(s.parent)
        assertEquals(2, s.parents)
    }

    @Test
    fun `undated items give no newest or oldest, and unknown sizes are left out and counted`() {
        val s = SelectionSummary.of(listOf(file("/a/x.txt", size = -1), file("/a/y.txt", size = 10)))
        assertNull(s.newest)
        assertNull(s.oldest)
        assertEquals(10L, s.fileBytes)
        assertEquals(1, s.unknown)
    }

    // ── the size row ──

    private fun tally(bytes: Long, unreadable: Int = 0) = FolderTally.EMPTY.copy(bytes = bytes, unreadable = unreadable)

    @Test
    fun `while a folder is measured the row says so far`() {
        val line = SelectionSize.of(1_000, 0, listOf(WalkState.Done(tally(1_000), 0), WalkState.Running(tally(48))))
        assertTrue(line.measuring)
        assertEquals("2.0 KB so far…", line.text)
    }

    @Test
    fun `measured, it is the whole, with the bytes`() {
        val line = SelectionSize.of(1_024, 0, listOf(WalkState.Done(tally(1_024), 0)))
        assertEquals("2.0 KB  (2,048 bytes)", line.text)
    }

    @Test
    fun `a folder not measured or unreadable makes it a floor`() {
        assertEquals(
            "at least 10 B  (10 bytes) · 1 folder not measured",
            SelectionSize.of(10, 0, listOf(WalkState.OnRequest)).text,
        )
        assertTrue(SelectionSize.of(10, 0, listOf(WalkState.Done(tally(0, unreadable = 1), 0))).text.startsWith("at least "))
        assertTrue(SelectionSize.of(10, 1, emptyList()).text.startsWith("at least "))
    }
}
