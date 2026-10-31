package dev.niccc2007.filet.open

import dev.niccc2007.filet.handlers.HandlerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundViewerTest {

    @Test
    fun `the alias that was chosen picks its viewer`() {
        assertEquals(InboundViewer.VIDEO, InboundViewer.of("dev.niccc2007.filet.open.VideoPlayer"))
        assertEquals(HandlerId.PDF, InboundViewer.of("dev.niccc2007.filet.open.PdfReader")?.handler)
    }

    @Test
    fun `plain Filet follows the routing table`() {
        assertNull(InboundViewer.of("dev.niccc2007.filet.MainActivity"))
        assertNull(InboundViewer.of(null))
    }

    @Test
    fun `a debug build resolves the same, because aliases live in the namespace not the package`() {
        // The debug package is dev.niccc2007.filet.debug; the class name does not carry it.
        assertNull(InboundViewer.of("dev.niccc2007.filet.debug.open.VideoPlayer"))
        assertEquals(InboundViewer.MUSIC, InboundViewer.of("dev.niccc2007.filet.open.MusicPlayer"))
    }

    @Test
    fun `every name reads as Filet's and is unique`() {
        for (v in InboundViewer.entries) assert(v.label.startsWith("Filet ")) { v.label }
        assertEquals(InboundViewer.entries.size, InboundViewer.entries.map { it.label }.toSet().size)
        assertEquals(InboundViewer.entries.size, InboundViewer.entries.map { it.alias }.toSet().size)
    }
}
