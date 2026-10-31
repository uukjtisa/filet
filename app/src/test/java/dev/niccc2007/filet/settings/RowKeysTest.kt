package dev.niccc2007.filet.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every settings row says which preference it writes.
 *
 * The key is the last line of a row's hint, and it is the name the setting goes by in the
 * settings file. A row added without one would be the one setting whose hint cannot say what
 * to look for, so the call sites are read and any row missing `key =` fails here by title.
 */
class RowKeysTest {

    private fun root(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { File(it, "dev/niccc2007/filet").isDirectory }
            ?: error("sources not found from " + File(".").absolutePath)
    }

    @Test
    fun `every toggle, slider and segment row names its key`() {
        val s = File(root(), "dev/niccc2007/filet/settings/SettingsPage.kt").readText()
        val missing = ArrayList<String>()
        for (m in Regex("""\b(ToggleRow|SliderRow|SegmentRow)\(""").findAll(s)) {
            val lineStart = s.lastIndexOf('\n', m.range.first) + 1
            if ("fun " in s.substring(lineStart, m.range.first)) continue
            val args = argsOf(s, m.range.last)
            if (!Regex("""\bkey\s*=""").containsMatchIn(args)) {
                missing += m.groupValues[1] + " " + (Regex("\"([^\"]*)\"").find(args)?.groupValues?.get(1) ?: "?")
            }
        }
        assertTrue("rows with no key: $missing", missing.isEmpty())
    }

    /** The text between the '(' at [open] and its matching ')', skipping string literals. */
    private fun argsOf(s: String, open: Int): String {
        var depth = 0
        var j = open
        while (j < s.length) {
            when (s[j]) {
                '"' -> {
                    j++
                    while (s[j] != '"') j += if (s[j] == '\\') 2 else 1
                }
                '(' -> depth++
                ')' -> if (--depth == 0) return s.substring(open + 1, j)
            }
            j++
        }
        error("unbalanced call at $open")
    }
}
