package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an Activity row offers, and where a swiped one settles. */
class JobActionsTest {

    private fun job(
        kind: JobKind,
        state: JobState = JobState.DONE,
        opened: String = "local:///storage/emulated/0/Download/x",
        pausable: Boolean = false,
        paused: Boolean = false,
        cancellable: Boolean = false,
        canRunAgain: Boolean = false,
    ) = Job(
        id = 1L, title = "t", state = state, opened = opened, kind = kind,
        pausable = pausable, paused = paused, cancellable = cancellable, canRunAgain = canRunAgain,
    )

    // ── what a finished job offers ──

    @Test
    fun `a finished copy or move offers its folder, and no longer jumps on a tap`() {
        for (k in listOf(JobKind.COPY, JobKind.MOVE, JobKind.EXTRACT)) {
            assertEquals("$k", listOf(JobAction.GO_TO), JobActions.of(job(k)))
        }
    }

    @Test
    fun `a file made offers opening it and seeing it in its folder`() {
        for (k in listOf(JobKind.COMPRESS, JobKind.SAVE, JobKind.DOWNLOAD, JobKind.FETCH)) {
            assertEquals("$k", listOf(JobAction.OPEN, JobAction.SHOW_IN_FOLDER), JobActions.of(job(k)))
        }
    }

    @Test
    fun `indexing and scripts have nothing to go to yet`() {
        for (k in listOf(JobKind.INDEX, JobKind.SCRIPT)) {
            assertEquals("$k", emptyList<JobAction>(), JobActions.of(job(k)))
        }
    }

    @Test
    fun `no result recorded offers no way to it`() {
        assertEquals(emptyList<JobAction>(), JobActions.of(job(JobKind.COPY, opened = "")))
    }

    @Test
    fun `a failed copy offers its folder and a retry, a done one a run again`() {
        assertEquals(
            listOf(JobAction.GO_TO, JobAction.RETRY),
            JobActions.of(job(JobKind.COPY, state = JobState.FAILED, canRunAgain = true)),
        )
        assertEquals(
            listOf(JobAction.RETRY),
            JobActions.of(job(JobKind.INDEX, state = JobState.CANCELLED, canRunAgain = true)),
        )
        assertEquals(
            listOf(JobAction.RUN_AGAIN),
            JobActions.of(job(JobKind.SCRIPT, canRunAgain = true)),
        )
    }

    // ── while it runs ──

    @Test
    fun `a running job offers pause or resume and stop, and not its result yet`() {
        assertEquals(
            listOf(JobAction.PAUSE, JobAction.STOP),
            JobActions.of(job(JobKind.COPY, state = JobState.RUNNING, pausable = true, cancellable = true)),
        )
        assertEquals(
            listOf(JobAction.RESUME),
            JobActions.of(job(JobKind.INDEX, state = JobState.RUNNING, pausable = true, paused = true)),
        )
        assertEquals(emptyList<JobAction>(), JobActions.of(job(JobKind.DOWNLOAD, state = JobState.WAITING)))
    }

    @Test
    fun `every kind has decided what its result is`() {
        // The `when` is exhaustive in the code; here, that each answer is one of the four.
        for (k in JobKind.entries) assertTrue("$k", JobActions.resultOf(k) in JobActions.Result.entries)
    }

    // ── the swipe ──

    @Test
    fun `a short swipe springs back`() {
        assertEquals(0f, SwipeAway.settle(100f, 1000f, 0f))
        assertEquals(0f, SwipeAway.settle(-399f, 1000f, 500f))
    }

    @Test
    fun `past 40 percent it goes, to the side it was heading`() {
        assertEquals(1000f, SwipeAway.settle(401f, 1000f, 0f))
        assertEquals(-1000f, SwipeAway.settle(-401f, 1000f, 0f))
    }

    @Test
    fun `a fling goes however short, the way it was flung`() {
        assertEquals(-1000f, SwipeAway.settle(50f, 1000f, -3_000f))
        assertEquals(1000f, SwipeAway.settle(-50f, 1000f, 3_000f))
    }
}
