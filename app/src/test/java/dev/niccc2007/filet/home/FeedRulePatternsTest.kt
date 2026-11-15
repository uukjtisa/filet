package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exclusion list as the query reads it. Each kind of line has to come out as patterns that
 * hide the entry itself and everything under it, anchored so that a start never matches the
 * middle of a name, and escaped so that a typed `_` is a character and not a wildcard.
 */
class FeedRulePatternsTest {

    @Test fun `a whole name hides the entry and what is inside it`() {
        assertEquals(listOf("%/logs", "%/logs/%"), CategoryQuery.rulePatterns(setOf("logs")))
    }

    @Test fun `a start is anchored to a separator`() {
        assertEquals(listOf("%/log\\_%"), CategoryQuery.rulePatterns(setOf("log_*")))
    }

    @Test fun `an end hides a file ending that way and a folder ending that way`() {
        assertEquals(listOf("%.ft\\_lnk", "%.ft\\_lnk/%"), CategoryQuery.rulePatterns(setOf("*.ft_lnk")))
    }

    @Test fun `a piece matches anywhere`() {
        assertEquals(listOf("%cache%"), CategoryQuery.rulePatterns(setOf("*cache*")))
    }

    @Test fun `a line that names nothing adds no pattern`() {
        assertTrue(CategoryQuery.rulePatterns(setOf("", "*", " ** ")).isEmpty())
    }
}
