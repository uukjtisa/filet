package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which job kinds announce themselves, and how the Activity page's settings are kept. */
class NoticesTest {

    @Test
    fun `every kind sits in exactly one group, and every group has a kind`() {
        val placed = NoticeGroup.entries.flatMap { Notices.kindsIn(it) }
        assertEquals(JobKind.entries.toSet(), placed.toSet())
        assertEquals(JobKind.entries.size, placed.size)
        for (g in NoticeGroup.entries) assertTrue(g.name, Notices.kindsIn(g).isNotEmpty())
    }

    @Test
    fun `the kinds that have always ended in silence have no finished message`() {
        val silent = JobKind.entries.filterNot { it.notice().ends }.toSet()
        assertEquals(
            setOf(JobKind.INDEX, JobKind.MEASURE, JobKind.BEAT, JobKind.FETCH, JobKind.DOWNLOAD, JobKind.OTHER),
            silent,
        )
    }

    @Test
    fun `by default everything shows as it always has`() {
        val d = NoticePrefs()
        for (k in JobKind.entries) {
            assertTrue(k.name, Notices.showsCard(k, d))
            assertEquals(k.name, k.notice().ends, Notices.showsEnd(k, failed = false, prefs = d))
        }
        assertEquals(StartedCards.LIFE_MS, d.lifeMs)
        assertEquals(StartedCards.MAX, d.stack)
        assertEquals(CardPlace.TOP_END, d.place)
    }

    @Test
    fun `switching a kind off hides its card or its message, and no other kind's`() {
        val p = NoticePrefs(startsOff = setOf(JobKind.COPY), endsOff = setOf(JobKind.DELETE))
        assertFalse(Notices.showsCard(JobKind.COPY, p))
        assertTrue(Notices.showsCard(JobKind.MOVE, p))
        assertTrue(Notices.showsEnd(JobKind.COPY, failed = false, prefs = p))
        assertFalse(Notices.showsEnd(JobKind.DELETE, failed = false, prefs = p))
        assertTrue(Notices.showsEnd(JobKind.MOVE, failed = false, prefs = p))
    }

    @Test
    fun `a failure always says so, whatever is switched off`() {
        val everythingOff = NoticePrefs(startsOff = JobKind.entries.toSet(), endsOff = JobKind.entries.toSet())
        for (k in JobKind.entries) assertTrue(k.name, Notices.showsEnd(k, failed = true, prefs = everythingOff))
    }

    @Test
    fun `the settings keep only what is off, and read back as they were`() {
        val p = NoticePrefs(
            startsOff = setOf(JobKind.INDEX, JobKind.COPY),
            endsOff = setOf(JobKind.SAVE),
            lifeMs = 5_000L,
            place = CardPlace.BOTTOM_START,
            stack = 1,
        )
        assertEquals(p, NoticePrefs.decode(p.encode()))
        assertFalse(NoticePrefs().encode().contains("COPY"))
    }

    @Test
    fun `missing, unreadable or retired values fall back one at a time`() {
        assertEquals(NoticePrefs(), NoticePrefs.decode(null))
        assertEquals(NoticePrefs(), NoticePrefs.decode("not json"))
        val p = NoticePrefs.decode("""{"startsOff":["COPY","RETIRED_KIND"],"place":"MIDDLE","life":4000,"stack":9}""")
        assertEquals(setOf(JobKind.COPY), p.startsOff)
        assertEquals(CardPlace.TOP_END, p.place)
        // Snapped to the nearest choice offered.
        assertTrue(p.lifeMs in Notices.LIFE_CHOICES)
        assertEquals(5, p.stack)
    }

    @Test
    fun `a kind with no finished message cannot have one switched off`() {
        val p = NoticePrefs.decode("""{"endsOff":["INDEX","COPY"]}""")
        assertEquals(setOf(JobKind.COPY), p.endsOff)
    }
}
