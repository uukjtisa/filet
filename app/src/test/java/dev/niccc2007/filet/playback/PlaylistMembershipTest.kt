package dev.niccc2007.filet.playback

import dev.niccc2007.filet.playback.PlaylistMembership.Act
import dev.niccc2007.filet.playback.PlaylistMembership.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistMembershipTest {

    @Test fun `the three states`() {
        assertEquals(State.NONE, PlaylistMembership.state(0, 5))
        assertEquals(State.SOME, PlaylistMembership.state(1, 5))
        assertEquals(State.SOME, PlaylistMembership.state(4, 5))
        assertEquals(State.ALL, PlaylistMembership.state(5, 5))
        assertEquals(State.ALL, PlaylistMembership.state(1, 1))
    }

    @Test fun `an empty selection is not somehow all of itself`() {
        // Guards a divide-by-nothing that would tick every box in the dialogue.
        assertEquals(State.NONE, PlaylistMembership.state(0, 0))
        assertEquals(State.NONE, PlaylistMembership.state(3, 0))
    }

    @Test fun `only a full box removes`() {
        assertEquals(Act.ADD, PlaylistMembership.act(0, 5))
        assertEquals(Act.ADD, PlaylistMembership.act(3, 5))
        assertEquals(Act.REMOVE, PlaylistMembership.act(5, 5))
    }

    @Test fun `a half-ticked box fills up rather than emptying`() {
        // The decision this file exists for. Clearing a partial selection would discard filing
        // somebody had already done, and that is exactly the state they cannot remember being in.
        assertEquals(Act.ADD, PlaylistMembership.act(State.SOME))
    }

    @Test fun `the dash is the partial state and nothing else`() {
        assertEquals(false, State.NONE.ticked)
        assertNull(State.SOME.ticked)
        assertEquals(true, State.ALL.ticked)
    }

    @Test fun `the caption always agrees with the box`() {
        assertEquals("In this playlist  ·  tap to take it out", PlaylistMembership.detail(1, 1, 9))
        assertEquals("All 4 are in here  ·  tap to take them out", PlaylistMembership.detail(4, 4, 9))
        assertEquals("3 to add  ·  1 already in here", PlaylistMembership.detail(1, 4, 9))
        assertEquals("Empty", PlaylistMembership.detail(0, 4, 0))
        assertEquals("9 tracks", PlaylistMembership.detail(0, 4, 9))
        assertEquals("1 track", PlaylistMembership.detail(0, 4, 1))
    }
}
