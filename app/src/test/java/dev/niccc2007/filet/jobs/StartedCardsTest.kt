package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which jobs raise a card, and what the card says. */
class StartedCardsTest {

    private fun job(id: Long, title: String = "Copying 3 item(s)", kind: JobKind = JobKind.COPY, state: JobState = JobState.RUNNING) =
        Job(id, title, state = state, kind = kind)

    @Test
    fun `only jobs started after the newest one seen raise a card, oldest first`() {
        val jobs = listOf(job(7), job(5), job(6), job(3))
        assertEquals(listOf(6L, 7L), StartedCards.newSince(5, jobs).map { it.id })
    }

    @Test
    fun `a job that already finished still gets its card`() {
        // A delete of one file is over in milliseconds; the card is still the acknowledgement.
        val done = job(9, kind = JobKind.DELETE, state = JobState.DONE)
        assertEquals(listOf(9L), StartedCards.newSince(8, listOf(done)).map { it.id })
    }

    @Test
    fun `history read back from disk raises nothing`() {
        val history = listOf(job(1, state = JobState.DONE), job(2, state = JobState.FAILED))
        assertTrue(StartedCards.newSince(StartedCards.newest(history), history).isEmpty())
        assertEquals(0L, StartedCards.newest(emptyList()))
    }

    @Test
    fun `a copy says how many and where to`() {
        val j = Job(1, "Copying 3 item(s)", subtitle = "Camera", kind = JobKind.COPY, to = "local:///storage/emulated/0/DCIM/Camera")
        assertEquals("Copying 3 items" to "to Camera", StartedCards.words(j))
    }

    @Test
    fun `one item is one item`() {
        assertEquals("Moving 1 item", StartedCards.words(job(1, "Moving 1 item(s)", JobKind.MOVE)).first)
        assertEquals("Moving 11 items", StartedCards.words(job(1, "Moving 11 item(s)", JobKind.MOVE)).first)
    }

    @Test
    fun `a delete says where from`() {
        val j = Job(1, "Deleting 4 item(s)", kind = JobKind.DELETE, from = "local:///storage/emulated/0/Download")
        assertEquals("in Download", StartedCards.words(j).second)
    }

    @Test
    fun `a job that names no place is a card of one line`() {
        assertNull(StartedCards.words(job(1, "Indexing", JobKind.INDEX)).second)
    }
}
