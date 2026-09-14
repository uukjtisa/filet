package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimate is a claim about an encoder, so it gets tested like one.
 *
 * What is checked here is not "is 0.42 the right ratio" - no fixed number is right for all
 * input, which is why the result is a range. It is the properties that make the range honest:
 * it moves the right way with the setting, already-compressed bytes never shrink, an unknown
 * size is admitted rather than guessed, and the low end is never above the high end.
 */
class SizeEstimateTest {

    private val zip = Archives.ALL.first { it.id == "zip" }
    private val tar = Archives.ALL.first { it.id == "tar" }
    private val tarXz = Archives.ALL.first { it.id == "tar.xz" }
    private val tarGz = Archives.ALL.first { it.id == "tar.gz" }

    private fun text(n: Int, each: Long = 1_000_000L) = (1..n).map { "note$it.txt" to each }

    @Test fun a_range_is_never_inverted() {
        for (f in Archives.ALL.filter { it.canCreate }) {
            for (s in listOf(null, 0, 3, 9)) {
                val e = CompressEstimate.of(text(4), f, s)
                assertTrue("${f.id}@$s: ${e.low} > ${e.high}", e.low <= e.high)
            }
        }
    }

    @Test fun text_is_expected_to_shrink() {
        val e = CompressEstimate.of(text(1), zip, null)
        assertTrue("${e.high} should be under the input", e.high < 1_000_000L)
    }

    @Test fun plain_tar_never_promises_a_saving() {
        val e = CompressEstimate.of(text(3), tar, null)
        assertTrue("tar must not claim to compress: ${e.low}", e.low >= 3_000_000L)
    }

    @Test fun turning_the_setting_up_never_makes_the_estimate_bigger() {
        var previous = Long.MAX_VALUE
        for (level in 0..9) {
            val e = CompressEstimate.of(text(2), zip, level)
            assertTrue("level $level: ${e.low} rose above $previous", e.low <= previous)
            previous = e.low
        }
    }

    @Test fun deflate_at_zero_is_store_and_says_so() {
        val e = CompressEstimate.of(text(1), zip, 0)
        // Level 0 in a zip does not compress at all, so the estimate must not shrink anything.
        assertTrue("store must not shrink: ${e.low}", e.low >= 1_000_000L)
    }

    @Test fun already_compressed_bytes_are_never_shrunk() {
        val e = CompressEstimate.of(listOf("holiday.mp4" to 100_000_000L), zip, 9)
        assertEquals(100_000_000L, e.incompressibleBytes)
        assertTrue("a folder of mp4 must not be promised a saving: ${e.low}", e.low >= 100_000_000L)
        assertTrue(e.mostlyIncompressible)
    }

    @Test fun a_mixed_selection_only_shrinks_the_half_that_can() {
        val e = CompressEstimate.of(listOf("a.txt" to 10_000_000L, "b.jpg" to 10_000_000L), tarXz, 9)
        assertEquals(10_000_000L, e.incompressibleBytes)
        assertTrue(e.low >= 10_000_000L)
        assertTrue("the text half should still be counted as shrinking: ${e.high}", e.high < 20_000_000L)
        assertFalse("half is not mostly", e.mostlyIncompressible)
    }

    @Test fun an_unmeasurable_item_is_admitted_not_guessed() {
        val e = CompressEstimate.of(listOf("Photos" to -1L, "a.txt" to 1_000_000L), zip, null)
        assertTrue(e.partial)
        assertEquals(1_000_000L, e.knownBytes)
    }

    @Test fun nothing_measurable_is_empty_rather_than_zero_bytes() {
        assertTrue(CompressEstimate.of(emptyList(), zip, null).empty)
        // A folder on its own is not empty - it is unknown, and those are different sentences.
        assertFalse(CompressEstimate.of(listOf("Photos" to -1L), zip, null).empty)
    }

    @Test fun ten_thousand_empty_files_cost_more_than_nothing() {
        val e = CompressEstimate.of((1..10_000).map { "f$it" to 0L }, tar, null)
        // Tar pads every member to a 512-byte header. An estimate of zero here would be wrong
        // by five megabytes, which is the whole archive.
        assertTrue("per-entry overhead is not counted: ${e.high}", e.high >= 5_000_000L)
    }

    @Test fun xz_is_expected_to_beat_gzip_at_the_same_end_of_the_scale() {
        val x = CompressEstimate.of(text(1), tarXz, 9)
        val g = CompressEstimate.of(text(1), tarGz, 9)
        assertTrue("xz ${x.low} should not be worse than gzip ${g.low}", x.low <= g.low)
    }

    @Test fun a_setting_outside_the_scale_is_clamped_rather_than_extrapolated() {
        val high = CompressEstimate.of(text(1), zip, 99)
        val max = CompressEstimate.of(text(1), zip, 9)
        assertEquals(max.low, high.low)
        val low = CompressEstimate.of(text(1), zip, -5)
        val min = CompressEstimate.of(text(1), zip, 0)
        assertEquals(min.low, low.low)
    }

    @Test fun every_creatable_format_returns_something_usable() {
        for (f in Archives.ALL.filter { it.canCreate }) {
            val e = CompressEstimate.of(text(2), f, null)
            assertTrue("${f.id} produced nothing", e.high > 0)
            assertFalse("${f.id} claimed to be empty", e.empty)
        }
    }
}
