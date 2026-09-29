package dev.niccc2007.filet.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup flash in the tracked-folders feed.
 *
 * Reported as stale rows appearing for about half a second and then being replaced, after an
 * earlier fix that "only lessened the delay". Both halves of that are accurate. The earlier fix
 * blocked partial answers once the screen had content, which is correct and is why a REFRESH no
 * longer flickers. It deliberately left the cold start open so the card would not sit blank - and
 * on a cold start `publish` is called from inside each folder's own coroutine, so the first folder
 * that happens to finish paints.
 *
 * Its files are real. They are also a subset, and a subset of "the newest eight across all
 * tracked folders" is a different list from the answer - so what lands first is whichever folder
 * won a race, and the complete publish then corrects it.
 *
 * So the test is not about a delay. It is about whether a partial answer can be shown to be
 * FINAL for the rows it would display, using each unread folder's own mtime as an upper bound on
 * when anything could have arrived inside it.
 */
class FeedStartupTest {

    private val shown = 8

    // ── the proof ──

    @Test
    fun `a full window with every unread folder older than its oldest row is final`() {
        assertTrue(
            FeedPublish.partialIsFinal(
                shownCount = shown,
                shownLimit = shown,
                oldestShownAt = 5_000L,
                unreadFolderMtimes = listOf(4_000L, 1_000L, 4_999L),
            ),
        )
    }

    @Test
    fun `one unread folder newer than the oldest row is enough to refuse`() {
        assertFalse(
            FeedPublish.partialIsFinal(
                shownCount = shown,
                shownLimit = shown,
                oldestShownAt = 5_000L,
                unreadFolderMtimes = listOf(1_000L, 5_001L),
            ),
        )
    }

    @Test
    fun `a folder exactly as old as the oldest row does not refuse`() {
        // Equal cannot displace: the row is already at the boundary and a tie does not reorder.
        assertTrue(
            FeedPublish.partialIsFinal(shown, shown, 5_000L, listOf(5_000L)),
        )
    }

    @Test
    fun `a half-full window is never final however old the unread folders are`() {
        // The case that makes this more than a comparison. With four rows and room for eight,
        // anything at all in an unread folder lands inside the window - there is space below the
        // oldest row, so its timestamp is irrelevant.
        assertFalse(
            FeedPublish.partialIsFinal(
                shownCount = 4,
                shownLimit = shown,
                oldestShownAt = 5_000L,
                unreadFolderMtimes = listOf(1L),
            ),
        )
    }

    @Test
    fun `nothing unread means the answer is complete by definition`() {
        assertTrue(FeedPublish.partialIsFinal(shown, shown, 5_000L, emptyList()))
    }

    @Test
    fun `an unstattable folder must not be treated as old`() {
        // HomeFeed substitutes Long.MAX_VALUE for a folder it could not stat. An unknown bound
        // has to refuse, or a partial would claim to be final on the strength of a folder it
        // knows nothing about.
        assertFalse(
            FeedPublish.partialIsFinal(shown, shown, 5_000L, listOf(Long.MAX_VALUE)),
        )
    }

    // ── what reaches the screen ──

    @Test
    fun `a proven partial paints immediately on a cold start`() {
        assertTrue(FeedPublish.showPartial(screenIsEmpty = true, elapsedMs = 0L, provenFinal = true))
    }

    @Test
    fun `an unproven partial does not paint inside the grace window`() {
        // This is the reported flash. Same inputs as before the fix, and now it is refused.
        assertFalse(
            FeedPublish.showPartial(screenIsEmpty = true, elapsedMs = 20L, provenFinal = false),
        )
    }

    @Test
    fun `an unproven partial paints once the pass has run long enough`() {
        // Slow storage. An incomplete list beats an empty card once the wait is long enough to
        // look broken.
        assertTrue(
            FeedPublish.showPartial(
                screenIsEmpty = true,
                elapsedMs = FeedPublish.PARTIAL_GRACE_MS,
                provenFinal = false,
            ),
        )
    }

    @Test
    fun `no partial ever paints over content however proven`() {
        // The half the earlier fix got right, held in place: a refresh keeps the answer it has.
        assertFalse(FeedPublish.showPartial(screenIsEmpty = false, elapsedMs = 9_999L, provenFinal = true))
    }

    @Test
    fun `complete and splice are unaffected by any of this`() {
        // A splice is one file the kernel has just named, not a subset of anything, and blocking
        // it was the regression the previous round had to undo. It stays unconditional.
        for (empty in listOf(true, false)) {
            assertTrue(FeedPublish.publishable(PublishKind.COMPLETE, empty, 0L, false))
            assertTrue(FeedPublish.publishable(PublishKind.SPLICE, empty, 0L, false))
        }
    }

    @Test
    fun `the old policy is what produced the flash`() {
        // The negative control, stated as the previous rule: "empty screen means draw it". On a
        // cold start that is true for the first folder to finish, whatever it holds.
        val oldPolicy = { screenIsEmpty: Boolean -> screenIsEmpty }
        assertTrue("the old rule painted the first partial", oldPolicy(true))
        assertFalse(
            "the new one refuses it",
            FeedPublish.showPartial(screenIsEmpty = true, elapsedMs = 20L, provenFinal = false),
        )
    }
}
