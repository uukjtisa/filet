package dev.niccc2007.filet.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every tab that offers a search box narrows by it.
 *
 * A kind `searchPlan` calls FILTER shows the box; a screen that never reads what is typed into
 * it is a search box that does nothing, which is a dead switch. So the plan is read, the screen
 * each kind is drawn by is found in PaneView's `SpecialBodyContent`, and that screen's file has
 * to read the query - `LocalPaneQuery` or the pane's own `search.query`.
 */
class SearchFiltersTest {

    /** Kinds whose screen is being rebuilt elsewhere, and why they may keep the box meanwhile. */
    private val exempt = emptyMap<String, String>()

    private fun root(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { File(it, "dev/niccc2007/filet").isDirectory }
            ?: error("sources not found from " + File(".").absolutePath)
    }

    private val sources by lazy { root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `every kind with a search box has a screen that reads the query`() {
        val base = File(root(), "dev/niccc2007/filet/browser")
        val plan = File(base, "SearchPlan.kt").readText()
        val filtered = Regex("""PaneKind\.(\w+)\s*->\s*SearchMode\.FILTER""").findAll(plan).map { it.groupValues[1] }.toList()
        assertTrue("no FILTER kinds found - has SearchPlan moved?", filtered.isNotEmpty())

        val view = File(base, "PaneView.kt").readText()
        val body = view.substring(view.indexOf("private fun SpecialBodyContent"))
        val unread = ArrayList<String>()
        for (kind in filtered) {
            if (kind in exempt) continue
            val at = body.indexOf("PaneKind.$kind ->")
            if (at < 0) { unread += "$kind (not drawn by SpecialBodyContent)"; continue }
            val next = Regex("""\n\s+PaneKind\.\w+\s*->""").find(body, at + 1)?.range?.first ?: body.length
            val branch = body.substring(at, next)
            val screens = Regex("""\b([A-Z]\w*(?:Screen|Page|Body))\(""").findAll(branch).map { it.groupValues[1] }.toSet()
            val reads = screens.any { name ->
                val file = sources.firstOrNull { f -> Regex("""fun $name\(""").containsMatchIn(f.readText()) }
                file != null && file.readText().let { "LocalPaneQuery" in it || "search.query" in it }
            }
            if (!reads) unread += "$kind ($screens)"
        }
        assertTrue("search box shown but the query is never read: $unread", unread.isEmpty())
    }
}
