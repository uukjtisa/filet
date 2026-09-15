package dev.niccc2007.filet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stale folder, and the syscall storm that must not replace it.
 *
 * Every test here is one of the two failure modes. A rule that always returns true fixes his
 * report and re-reads a directory every time a tab is tapped; a rule that always returns false
 * is what shipped. Both are covered, so neither can pass by accident.
 */
class FolderFreshnessTest {

    private fun ask(
        isFolder: Boolean = true,
        hasPath: Boolean = true,
        loading: Boolean = false,
        visible: Boolean = true,
        listedAt: Int = 4,
        world: Int = 4,
    ) = FolderFreshness.shouldRelist(isFolder, hasPath, loading, visible, listedAt, world)

    // ── his report ──

    @Test
    fun `a visible folder that is behind the world is re-read`() {
        // He moved a file out of a folder that another tab was showing. That tab is behind.
        assertTrue(ask(listedAt = 4, world = 5))
    }

    @Test
    fun `a tab that was hidden when the move happened is re-read as soon as it is shown`() {
        assertFalse("nothing is read while it is off screen", ask(visible = false, listedAt = 4, world = 9))
        assertTrue("and it is read the moment it comes back", ask(visible = true, listedAt = 4, world = 9))
    }

    @Test
    fun `a pane that has never listed anything reads`() {
        assertTrue(ask(listedAt = FolderFreshness.NEVER, world = 0))
    }

    @Test
    fun `revision zero is a real revision and not treated as never`() {
        // NEVER is -1 precisely so this case works: a pane that listed at the very first
        // revision is up to date, not unread.
        assertFalse(ask(listedAt = 0, world = 0))
    }

    // ── the storm this must not become ──

    @Test
    fun `an up-to-date folder is left alone`() {
        assertFalse("this is the whole reason for the counter", ask(listedAt = 7, world = 7))
    }

    @Test
    fun `a read already in flight is not raced with a second one`() {
        assertFalse(ask(loading = true, listedAt = 4, world = 5))
    }

    @Test
    fun `panes that are not folders are never decided here`() {
        // Home, Nearby, Scripts and Settings answer to refreshPlan. Re-listing Nearby on a tab
        // tap would restart peer discovery every time the strip was touched.
        assertFalse(ask(isFolder = false, listedAt = 1, world = 99))
    }

    @Test
    fun `a folder pane with no path has nothing to read`() {
        assertFalse(ask(hasPath = false, listedAt = 1, world = 99))
    }

    // ── the control ──

    @Test
    fun `the rule can say yes and no on the same inputs bar one`() {
        // A positive control against a rule that is accidentally constant. If either of these
        // stops holding, every assertion above is passing for the wrong reason.
        assertTrue(ask(listedAt = 1, world = 2))
        assertFalse(ask(listedAt = 2, world = 2))
    }
}
