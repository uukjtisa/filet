package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.FakeIndex
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Measuring a folder for real: what a walk is allowed to claim, and what the queue behind it
 * promises.
 *
 * These run against a real temporary tree rather than a fake VFS, because the claim being
 * tested is precisely that the figure came from the filesystem. A fake that reports what the
 * test told it to report would pass whether or not anything was walked, which is the shape of
 * the defect this replaced: a figure that had never touched the filesystem, printed as though
 * it had.
 *
 * The queue is the other half. A row asks for its folder to be measured, a screenful is forty
 * rows, and forty concurrent recursive walks is the cost that made the row total a lookup in
 * the first place - so the request is a queue entry, one walk runs at a time, and the two
 * things a caller is owed are that waiting its turn still gets it measured and that the backlog
 * cannot grow without bound.
 */
class FolderWalkQueueTest {

    private lateinit var tmp: File
    private lateinit var scope: CoroutineScope

    /** Every folder a walk has listed, in order, so a test can see what was and was not read. */
    private val listed = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Set to a folder whose listing blocks until the deferred completes. */
    private var hold: Pair<String, CompletableDeferred<Unit>>? = null

    /**
     * A provider that records what it lists and can be told it is remote.
     *
     * Delegation rather than a hand-written fake: the walk's correctness depends on real
     * directory listings, so what is wanted is the real local provider with a counter on the
     * one method under test.
     */
    private inner class Watched(
        private val inner: FileSystemProvider,
        override val remote: Boolean = false,
    ) : FileSystemProvider by inner {
        override suspend fun list(path: VPath): List<VNode> {
            listed += path.toString()
            hold?.let { (blocked, gate) -> if (path.toString() == blocked) gate.await() }
            return inner.list(path)
        }
    }

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-measure-" + System.nanoTime())
        tmp.mkdirs()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun vpath(f: File, scheme: String = "local") =
        VPath.of(scheme, f.absolutePath.replace(File.separatorChar, '/'))

    /** A folder holding [files] files of ten bytes each, so its total is arithmetic. */
    private fun dir(name: String, files: Int = 0): File =
        File(tmp, name).apply {
            mkdirs()
            repeat(files) { File(this, "f$it.bin").writeText("0123456789") }
        }

    private fun measure(
        index: FakeIndex = FakeIndex(rows = null),
        providers: List<FileSystemProvider> = listOf(Watched(LocalProvider(listOf(tmp)))),
    ) = FolderMeasure(Vfs(providers), index, JobLedger(), scope)

