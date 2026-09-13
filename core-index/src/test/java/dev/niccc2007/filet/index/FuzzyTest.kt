package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    private fun s(q: String, name: String): Int =
        Fuzzy.score(Fuzzy.fold(q).toCharArray(), Fuzzy.fold(name).toCharArray(), name.toCharArray())

    @Test fun folding_strips_diacritics_and_case() {
        assertEquals("resume", Fuzzy.fold("Résumé"))
        assertEquals("uber", Fuzzy.fold("ÜBER"))
    }

    @Test fun nfd_and_nfc_spellings_fold_together() {
        val nfc = "é"            // e-acute, single code point
        val nfd = "é"           // e + combining acute
        assertEquals(Fuzzy.fold(nfc), Fuzzy.fold(nfd))
    }

    @Test fun abbreviation_finds_the_word() {
        assertNotEquals(Fuzzy.NO_MATCH, s("dwnld", "Downloads"))
    }

    @Test fun camel_humps_are_matchable() {
        assertNotEquals(Fuzzy.NO_MATCH, s("AM", "AndroidManifest.xml"))
    }

    @Test fun separator_abbreviation_finds_the_apk() {
        assertNotEquals(Fuzzy.NO_MATCH, s("mpmgr", "MP-Manager-1.4.apk"))
    }

    @Test fun non_subsequence_does_not_match() {
        assertEquals(Fuzzy.NO_MATCH, s("zzz", "Downloads"))
    }

    @Test fun exact_name_outranks_a_mere_containment() {
        assertTrue(s("report", "report") > s("report", "quarterly-report-draft-final.txt"))
    }

    @Test fun prefix_outranks_a_late_match() {
        assertTrue(s("doc", "documents") > s("doc", "my-old-doc"))
    }

    @Test fun consecutive_run_outranks_a_scattered_subsequence() {
        assertTrue(s("abc", "abc_file") > s("abc", "axbxcfile"))
    }

    /**
     * Initialisms are deliberately competitive with a solid run: `a_b_c` is how people
     * abbreviate, and the word-boundary weight is what makes `dwnld` and `AM` work at all.
     * Asserted explicitly so a later tuning pass cannot quietly delete the behaviour.
     */
    @Test fun an_initialism_still_scores_well() {
        assertTrue(s("abc", "a_b_c_file") > s("abc", "axbxcfile"))
    }

    @Test fun a_name_hit_outranks_an_ancestor_hit() {
        val needle = Fuzzy.fold("photo").toCharArray()
        val inName = Fuzzy.score(needle, "photo.jpg".toCharArray(), inName = true)
        val inDir = Fuzzy.score(needle, "photo.jpg".toCharArray(), inName = false)
        assertTrue(inName > inDir)
    }

    @Test fun exact_extension_is_a_bonus() {
        val needle = Fuzzy.fold("apk").toCharArray()
        val hay = "game.apk".toCharArray()
        assertTrue(Fuzzy.score(needle, hay, extFold = "apk") > Fuzzy.score(needle, hay, extFold = null))
    }

    @Test fun empty_query_scores_zero_rather_than_failing() {
        assertEquals(0, s("", "anything"))
    }

    @Test fun query_longer_than_the_name_cannot_match() {
        assertEquals(Fuzzy.NO_MATCH, s("verylongquery", "ab"))
    }

    // ── typo repair ──
    @Test fun single_typo_is_within_budget() {
        assertEquals(1, Fuzzy.editDistance("screnshot".toCharArray(), "screenshot".toCharArray(), 2))
    }

    @Test fun transposition_costs_one_not_two() {
        assertEquals(1, Fuzzy.editDistance("recieve".toCharArray(), "receive".toCharArray(), 2))
    }

    @Test fun distance_beyond_the_cap_reports_the_cap_plus_one_rather_than_working() {
        assertEquals(3, Fuzzy.editDistance("abcdefgh".toCharArray(), "zyxwvuts".toCharArray(), 2))
    }

    @Test fun identical_strings_have_distance_zero() {
        assertEquals(0, Fuzzy.editDistance("same".toCharArray(), "same".toCharArray(), 2))
    }

    @Test fun typo_budget_tightens_for_short_queries() {
        assertEquals(1, Fuzzy.typoBudget(4))
        assertEquals(2, Fuzzy.typoBudget(9))
    }

    @Test fun scoring_five_hundred_candidates_stays_inside_the_budget() {
        // SEARCH.md §5.5 budgets < 2 ms for the rerank. Measured, not asserted by faith.
        val names = (0 until 500).map { "Screenshot_2026-09-$it-photo-album-final.png" }
        val folded = names.map { Fuzzy.fold(it).toCharArray() }
        val raw = names.map { it.toCharArray() }
        val needle = Fuzzy.fold("scrnsht").toCharArray()
        repeat(50) { for (i in folded.indices) Fuzzy.score(needle, folded[i], raw[i]) }  // warm
        val t0 = System.nanoTime()
        repeat(10) { for (i in folded.indices) Fuzzy.score(needle, folded[i], raw[i]) }
        val perPassMs = (System.nanoTime() - t0) / 10.0 / 1_000_000.0
        assertTrue("rerank of 500 took ${perPassMs}ms, budget 2ms", perPassMs < 2.0)
    }
}
