package dev.niccc2007.filet.pick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of what goes back to the caller.
 *
 * Every failure here happens in somebody else's app, after Filet has closed, and looks to them
 * exactly like the user cancelling. That is why the shape is a tested value rather than an
 * `Intent` assembled inline: a wrong `Intent` throws nothing and tells nobody.
 */
class PickAnswerTest {

    private fun request(multiple: Boolean = false, openable: Boolean = true) =
        PickRequest.of("*/*", null, multiple, false, openable)

    @Test fun one_file_goes_in_the_data_slot_with_no_clip() {
        val a = PickAnswer.of(listOf("content://x/1"), request())
        assertEquals("content://x/1", a.primary)
        assertFalse("a single pick must not carry a clip", a.useClipData)
        assertEquals(1, a.uris.size)
    }

    @Test fun several_files_carry_a_clip_AND_still_fill_the_data_slot() {
        // The half that is easy to leave out. A caller that sets EXTRA_ALLOW_MULTIPLE and then
        // reads only getData() is common; giving it nothing is a silent failure that looks
        // like a cancel from where it is standing.
        val a = PickAnswer.of(listOf("content://x/1", "content://x/2"), request(multiple = true))
        assertTrue(a.useClipData)
        assertEquals(2, a.uris.size)
        assertEquals("content://x/1", a.primary)
    }

    @Test fun order_is_preserved_because_callers_use_it() {
        val uris = listOf("content://x/3", "content://x/1", "content://x/2")
        assertEquals(uris, PickAnswer.of(uris, request(multiple = true)).uris)
    }

    @Test fun a_single_pick_request_never_returns_more_than_one() {
        // A caller that did not ask for multiple cannot read a clip, so handing it several is
        // handing it one at random.
        val a = PickAnswer.of(listOf("content://x/1", "content://x/2"), request(multiple = false))
        assertEquals(1, a.uris.size)
        assertFalse(a.useClipData)
        assertEquals("content://x/1", a.primary)
    }

    @Test fun nothing_picked_is_empty_rather_than_a_result_with_no_data() {
        val a = PickAnswer.of(emptyList(), request())
        assertTrue(a.isEmpty)
        assertNull(a.primary)
    }

    // ── the refusals ──

    @Test fun picking_nothing_is_refused_with_what_to_do() {
        val why = PickRules.refusal(request(), count = 0, anyDirectories = false)
        assertNotNull(why)
        assertTrue("got: $why", why!!.contains("Choose"))
    }

    @Test fun a_folder_is_refused_because_the_caller_will_open_a_stream_on_it() {
        val why = PickRules.refusal(request(openable = true), count = 1, anyDirectories = true)
        assertNotNull(why)
        assertTrue("got: $why", why!!.contains("folder", ignoreCase = true))
    }

    @Test fun a_folder_is_refused_even_when_the_caller_did_not_say_openable() {
        // Filet has no way to hand over a directory at all - there is no grantable URI for one
        // short of a documents provider, which this round does not claim.
        assertNotNull(PickRules.refusal(request(openable = false), count = 1, anyDirectories = true))
    }

    @Test fun several_files_are_refused_when_the_caller_asked_for_one() {
        val why = PickRules.refusal(request(multiple = false), count = 3, anyDirectories = false)
        assertNotNull(why)
        assertTrue("got: $why", why!!.contains("one file"))
    }

    @Test fun several_files_are_fine_when_the_caller_asked_for_several() {
        assertNull(PickRules.refusal(request(multiple = true), count = 3, anyDirectories = false))
    }

    @Test fun one_ordinary_file_is_never_refused() {
        // The positive control. Without it, a rule that refused everything would pass all of
        // the above.
        assertNull(PickRules.refusal(request(), count = 1, anyDirectories = false))
        assertNull(PickRules.refusal(request(multiple = true), count = 1, anyDirectories = false))
    }

    @Test fun every_refusal_is_a_sentence_somebody_can_act_on() {
        val cases = listOf(
            Triple(request(), 0, false),
            Triple(request(), 1, true),
            Triple(request(multiple = false), 2, false),
        )
        for ((r, count, dirs) in cases) {
            val why = PickRules.refusal(r, count, dirs)!!
            // A whole sentence with an instruction in it, not a terse fragment. Counted in
            // words rather than characters: "Choose a file first." is a perfectly good refusal
            // and a character threshold rejected it for being one letter short, which says
            // more about the threshold than about the sentence.
            assertTrue("does not end as a sentence: $why", why.trim().endsWith("."))
            assertTrue("too terse to act on: $why", why.trim().split(" ").size >= 4)
        }
    }
}
