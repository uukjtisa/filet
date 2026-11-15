package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModesTest {
    @Test
    fun `HTML and Markdown have a page, everything else starts at its source`() {
        val all = listOf(ViewMode.PAGE, ViewMode.CODE, ViewMode.HEX)
        assertEquals(all, ViewModes.of("html"))
        assertEquals(all, ViewModes.of("HTM"))
        assertEquals(all, ViewModes.of("md"))
        assertEquals(listOf(ViewMode.CODE, ViewMode.HEX), ViewModes.of("kt"))
        assertEquals(listOf(ViewMode.CODE, ViewMode.HEX), ViewModes.of(""))
    }

    @Test
    fun `each mode opens in its own handler`() {
        assertEquals(HandlerId.WEB, ViewModes.handlerFor(ViewMode.PAGE, "xhtml"))
        assertEquals(HandlerId.DOC, ViewModes.handlerFor(ViewMode.PAGE, "markdown"))
        assertEquals(HandlerId.TEXT, ViewModes.handlerFor(ViewMode.PAGE, "txt"))
        assertEquals(HandlerId.TEXT, ViewModes.handlerFor(ViewMode.CODE, "html"))
        assertEquals(HandlerId.HEX, ViewModes.handlerFor(ViewMode.HEX, "md"))
    }
}
