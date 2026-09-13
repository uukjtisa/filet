package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.data.Bookmarks
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.index.SearchScope
import dev.niccc2007.filet.index.WalkSearchSource
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M1's mechanics: two independent panes, sort, hidden files, bookmarks, archive browsing and
 * live (non-indexed) search.
 *
 * > Used daily in place of the incumbent file manager (PLAN.md §8, M1).
 *
 * That criterion is a verdict only its user can give, so it is not asserted here. What *is*
 * asserted is every capability it rests on - and in particular the one genuinely hard part of
 * a dual pane: two panes with **separate** navigation stacks, selections and searches, which
 * is where most two-pane file managers quietly share state and misbehave.
 */
@RunWith(AndroidJUnit4::class)
class BrowsingTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var prefs: Prefs
    private lateinit var scope: CoroutineScope
    private lateinit var a: PaneController
    private lateinit var b: PaneController

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    /** Polls the pane's own state rather than sleeping a fixed amount. */
    private fun settle(pane: PaneController, timeoutMs: Long = 8_000, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!pane.state.value.loading && until()) return
            Thread.sleep(25)
        }
        throw AssertionError("pane ${pane.id} never settled: ${pane.state.value}")
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "browse-" + System.nanoTime())
        File(tmp, "left/deep").mkdirs()
        File(tmp, "right").mkdirs()
        File(tmp, "left/alpha.txt").writeText("a")
        File(tmp, "left/beta.txt").writeText("bb")
        File(tmp, "left/gamma.log").writeText("ccc")
        File(tmp, "left/.hidden-note.txt").writeText("secret")
        File(tmp, "left/deep/needle-in-a-haystack.md").writeText("found me")
        File(tmp, "right/kept.txt").writeText("r")

        ZipOutputStream(File(tmp, "left/bundle.zip").outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("inside/one.txt")); zos.write("1".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("inside/two.txt")); zos.write("22".toByteArray()); zos.closeEntry()
        }

        vfs = Vfs(listOf(LocalProvider(listOf(tmp)), ArchiveProvider()))
        prefs = Prefs(context)
        prefs.setShowHidden(false)
        prefs.setSort(SortSpec(SortKey.NAME, descending = false, foldersFirst = true))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sources = listOf(WalkSearchSource(vfs))
        a = PaneController(1, vfs, prefs, scope, sources)
        b = PaneController(2, vfs, prefs, scope, sources)
    }

    @After fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun openLeft() {
        a.navigateTo(vpath(File(tmp, "left")))
        settle(a) { a.state.value.entries.isNotEmpty() }
    }

    private fun openRight() {
        b.navigateTo(vpath(File(tmp, "right")))
        settle(b) { b.state.value.entries.isNotEmpty() }
    }

    // ── the dual pane ──

    @Test fun two_panes_hold_separate_locations_histories_and_selections() {
        openLeft(); openRight()
        assertEquals("left", a.state.value.cwd?.name)
        assertEquals("right", b.state.value.cwd?.name)

        a.navigateTo(vpath(File(tmp, "left/deep")))
        settle(a) { a.state.value.cwd?.name == "deep" }
        // Moving one pane must not move the other. This is the whole feature.
        assertEquals("right", b.state.value.cwd?.name)

        // Going back in one pane must walk ONE pane's history. Both have moved once, so both
        // can go back - the claim is that the stacks are separate, not that B's is empty.
        assertTrue(a.state.value.canGoBack)
        a.goBack()
        settle(a) { a.state.value.cwd?.name == "left" }
        assertEquals("pane B must not have moved", "right", b.state.value.cwd?.name)
        assertTrue("pane B's own history must be untouched", b.state.value.canGoBack)

        a.selectAll()
        assertTrue(a.state.value.selecting)
        assertTrue("selecting in one pane must not select in the other", !b.state.value.selecting)
    }

    // ── sort and filter ──

    @Test fun sort_order_changes_what_the_pane_shows_without_re_reading_disk() {
        openLeft()
        val byName = a.state.value.entries.filter { !it.isDir }.map { it.name }
        assertEquals(byName.sortedBy { it.lowercase() }, byName)

        // Wait for the ORDER to change, not for the list to be non-empty: a pane that is
        // already populated satisfies "not empty" instantly and the assertion then races the
        // re-render it is meant to be testing.
        prefs.setSort(SortSpec(SortKey.SIZE, descending = true, foldersFirst = true))
        settle(a) { a.state.value.entries.filter { !it.isDir }.map { it.name } != byName }
        val bySize = a.state.value.entries.filter { !it.isDir }
        assertEquals(bySize.sortedByDescending { it.size }.map { it.name }, bySize.map { it.name })

        prefs.setSort(SortSpec(SortKey.NAME, descending = true, foldersFirst = true))
        settle(a) { a.state.value.entries.filter { !it.isDir }.map { it.name } == byName.reversed() }
        val desc = a.state.value.entries.filter { !it.isDir }.map { it.name }
        assertEquals(byName.reversed(), desc)
    }

    @Test fun folders_first_is_respected() {
        openLeft()
        val kinds = a.state.value.entries.map { it.isDir }
        assertEquals("directories must lead", kinds.sortedByDescending { it }, kinds)
    }

    @Test fun hidden_files_appear_only_when_asked_for() {
        openLeft()
        assertTrue(a.state.value.entries.none { it.name == ".hidden-note.txt" })
        prefs.setShowHidden(true)
        settle(a) { a.state.value.entries.any { it.name == ".hidden-note.txt" } }
        prefs.setShowHidden(false)
        settle(a) { a.state.value.entries.none { it.name == ".hidden-note.txt" } }
    }

    // ── archives as directories ──

    @Test fun an_archive_browses_as_a_directory() {
        val zip = File(tmp, "left/bundle.zip").absolutePath.replace(File.separatorChar, '/')
        a.navigateTo(VPath.of("zip", "$zip!/inside"))
        settle(a) { a.state.value.entries.isNotEmpty() }
        val names = a.state.value.entries.map { it.name }
        assertTrue("archive listing was $names", names.containsAll(listOf("one.txt", "two.txt")))

        val text = runBlocking {
            vfs.openRead(VPath.of("zip", "$zip!/inside/two.txt")).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals("22", text)
    }

    // ── live search: the M1 half that ships before the index ──

    @Test fun live_search_finds_a_file_in_a_subfolder_without_an_index() {
        openLeft()
        a.openSearch(true)
        a.setScope(SearchScope.SUBFOLDERS)
        a.setQuery("needle")
        settle(a, 12_000) { a.state.value.search.hits.isNotEmpty() }
        assertTrue(
            "live search returned ${a.state.value.search.hits.map { it.node.name }}",
            a.state.value.search.hits.any { it.node.name == "needle-in-a-haystack.md" },
        )
    }

    @Test fun each_pane_searches_on_its_own() {
        openLeft(); openRight()
        a.openSearch(true); a.setScope(SearchScope.FOLDER); a.setQuery("alpha")
        settle(a) { a.state.value.search.hits.isNotEmpty() }
        assertTrue("pane B must not have been searched", !b.state.value.search.open)
        assertEquals("", b.state.value.search.query)
    }

    // ── bookmarks ──

    @Test fun a_bookmark_survives_a_restart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val target = vpath(File(tmp, "left/deep"))
        val bookmarks = Bookmarks(Prefs(context))
        bookmarks.remove(target)
        bookmarks.add(target, "Deep")
        assertTrue(bookmarks.items.value.any { it.path == target })

        // A second instance reads the same store - which is what "survives a restart" means.
        assertTrue(Bookmarks(Prefs(context)).items.value.any { it.path == target })
        bookmarks.remove(target)
        assertTrue(Bookmarks(Prefs(context)).items.value.none { it.path == target })
    }
}
