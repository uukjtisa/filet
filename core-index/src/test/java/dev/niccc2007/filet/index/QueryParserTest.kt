package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Row(
    override val name: String,
    override val size: Long = 0,
    override val mtime: Long = 0,
    override val isDir: Boolean = false,
    override val pathText: String = "/storage/emulated/0/$name",
    override val origin: String? = null,
) : Candidate {
    override val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

class QueryParserTest {

    private fun matches(query: String, row: Row, now: Long = NOW): Boolean =
        QueryEval.matches(QueryParser.parse(query).root, row, now)

    @Test fun a_bare_word_is_free_text_not_a_filter() {
        val q = QueryParser.parse("holiday photos")
        assertEquals("holiday photos", q.free)
        // Free text ranks, it does not exclude - otherwise the abbreviation matches that are
        // the point of the scorer would be filtered out before scoring.
        assertTrue(matches("holiday photos", Row("unrelated.txt")))
    }

    @Test fun type_matches_a_group() {
        assertTrue(matches("type:image", Row("a.png")))
        assertFalse(matches("type:image", Row("a.txt")))
    }

    @Test fun type_dir_matches_only_directories() {
        assertTrue(matches("type:dir", Row("Download", isDir = true)))
        assertFalse(matches("type:dir", Row("a.txt")))
    }

    @Test fun ext_matches_with_or_without_the_dot() {
        assertTrue(matches("ext:apk", Row("game.apk")))
        assertTrue(matches("ext:.apk", Row("game.apk")))
        assertFalse(matches("ext:apk", Row("game.zip")))
    }

    @Test fun size_comparison() {
        assertTrue(matches("size:>50mb", Row("big.mp4", size = 80L * 1024 * 1024)))
        assertFalse(matches("size:>50mb", Row("small.mp4", size = 3L * 1024 * 1024)))
    }

    @Test fun size_units_are_binary() {
        assertEquals(50L * 1024 * 1024, QueryEval.parseSize("50mb"))
        assertEquals(1024L, QueryEval.parseSize("1kb"))
        assertEquals(7L, QueryEval.parseSize("7"))
    }

    @Test fun size_range() {
        assertTrue(matches("size:1mb..10mb", Row("mid", size = 5L * 1024 * 1024)))
        assertFalse(matches("size:1mb..10mb", Row("big", size = 50L * 1024 * 1024)))
    }

    /**
     * `modified:<7d` reads as "within the last 7 days", which is the opposite of a literal
     * `mtime < 7d`. Getting this backwards is the classic bug in a search grammar.
     */
    @Test fun modified_less_than_means_recently() {
        val recent = Row("new.txt", mtime = NOW - 2L * 86_400_000)
        val old = Row("old.txt", mtime = NOW - 40L * 86_400_000)
        assertTrue(matches("modified:<7d", recent))
        assertFalse(matches("modified:<7d", old))
        assertTrue(matches("modified:>7d", old))
    }

    @Test fun negation_excludes() {
        assertFalse(matches("-type:image", Row("a.png")))
        assertTrue(matches("-type:image", Row("a.txt")))
    }

    @Test fun space_is_and() {
        val apk = Row("game.apk", size = 80L * 1024 * 1024)
        assertTrue(matches("type:apk size:>50mb", apk))
        assertFalse(matches("type:apk size:>500mb", apk))
    }

    @Test fun or_matches_either_side() {
        assertTrue(matches("type:apk OR type:image", Row("a.png")))
        assertTrue(matches("type:apk OR type:image", Row("a.apk")))
        assertFalse(matches("type:apk OR type:image", Row("a.txt")))
    }

    @Test fun parentheses_group() {
        val row = Row("a.png")
        assertTrue(matches("(type:apk OR type:image) -ext:zip", row))
        assertFalse(matches("(type:apk OR type:zip) -ext:png", row))
    }

    @Test fun in_matches_a_path_fragment() {
        assertTrue(matches("in:Download", Row("a.txt", pathText = "/storage/emulated/0/Download/a.txt")))
        assertFalse(matches("in:Android", Row("a.txt", pathText = "/storage/emulated/0/Download/a.txt")))
    }

    /** `from:` is the one nothing else on Android has: it searches provenance, not names. */
    @Test fun from_matches_provenance_only() {
        val downloaded = Row("clip.mp4", origin = "https://youtube.com/watch?v=abc")
        assertTrue(matches("from:youtube", downloaded))
        assertFalse(matches("from:youtube", Row("clip.mp4")))
    }

    @Test fun a_quoted_phrase_is_one_term() {
        val q = QueryParser.parse("\"my holiday\" type:image")
        assertTrue(q.root is Node.And)
        assertTrue(matches("\"my holiday\" type:image", Row("a.png")))
    }

    /** Invalid syntax never errors: it degrades to a literal search. */
    @Test fun an_unknown_field_is_treated_as_text() {
        val q = QueryParser.parse("frobnicate:yes")
        assertEquals("frobnicate:yes", q.free)
        assertTrue(matches("frobnicate:yes", Row("anything")))
    }

    @Test fun an_unclosed_quote_does_not_throw() {
        val q = QueryParser.parse("\"unfinished")
        assertTrue(q.root is Node.Text)
    }

    @Test fun a_time_in_a_name_is_not_a_field() {
        val q = QueryParser.parse("10:30")
        assertEquals("10:30", q.free)
    }

    @Test fun empty_query_is_everything() {
        assertTrue(QueryParser.parse("").isEmpty)
        assertTrue(matches("", Row("anything")))
    }

    @Test fun relative_times_parse() {
        assertEquals(NOW - 86_400_000L, QueryEval.parseTime("1d", NOW))
        assertEquals(NOW - 7 * 86_400_000L, QueryEval.parseTime("1w", NOW))
        assertEquals(NOW - 3 * 3_600_000L, QueryEval.parseTime("3h", NOW))
    }

    private companion object { const val NOW = 1_760_000_000_000L }
}
