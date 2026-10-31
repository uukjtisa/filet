package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which row of Open with says "Default". See [openWithDetail]. */
class OpenWithDetailTest {

    @Test
    fun `with no override the built-in viewer is the default`() {
        assertEquals("Default", openWithDetail(HandlerId.DOC, HandlerId.DOC, HandlerId.DOC, null))
        assertEquals("Pick an app", openWithDetail(HandlerId.EXTERNAL, HandlerId.DOC, HandlerId.DOC, null))
    }

    @Test
    fun `a type sent to an app marks the app, not the built-in reader`() {
        // The case that shipped wrong: .xlsx routed to an office app in Default openers.
        assertNull(openWithDetail(HandlerId.DOC, HandlerId.DOC, HandlerId.EXTERNAL, "Excel"))
        assertEquals("Default  ·  Excel", openWithDetail(HandlerId.EXTERNAL, HandlerId.DOC, HandlerId.EXTERNAL, "Excel"))
    }

    @Test
    fun `a type sent to another built-in viewer marks that viewer`() {
        assertEquals("Default", openWithDetail(HandlerId.HEX, HandlerId.DOC, HandlerId.HEX, null))
        assertNull(openWithDetail(HandlerId.DOC, HandlerId.DOC, HandlerId.HEX, null))
    }

    @Test
    fun `an app that is remembered but not the default is named without the word`() {
        assertEquals("Excel", openWithDetail(HandlerId.EXTERNAL, HandlerId.DOC, HandlerId.DOC, "Excel"))
    }

    @Test
    fun `a row that is neither the default nor an app says nothing`() {
        assertNull(openWithDetail(HandlerId.TEXT, HandlerId.DOC, HandlerId.DOC, null))
    }
}
