package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DragRulesTest {

    @Test
    fun `the inference only moves the cursor, never the file`() {
        // Same volume suggests a move, across volumes suggests a copy - and both are only the
        // preselected button.
        assertEquals(DropAction.MOVE, DragRules.suggested(inferredMove = true))
        assertEquals(DropAction.COPY, DragRules.suggested(inferredMove = false))
    }

    @Test
    fun `the prompt has a title for each action`() {
        assertEquals("Move", DragRules.verb(DropAction.MOVE))
        assertEquals("Copy", DragRules.verb(DropAction.COPY))
        assertEquals("Move or copy", DragRules.verb(DropAction.ASK))
    }
}
