package dev.niccc2007.filet.browser

import dev.niccc2007.filet.ops.ClipBatch
import dev.niccc2007.filet.ops.ClipTray
import dev.niccc2007.filet.ops.PendingOp
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A clipboard row's second line. Three cases that read alike and mean different things: where a
 * new batch came from, where a moved one's files are now, and how often a copy has been used.
 */
class ClipWhereTest {

    private val dcim = VPath.of("local", "/dcim")
    private val out = VPath.of("local", "/out")
    private val two = VPath.of("local", "/two")

    private fun tray(op: PendingOp) = ClipTray().add(
        ClipBatch("a", listOf(VPath.of("local", "/dcim/one.jpg")), op, dcim, 0L),
    ).tray

    @Test
    fun `a new batch says where it came from`() {
        assertEquals("from dcim", clipWhere(tray(PendingOp.MOVE).batch("a")!!))
    }

    @Test
    fun `a moved batch says where its files are now`() {
        val b = tray(PendingOp.MOVE).afterPaste("a", out).batch("a")!!
        assertEquals("now in out", clipWhere(b))
    }

    @Test
    fun `a used copy says how often and where last`() {
        val b = tray(PendingOp.COPY).afterPaste("a", out).afterPaste("a", two).batch("a")!!
        assertEquals("pasted 2× · last to two", clipWhere(b))
    }

    @Test
    fun `files from several places say how many`() {
        val b = ClipBatch(
            "a",
            listOf(VPath.of("local", "/x/one.jpg"), VPath.of("local", "/y/two.jpg")),
            PendingOp.COPY,
            null,
            0L,
        )
        assertEquals("from 2 places", clipWhere(b))
    }
}
