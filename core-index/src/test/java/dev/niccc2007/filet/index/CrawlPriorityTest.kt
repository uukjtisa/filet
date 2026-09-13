package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The temporary jump: a search during a crawl bends the crawl toward it, then lets go.
 *
 * The two things that must hold are that nothing is lost and that the original order comes
 * back. Both are easy to break with a "smart" reorder and neither is visible on screen until
 * a crawl finishes with folders missing.
 */
class CrawlPriorityTest {

    private var next = 0L
    private fun p(path: String) = Pending(next++, next, VPath.of("local", path))

    private val pending = listOf(
        p("/storage/emulated/0/Android/data/com.whatever/files"),
        p("/storage/emulated/0/DCIM/Camera"),
        p("/storage/emulated/0/Documents/Invoices"),
        p("/storage/emulated/0/Download"),
        p("/storage/emulated/0/Music/Albums/Invoices are not music"),
        p("/storage/emulated/0/Android/obb"),
    )

    private fun names(list: List<Pending>) = list.map { it.path.name }

    // ── the jump ──

    @Test fun a_query_pulls_the_folder_that_matches_it_to_the_front() {
        val steered = steer(pending, "invoice")
        assertEquals("Invoices", steered.first().path.name)
    }

    @Test fun nothing_is_dropped_by_a_detour() {
        // A filter here would silently make the crawl finish with folders it never visited,
        // and the generation sweep would then delete everything in them.
        val steered = steer(pending, "invoice")
        assertEquals(pending.size, steered.size)
        assertEquals(pending.map { it.order }.toSet(), steered.map { it.order }.toSet())
    }

    @Test fun the_noisy_machine_folders_go_to_the_back_for_the_duration() {
        val steered = names(steer(pending, "invoice"))
        assertTrue(
            "Android/data and obb should trail: $steered",
            steered.indexOf("files") > steered.indexOf("Camera") &&
                steered.indexOf("obb") > steered.indexOf("Download"),
        )
    }

    @Test fun ties_keep_the_order_the_crawl_discovered_them_in() {
        // Nothing matches, so every entry scores on depth and noise alone. The two ordinary
        // folders tie and must not swap: the breadth-first shape inside a band is the thing
        // that makes a crawl feel like it is making progress.
        val steered = steer(pending, "zzzz")
        val camera = steered.indexOfFirst { it.path.name == "Camera" }
        val download = steered.indexOfFirst { it.path.name == "Download" }
        assertTrue("Download is discovered later and sits shallower", download >= 0 && camera >= 0)
        assertEquals(
            steered.filter { steerScore(it.path, "zzzz") == steerScore(steered[camera].path, "zzzz") }
                .map { it.order }
                .sorted(),
            steered.filter { steerScore(it.path, "zzzz") == steerScore(steered[camera].path, "zzzz") }
                .map { it.order },
        )
    }

    @Test fun a_deeper_match_still_beats_a_shallow_miss() {
        val steered = names(steer(pending, "invoice"))
        assertTrue(
            "the deep folder whose name carries the word still outranks Download: $steered",
            steered.indexOf("Invoices are not music") < steered.indexOf("Download"),
        )
    }

    @Test fun an_exact_folder_name_beats_a_folder_that_merely_contains_the_word() {
        val steered = names(steer(pending, "invoices"))
        assertTrue(steered.indexOf("Invoices") < steered.indexOf("Invoices are not music"))
    }

    // ── coming back ──

    @Test fun ending_the_detour_restores_the_exact_order_the_crawl_had() {
        val steered = steer(pending, "invoice")
        assertEquals(pending.map { it.order }, unsteer(steered).map { it.order })
    }

    @Test fun an_empty_query_is_the_same_as_ending_the_detour() {
        // The crawl clears a detour by steering with nothing, so these two have to agree or
        // clearing would be its own third ordering.
        assertEquals(unsteer(pending).map { it.order }, steer(pending, "").map { it.order })
        assertEquals(unsteer(pending).map { it.order }, steer(pending, "   ").map { it.order })
    }

    @Test fun a_detour_survives_partial_progress_and_still_restores() {
        // Half the queue has been walked during the detour. What is left must still come back
        // in discovery order, which is what "resumes where it would have been" means.
        val steered = steer(pending, "invoice")
        val left = steered.drop(3)
        val restored = unsteer(left)
        assertEquals(left.map { it.order }.sorted(), restored.map { it.order })
    }

    // ── scoring rules on their own ──

    @Test fun a_one_letter_query_does_not_steer_anything() {
        // Every folder contains "a" somewhere. Steering on that would shuffle the whole crawl
        // as soon as somebody put one character in the box.
        assertEquals(0, steerScore(VPath.of("local", "/storage/emulated/0/Documents"), "a"))
    }

    @Test fun a_query_of_punctuation_alone_steers_nothing() {
        assertEquals(0, steerScore(VPath.of("local", "/storage/emulated/0/Documents"), "..."))
        assertEquals(0, steerScore(VPath.of("local", "/storage/emulated/0/Documents"), ""))
    }

    @Test fun the_query_is_matched_by_word_not_as_one_string() {
        val music = VPath.of("local", "/storage/emulated/0/Music")
        assertTrue(steerScore(music, "cold play music") > steerScore(music, "cold play"))
    }

    // ── the budget ──

    @Test fun a_detour_lets_go_on_its_own() {
        val d = Detour("invoice", startedAt = 1_000L)
        assertFalse(d.expired(1_000L))
        assertFalse(d.expired(1_000L + DETOUR_MS - 1))
        assertTrue(d.expired(1_000L + DETOUR_MS))
    }

    @Test fun a_detour_that_matches_nothing_still_expires() {
        // Otherwise the crawl finishes in the deprioritised order forever, which nobody asked
        // for and nothing would ever undo.
        val d = Detour("zzzzzz", startedAt = 0L, budgetMs = 5_000L)
        assertTrue(d.expired(5_000L))
    }
}
