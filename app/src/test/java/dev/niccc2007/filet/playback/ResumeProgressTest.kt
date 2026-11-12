package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeProgressTest {

    private fun point(at: Long, length: Long) = ResumeStore.Point("local:///x.mp4", "x.mp4", true, at, length, 0L)

    private val partWay = point(30_000, 100_000)
    private val nearlyDone = point(98_000, 100_000)
    private val finished = point(99_500, 100_000)
    private val noLength = point(5_000, 0)
    private val list = listOf(partWay, nearlyDone, finished, noLength)

    @Test fun `unfinished keeps everything short of the finished line, unknown lengths included`() {
        assertEquals(listOf(partWay, nearlyDone, noLength), list.filter(ResumeProgress.UNFINISHED::accepts))
    }

    @Test fun `finished is the same line the tag draws, so a tagged row is never filtered as unfinished`() {
        assertEquals(listOf(finished), list.filter(ResumeProgress.FINISHED::accepts))
        for (p in list) assertEquals(finishedTag(p) != null, ResumeProgress.FINISHED.accepts(p))
    }

    @Test fun `both keeps every row`() {
        assertEquals(list, list.filter(ResumeProgress.BOTH::accepts))
    }
}
