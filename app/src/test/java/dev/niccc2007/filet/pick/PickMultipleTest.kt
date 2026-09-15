package dev.niccc2007.filet.pick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking several files, and the caller that only reads one.
 *
 * Nic: *"the file picker I selected multiple to upload any files.. and bruh it only uploaded
 * 1.. I think it only uploaded the one that I last long clicked to send"*.
 *
 * Verified on the device from the RECEIVING side before any code was changed, which is the
 * only place it can be settled - three files picked, and the probe reported:
 *
 *     SLOTS: data=1 clip=3          reading both slots  -> 3 uris
 *     SLOTS: data=1 clip=3          reading getData()   -> 1 uri
 *
 * So Filet answers correctly and the app that received one file reads only `getData()` and
 * ignores the `ClipData`. That is the other app's defect, and there is nothing Filet can send
 * that fixes it: `setData` holds exactly one URI by definition.
 *
 * These tests exist so the half that IS Filet's can never quietly regress into the shape the
 * report describes - because from inside Filet that failure is invisible.
 */
class PickMultipleTest {

    private val many = listOf("content://f/1.txt", "content://f/2.md", "content://f/3.csv")

    private fun request(multiple: Boolean) = PickRequest.of(
        type = "*/*",
        extraMimeTypes = null,
        allowMultiple = multiple,
        localOnly = false,
        openable = true,
    )

    @Test
    fun `every picked file comes back, in the order they were chosen`() {
        val answer = PickAnswer.of(many, request(multiple = true))
        assertEquals(many, answer.uris)
    }

    @Test
    fun `a multiple pick fills the data slot as well as the clip`() {
        // The whole of his report. A caller that sets EXTRA_ALLOW_MULTIPLE and then reads only
        // getData() is common and is not going to be fixed; handing it nothing would be a
        // silent failure indistinguishable from a cancel. It gets the first file instead.
        val answer = PickAnswer.of(many, request(multiple = true))
        assertTrue("several files must carry a ClipData", answer.useClipData)
        assertNotNull("and must still fill the data slot", answer.primary)
        assertEquals(many.first(), answer.primary)
    }

    @Test
    fun `a single pick carries no clip, because a caller that asked for one will not read it`() {
        val answer = PickAnswer.of(listOf(many.first()), request(multiple = true))
        assertEquals(false, answer.useClipData)
        assertEquals(many.first(), answer.primary)
    }

    @Test
    fun `a caller that did not ask for several is given exactly one`() {
        val answer = PickAnswer.of(many, request(multiple = false))
        assertEquals(listOf(many.first()), answer.uris)
        assertEquals(false, answer.useClipData)
    }

    @Test
    fun `and a selection of several against a single-file request is refused before the picker closes`() {
        // Belt and braces with the rule above: the refusal is what he should see, rather than
        // silently getting one of the files he chose.
        val why = PickRules.refusal(request(multiple = false), count = 3, anyDirectories = false)
        assertNotNull(why)
        assertTrue(why!!.contains("one file"))
    }

    @Test
    fun `nothing picked is refused rather than returned as an empty answer`() {
        assertEquals(true, PickAnswer.of(emptyList(), request(multiple = true)).isEmpty)
        assertNotNull(PickRules.refusal(request(multiple = true), count = 0, anyDirectories = false))
    }
}