    /** Waits for something a background walk will do, or fails the test rather than hanging. */
    private suspend fun waitFor(what: String, cond: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            if (cond()) return
            delay(5)
        }
        fail("timed out waiting for $what")
    }

    // ── what a walk may claim ─────────────────────────────────────────────────────────────

    @Test fun `a finished walk is what makes a figure final`() = runBlocking {
        // The other half of the floor rule: Settled has to be reachable, or every folder in the
        // app would carry a + for ever and the mark would stop meaning anything.
        val root = dir("tree", files = 3)
        File(root, "inner").mkdirs()
        File(root, "inner/deep.bin").writeText("0123456789")
        val m = measure()
        val p = vpath(root)

        assertEquals("nothing has measured it yet", FolderTotal.Unknown, m.total(p))
        m.measure(p)
        waitFor("the walk to settle") { m.total(p) is FolderTotal.Settled }

        val done = m.total(p)
        assertEquals(FolderTotal.Settled(40L), done)
        assertFalse("a walked figure carries no plus", done.isFloor())
        assertFalse("and nothing is still turning on the row", done.busy())
    }

    @Test fun `a walk replaces the index's floor`() = runBlocking {
        // What the row does over its lifetime: a floor from the crawl immediately, then the real
        // figure when the walk lands. The floor here is deliberately wrong by a long way, which
        // is what it is on a real phone - the crawl had reached five files of the twelve.
        val root = dir("partly-indexed", files = 12)
        val m = measure(FakeIndex(rows = 5))
        val p = vpath(root)

        assertEquals(FolderTotal.AtLeast(500L), m.total(p))
        assertTrue(m.total(p).wantsWalk())
        m.measure(p)
        waitFor("the walk to correct the floor") { m.total(p) is FolderTotal.Settled }
        assertEquals(FolderTotal.Settled(120L), m.total(p))
        assertFalse("a settled figure is not asked for again", m.total(p).wantsWalk())
    }

    // ── the queue ─────────────────────────────────────────────────────────────────────────

    @Test fun `a folder whose row has scrolled away is still measured`() = runBlocking {
        // Dropping the request for a row that left the screen would mean a list that never
        // finishes measuring itself: with one walk at a time, most of a screenful is always
        // waiting, and a fling would cancel exactly the folders it just asked about.
        val dirs = (1..12).map { dir("d$it", files = it) }
        val m = measure()
        for (d in dirs) m.measure(vpath(d))

        waitFor("all twelve to settle") { dirs.all { m.total(vpath(it)) is FolderTotal.Settled } }
        for ((i, d) in dirs.withIndex()) {
            assertEquals("d${i + 1}", FolderTotal.Settled((i + 1) * 10L), m.total(vpath(d)))
        }
    }

    @Test fun `asking twenty times for the same folder walks it once`() = runBlocking {
        // Twenty asks is one row appearing twenty times during a scroll, which is the normal
        // case rather than a pathological one.
        val root = dir("asked-often", files = 2)
        val m = measure()
        val p = vpath(root)
        repeat(20) { m.measure(p) }

        waitFor("the one walk to settle") { m.total(p) is FolderTotal.Settled }
        delay(50)
        assertEquals(listOf(p.toString()), listed.toList())
    }

    @Test fun `the backlog is bounded, and what it admits is what gets measured`() = runBlocking {
        // A fling down a folder of hundreds of subfolders must not build a queue that outlives
        // any interest in it. The overflow is dropped rather than queued, which is safe only
        // because a row still on screen asks again - so what this pins is the cap itself and
        // that nothing below the cap is lost.
        val blocker = dir("blocker")
        val gate = CompletableDeferred<Unit>()
        val extras = (1..60).map { dir("q$it") }
        val m = measure()

        hold = vpath(blocker).toString() to gate
        m.measure(vpath(blocker))
        // Queued behind a walk that cannot finish, so admission is decided with the queue full.
        for (e in extras) m.measure(vpath(e))
        gate.complete(Unit)

        val admitted = extras.take(FolderMeasure.QUEUE_MAX - 1)
        val dropped = extras.drop(FolderMeasure.QUEUE_MAX - 1)
        waitFor("the admitted folders to drain") {
            admitted.all { m.total(vpath(it)) is FolderTotal.Settled }
        }
        delay(50)
        for (d in dropped) {
            assertEquals("q${extras.indexOf(d) + 1} was over the cap", FolderTotal.Unknown, m.total(vpath(d)))
        }
        assertEquals(FolderMeasure.QUEUE_MAX, listed.toList().distinct().size)
    }

    @Test fun `a remote folder is never walked unasked`() = runBlocking {
        // A network share costs a round trip per directory, so a list of them would be minutes
        // of somebody's data spent on figures nobody asked for. The details sheet's Measure
        // button is the way to get one, and it says so.
        val share = dir("share", files = 4)
        val local = dir("local-sentinel", files = 1)
        val m = measure(
            providers = listOf(
                Watched(LocalProvider(listOf(tmp))),
                Watched(LocalProvider(listOf(tmp), scheme = "smb"), remote = true),
            ),
        )
        val remote = vpath(share, scheme = "smb")

        m.measure(remote)
        // Queued behind it on the same single worker, so its arrival proves the remote folder
        // has had its turn and been passed over rather than merely not reached yet.
        m.measure(vpath(local))
        waitFor("the sentinel behind it to settle") { m.total(vpath(local)) is FolderTotal.Settled }

        assertEquals(FolderTotal.Unknown, m.total(remote))
        assertFalse("the share was listed", listed.toList().any { it.startsWith("smb") })
    }
}
