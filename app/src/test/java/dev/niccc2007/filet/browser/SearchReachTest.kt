package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.SearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a pane can reach the search engine, and whether the banner tells the truth.
 *
 * Both of these were reported as one thing - "search everywhere returns nothing and the index
 * keeps shrinking" - and they are two unrelated faults, neither of them in the index. Measured on
 * the real database at the time: 42,535 rows, an FTS match answering in 1.8 ms.
 */
class SearchReachTest {

    private val someFolder = "local:///storage/emulated/0/Download"

    // ── which scopes a pane offers ──

    @Test
    fun `a folder pane offers all four`() {
        val out = SearchReach.scopesFor(someFolder)
        assertEquals(
            listOf(
                SearchScope.FOLDER,
                SearchScope.SUBFOLDERS,
                SearchScope.DEVICE,
                SearchScope.PROVENANCE,
            ),
            out,
        )
    }

    @Test
    fun `home offers only the ones that mean something without a folder`() {
        // The reported state: the bar opened on Home with "This folder" ticked, and there is no
        // folder. Both folder-shaped scopes are gone rather than shown and dead.
        val out = SearchReach.scopesFor(null)
        assertFalse(out.contains(SearchScope.FOLDER))
        assertFalse(out.contains(SearchScope.SUBFOLDERS))
        assertTrue(out.contains(SearchScope.DEVICE))
    }

    @Test
    fun `provenance is dropped when nothing has recorded an origin`() {
        // A scope that can only ever return nothing is the same dead switch in miniature.
        assertFalse(SearchReach.scopesFor(someFolder, provenance = false).contains(SearchScope.PROVENANCE))
        assertTrue(SearchReach.scopesFor(someFolder, provenance = true).contains(SearchScope.PROVENANCE))
    }

    @Test
    fun `every pane is offered at least the device`() {
        for (cwd in listOf(someFolder, null)) {
            for (prov in listOf(true, false)) {
                assertTrue(
                    "cwd=$cwd provenance=$prov",
                    SearchReach.scopesFor(cwd, prov).contains(SearchScope.DEVICE),
                )
            }
        }
    }

    // ── resolving a scope that cannot be honoured ──

    @Test
    fun `a folder scope with no folder becomes the device`() {
        // THE REPORTED BUG. This combination used to reach SearchRequest unchanged, match no
        // source, and return an empty list with no explanation - under a banner saying the index
        // was busy, which read as "still loading" rather than "this cannot work".
        assertEquals(SearchScope.DEVICE, SearchReach.resolve(SearchScope.FOLDER, null))
        assertEquals(SearchScope.DEVICE, SearchReach.resolve(SearchScope.SUBFOLDERS, null))
    }

    @Test
    fun `a folder scope with a folder is left alone`() {
        assertEquals(SearchScope.FOLDER, SearchReach.resolve(SearchScope.FOLDER, someFolder))
        assertEquals(SearchScope.SUBFOLDERS, SearchReach.resolve(SearchScope.SUBFOLDERS, someFolder))
    }

    @Test
    fun `device and provenance never need resolving`() {
        for (cwd in listOf(someFolder, null)) {
            assertEquals(SearchScope.DEVICE, SearchReach.resolve(SearchScope.DEVICE, cwd))
            assertEquals(SearchScope.PROVENANCE, SearchReach.resolve(SearchScope.PROVENANCE, cwd))
        }
    }

    // ── the banner ──

    @Test
    fun `the index total is named and comes first`() {
        // The "shrinking index" report. `scanned` counts THIS PASS, so it starts near zero every
        // launch; printed alone and called "files" it read as the index size going backwards.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 21_000, running = true),
        )
        assertTrue(line, line.contains("42535"))
        assertTrue(line, line.contains("21000"))
        assertTrue("the total must come first", line.indexOf("42535") < line.indexOf("21000"))
    }

    @Test
    fun `a pass that has only just started still shows the full index`() {
        // The exact moment the report was about: a restart, a pass at the very beginning.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 12, running = true),
        )
        assertTrue(line, line.contains("42535"))
    }

    @Test
    fun `the total falls back to what was known at the start`() {
        // `files` is recomputed at the end of a pass, so mid-pass it can lag. Taking the larger of
        // the two keeps the number from dipping while a crawl runs, which is the whole point.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 0, knownAtStart = 42_535, scanned = 900, running = true),
        )
        assertTrue(line, line.contains("42535"))
    }

    @Test
    fun `a steered pass names the query it is working on`() {
        val line = SearchReach.indexingLine(
            IndexStatus(files = 100, knownAtStart = 100, scanned = 4, running = true),
            steeredTo = "minecraft",
        )
        assertTrue(line, line.contains("minecraft"))
    }

    @Test
    fun `the line stays inside the prose budget`() {
        // check-prose caps a control surface at 96 characters, and this one is built at runtime
        // so the checker cannot see it. Six-figure counts are the worst case.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 999_999, knownAtStart = 999_999, scanned = 999_999, running = true),
        )
        assertTrue("${line.length}: $line", line.length <= 96)
    }

    @Test
    fun `printing only the scan is what produced the report`() {
        // The negative control, written as the code that was there.
        val old = { st: IndexStatus -> "Still indexing — ${st.scanned} files" }
        val before = IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 31_000)
        val after = IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 21_000)
        assertTrue(old(before).contains("31000"))
        assertTrue(old(after).contains("21000"))
        // Both of the new lines name the same unchanged total, so nothing appears to shrink.
        assertTrue(SearchReach.indexingLine(before).contains("42535"))
        assertTrue(SearchReach.indexingLine(after).contains("42535"))
    }

    // -- which body a pane draws --

    @Test
    fun `home draws results while a search is active`() {
        // THE LAST PIECE. The plan and the request were both fixed and Home still showed
        // nothing, because the body was chosen by pane KIND alone - the search ran, the hits
        // landed, and the overview was drawn over them.
        assertTrue(SearchReach.showsResults(PaneKind.HOME, searchActive = true))
    }

    @Test
    fun `home draws its overview when nothing is being searched`() {
        assertFalse(SearchReach.showsResults(PaneKind.HOME, searchActive = false))
    }

    @Test
    fun `a folder pane draws results while searching`() {
        assertTrue(SearchReach.showsResults(PaneKind.FOLDER, searchActive = true))
    }

    @Test
    fun `a filter pane keeps its own body even while searching`() {
        // Settings narrows the rows it is already drawing. Swapping its body for a result list
        // would replace the settings screen with a file listing.
        for (kind in listOf(PaneKind.SETTINGS, PaneKind.BOOKMARKS, PaneKind.RECENT, PaneKind.NEARBY)) {
            assertFalse(kind.name, SearchReach.showsResults(kind, searchActive = true))
        }
    }

    @Test
    fun `keying on the pane kind is what hid the results`() {
        // The negative control, written as the condition that was there.
        val oldRule = { kind: PaneKind, _: Boolean -> kind == PaneKind.FOLDER }
        assertFalse("the old rule drew the overview over the hits", oldRule(PaneKind.HOME, true))
        assertTrue(SearchReach.showsResults(PaneKind.HOME, searchActive = true))
    }
}
