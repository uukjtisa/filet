package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace must not be slower than the thing it is tracing.
 *
 * Reported as the start-sharing button feeling frozen with the spinner barely visible. The
 * cause was the instrument: every traced line appended to a file, read the whole file back to
 * count it, and rewrote the file when it was over the limit - on whatever thread called it,
 * and the caller was a button.
 *
 * The read-back is the part that is testable without a device, because whether a trim happens
 * is arithmetic. Getting it wrong is quiet in both directions: never trim and the file grows
 * without bound, trim the moment the limit is passed and every subsequent line pays for a full
 * rewrite for ever - which is the behaviour being replaced.
 */
class MainThreadTest {

    private val LIMIT = 400
    private val SLACK = 80

    @Test
    fun `a file under the limit is never rewritten`() {
        for (n in 0..LIMIT) {
            assertFalse("$n lines should not trim", ShareTrace.shouldTrim(n, LIMIT, SLACK))
        }
    }

    @Test
    fun `passing the limit alone does not trigger a rewrite`() {
        // This is the fault. Trimming at exactly the limit means line 401 rewrites, line 402
        // rewrites, and so on for the life of the process.
        assertFalse(ShareTrace.shouldTrim(LIMIT + 1, LIMIT, SLACK))
        assertFalse(ShareTrace.shouldTrim(LIMIT + SLACK, LIMIT, SLACK))
    }

    @Test
    fun `the file is rewritten once it is far enough over`() {
        assertTrue(ShareTrace.shouldTrim(LIMIT + SLACK + 1, LIMIT, SLACK))
        assertTrue(ShareTrace.shouldTrim(LIMIT * 4, LIMIT, SLACK))
    }

    @Test
    fun `a rewrite happens at most once every slack lines`() {
        // The property that matters, stated as a count rather than as a threshold: writing
        // a thousand lines must not cause a thousand rewrites.
        var count = 0
        var rewrites = 0
        repeat(1_000) {
            count++
            if (ShareTrace.shouldTrim(count, LIMIT, SLACK)) {
                rewrites++
                count = ShareTrace.keepCount(LIMIT)
            }
        }
        assertTrue("$rewrites rewrites for 1000 lines is too many", rewrites <= 1_000 / SLACK + 1)
        assertTrue("it never trimmed at all", rewrites > 0)
    }

    @Test
    fun `a trim leaves exactly the limit behind`() {
        assertEquals(LIMIT, ShareTrace.keepCount(LIMIT))
    }

    @Test
    fun `the file is bounded no matter how much is written`() {
        // Over a long session the count must stay inside the limit plus the slack, which is
        // the actual promise: the file cannot grow without bound.
        var count = 0
        var high = 0
        repeat(10_000) {
            count++
            if (ShareTrace.shouldTrim(count, LIMIT, SLACK)) count = ShareTrace.keepCount(LIMIT)
            if (count > high) high = count
        }
        assertTrue("grew to $high", high <= LIMIT + SLACK + 1)
    }

    @Test
    fun `the slack is a real interval and not a disguised zero`() {
        // A slack of 0 is the old behaviour wearing the new shape, and it would pass every
        // test above except this one.
        assertTrue(SLACK > 0)
        assertFalse(ShareTrace.shouldTrim(LIMIT + 1, LIMIT, SLACK))
    }
}
