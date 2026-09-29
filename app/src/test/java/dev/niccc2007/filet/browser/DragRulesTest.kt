package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DragRulesTest {

    @Test
    fun `a fixed setting is obeyed`() {
        assertEquals(DropAction.MOVE, DragRules.resolve(DragBehaviour.CUT))
        assertEquals(DropAction.COPY, DragRules.resolve(DragBehaviour.COPY))
    }

    @Test
    fun `ask always asks`() {
        // A setting that says ask must ask. Quietly applying the same-volume inference would make
        // the setting a lie, and the inference is exactly what the prompt exists to expose.
        assertEquals(DropAction.ASK, DragRules.resolve(DragBehaviour.ASK))
    }

    @Test
    fun `the inference only moves the cursor, never the file`() {
        // Same volume suggests a move, across volumes suggests a copy - and both are only the
        // preselected button.
        assertEquals(DropAction.MOVE, DragRules.suggested(inferredMove = true))
        assertEquals(DropAction.COPY, DragRules.suggested(inferredMove = false))
    }

    @Test
    fun `remembering a choice never stores ask`() {
        // The tick exists to stop being asked; storing ASK would reopen the prompt forever.
        assertEquals(DragBehaviour.CUT, DragRules.remembered(DropAction.MOVE))
        assertEquals(DragBehaviour.COPY, DragRules.remembered(DropAction.COPY))
        assertNotEquals(DragBehaviour.ASK, DragRules.remembered(DropAction.MOVE))
        assertNotEquals(DragBehaviour.ASK, DragRules.remembered(DropAction.COPY))
    }

    @Test
    fun `a remembered choice then resolves to that action`() {
        for (chosen in listOf(DropAction.MOVE, DropAction.COPY)) {
            assertEquals(chosen, DragRules.resolve(DragRules.remembered(chosen)))
        }
    }

    @Test
    fun `every behaviour has words for the settings row`() {
        for (b in DragBehaviour.entries) {
            assertEquals("$b label", true, b.label.isNotBlank())
            assertEquals("$b detail", true, b.detail.isNotBlank())
        }
    }

    @Test
    fun `the prompt has a title for each action`() {
        assertEquals("Move", DragRules.verb(DropAction.MOVE))
        assertEquals("Copy", DragRules.verb(DropAction.COPY))
        assertEquals("Move or copy", DragRules.verb(DropAction.ASK))
    }
}
