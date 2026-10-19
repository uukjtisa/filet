package dev.niccc2007.filet.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preference the app reads can be changed by something, and nothing reads a preference
 * that has been removed.
 *
 * ## Why this reads source rather than the running app
 *
 * The defect it exists for compiles and runs perfectly. The row-display preference was read by
 * every listing while the only control that wrote it had gone in an earlier rework - so the date
 * format, the folder counts and the date column were fixed at their defaults, with all the code
 * behind them still in place, and nothing failed. A setting that cannot be reached is not an
 * error anywhere except in a statement about the whole codebase, which is what this makes.
 *
 * ## The rule
 *
 * For each `StateFlow` that `Prefs` exposes: if any file other than `Prefs.kt` reads it, then some
 * file other than `Prefs.kt` must call one of the functions that write it. "Writes it" is found
 * the way a reader would find it - the functions in `Prefs` that assign the flow's backing field,
 * and then any function in `Prefs` that calls one of those, so `hideCard` counts as a writer of
 * `hiddenCards` through `setHiddenCards`.
 *
 * A writer outside Settings counts. The sort is changed from the View menu and the drawer's folds
 * from the drawer, and those are controls; the rule is that a control exists, not where.
 *
 * Reorganising Settings is the moment this matters most: moving forty rows between rooms is
 * exactly how one gets dropped on the floor, and dropping one leaves a preference read and
 * never written - which this fails on, by name.
 */
class PrefsCoverageTest {

    private fun root(): File {
        // Unit tests run from the module directory under Gradle and from the project root in
        // some IDE configurations. Either way the sources are found from here.
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("sources not found from " + File(".").absolutePath)
    }

    private val prefsFile get() = File(root(), "dev/niccc2007/filet/data/Prefs.kt")

    private fun others(): String = root().walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "Prefs.kt" }
        .joinToString("\n") { it.readText() }

    /** Each function in Prefs, by name, with the text of its body. */
    private fun functions(prefs: String): Map<String, String> {
        val out = HashMap<String, String>()
        val decl = Regex("""\n    (?:private |internal )?fun (\w+)\(""")
        val member = Regex("""\n    (?:private |internal |override )?(?:fun|val|var|/\*\*|//|@)""")
        for (m in decl.findAll(prefs)) {
            val start = m.range.last + 1
            val next = member.find(prefs, start)?.range?.first ?: prefs.length
            out[m.groupValues[1]] = out[m.groupValues[1]].orEmpty() + prefs.substring(start, next)
        }
        return out
    }

    /** The functions that change [flow], directly or by calling one that does. */
    private fun writersOf(flow: String, funcs: Map<String, String>): Set<String> {
        val assigns = Regex("""\b_${flow}\.(value\s*=|update\b|emit\b|tryEmit\b)""")
        val writers = funcs.filter { assigns.containsMatchIn(it.value) }.keys.toMutableSet()
        while (true) {
            val more = funcs.filter { (name, body) ->
                name !in writers && writers.any { w -> Regex("""\b$w\(""").containsMatchIn(body) }
            }.keys
            if (more.isEmpty()) return writers
            writers += more
        }
    }

    @Test
    fun `every preference the app reads has something that changes it`() {
        val prefs = prefsFile.readText()
        val rest = others()
        val funcs = functions(prefs)
        val flows = Regex("""\n\s*val (\w+): StateFlow<""").findAll(prefs).map { it.groupValues[1] }.toList()
        assertTrue("no preferences found - the pattern is stale", flows.size > 20)

        val stranded = flows.filter { flow ->
            val read = Regex("""\.$flow\b""").containsMatchIn(rest)
            val written = writersOf(flow, funcs).any { w -> Regex("""\.$w\(""").containsMatchIn(rest) }
            read && !written
        }
        assertTrue(
            "read by the app but nothing outside Prefs changes them: $stranded",
            stranded.isEmpty(),
        )
    }

    @Test
    fun `every preference Settings shows is one that is written`() {
        // The other half: a row in Settings over a key nothing writes would be a row that shows a
        // value and cannot change it.
        val prefs = prefsFile.readText()
        val settings = File(root(), "dev/niccc2007/filet/settings/SettingsPage.kt").readText()
        val funcs = functions(prefs)
        val shown = Regex("""prefs\.(\w+)\.collectAsState""").findAll(settings).map { it.groupValues[1] }.toSet()
        val inert = shown.filter { flow ->
            writersOf(flow, funcs).none { w -> Regex("""\.$w\(""").containsMatchIn(settings + others()) }
        }
        assertTrue("shown in Settings and never written: $inert", inert.isEmpty())
    }

    @Test
    fun `removed settings are gone entirely, not just their rows`() {
        // Deleting a row and leaving its key, its reads and the branch that served its other
        // value is half a removal. Each of these must be absent from the whole app.
        //
        // Matched as PREFERENCES - the flow declared in Prefs, a read of it, or its setter - and
        // not as bare words. The beat renderers keep parameters called `waveSize` and `waveRest`,
        // which now receive constants; those are terms in a curve, not settings, and a check that
        // tripped on them would be a check somebody learns to ignore.
        // `pulseSense` is deliberately NOT here: it was collapsed away and then restored,
        // because it scales the onset BEFORE the detector and so decides WHICH beats fire,
        // while `pulseDepth` scales after and decides how far they move. One cannot stand in
        // for the other, which is what the collapse to a single slider got wrong.
        val flows = listOf("navWhileSelecting", "hideTermux", "waveRest", "waveSize", "drag")
        val types = listOf("NavDuringSelection", "setDragBehaviour", "settingsSummary")
        val prefs = prefsFile.readText()
        val rest = others()
        val left = flows.filter { f ->
            val setter = "set" + f.replaceFirstChar { it.uppercase() }
            Regex("""\bval $f: StateFlow""").containsMatchIn(prefs) ||
                Regex("""prefs\.$f\b""").containsMatchIn(rest) ||
                Regex("""\.$setter\(""").containsMatchIn(rest)
        } + types.filter { Regex("""\b$it\b""").containsMatchIn(rest) }
        assertTrue("still referenced: $left", left.isEmpty())
    }
}
