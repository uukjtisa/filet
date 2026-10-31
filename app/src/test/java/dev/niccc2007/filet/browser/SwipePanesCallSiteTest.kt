package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tab swipe keeps the arriving tab's composition, which needs ONE call site for a pane.
 *
 * The flinch this guards against compiled, ran and looked like a fix: `key(pane.id)` was there,
 * but inside it the current pane and the neighbour were drawn by the two branches of an `if`.
 * Compose gives each branch a group of its own, so on commit the arriving tab moved from one
 * branch to the other and was composed from nothing - folder totals, previews and thumbnails
 * all starting again, which is the tab visibly re-appearing as the swipe settles.
 *
 * Read from source because the fault is a shape of the code, not a value a function returns.
 */
class SwipePanesCallSiteTest {

    private fun source(): String {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.first { File(it, "dev/niccc2007/filet").isDirectory }
        return File(root, "dev/niccc2007/filet/browser/TabSwipe.kt").readText()
    }

    @Test
    fun `swipe panes draws a pane from exactly one place`() {
        val src = source()
        val start = src.indexOf("fun BoxScope.SwipePanes(")
        assertTrue("SwipePanes not found - has it moved?", start >= 0)
        val bodyStart = src.indexOf(") {", start) + 3
        val end = src.indexOf("\n}\n", bodyStart)
        val body = src.substring(bodyStart, end)
        val code = body.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertEquals("content(...) call sites inside SwipePanes", 1, Regex("""\bcontent\(""").findAll(code).count())
        assertTrue("the call must sit inside key(pane.id)", Regex("""key\(pane\.id\)""").containsMatchIn(code))
    }
}
