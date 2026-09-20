package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.browser.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "open with" offers, and what the reveal offers.
 *
 * Bug identified: the list was built from the extension, so anything the tables did not
 * recognise got the code editor, the hex viewer and "another app" - and nothing else. A
 * `.mcaddon`, which is a zip, could not be opened with the archive viewer at all, because the
 * archive viewer only appeared when it was already the default.
 *
 * This list exists for the case where the extension is wrong or unknown, so it is the one list
 * the extension must not decide. Two tiers rather than one, because dumping all six viewers on
 * every file would fix the report and make the common case worse.
 */
class HandlerCandidatesTest {

    // ── the report ──

    @Test
    fun `an unrecognised file offers the archive viewer without any reveal`() {
        // The whole complaint, and a .mcaddon lands in exactly this branch.
        val offer = HandlerCandidates.offer(FileKind.OTHER, HandlerId.TEXT)
        assertTrue("it has to be reachable", offer.all.contains(HandlerId.ARCHIVE))
        assertTrue("and for a blob it is a good guess, so not behind the reveal", offer.likely.contains(HandlerId.ARCHIVE))
    }

    @Test
    fun `every viewer is reachable for every kind of file`() {
        // The flexibility asked for: somebody who wants to try the photo viewer on a .mcaddon
        // can. Nothing is withheld, it is only ordered.
        val every = listOf(
            HandlerId.TEXT, HandlerId.ARCHIVE, HandlerId.IMAGE,
            HandlerId.MEDIA, HandlerId.APK, HandlerId.HEX,
        )
        for (kind in FileKind.entries) {
            if (kind == FileKind.FOLDER) continue
            val all = HandlerCandidates.offer(kind, HandlerId.TEXT).all
            for (h in every) assertTrue("$kind cannot reach $h", all.contains(h))
        }
    }

    // ── the tiers ──

    @Test
    fun `a photo leads with the image viewer and hides the odd ones`() {
        val offer = HandlerCandidates.offer(FileKind.IMAGE, HandlerId.IMAGE)
        assertEquals(HandlerId.IMAGE, offer.likely.first())
        assertFalse("the archive viewer is not a sensible first offer for a photo", offer.likely.contains(HandlerId.ARCHIVE))
        assertTrue("but it is still one tap away", offer.rest.contains(HandlerId.ARCHIVE))
    }

    @Test
    fun `an archive leads with the archive viewer`() {
        assertEquals(HandlerId.ARCHIVE, HandlerCandidates.offer(FileKind.ARCHIVE, HandlerId.ARCHIVE).likely.first())
    }

    @Test
    fun `an app package leads with the inspector and offers the archive viewer beside it`() {
        val offer = HandlerCandidates.offer(FileKind.APK, HandlerId.APK)
        assertEquals(HandlerId.APK, offer.likely.first())
        assertTrue("browsing an apk as a zip is a normal thing to want", offer.likely.contains(HandlerId.ARCHIVE))
    }

    @Test
    fun `the default a tap would use is always first`() {
        for (kind in FileKind.entries) {
            if (kind == FileKind.FOLDER) continue
            assertEquals(kind.name, HandlerId.HEX, HandlerCandidates.offer(kind, HandlerId.HEX).likely.first())
        }
    }

    @Test
    fun `another app stays in the first tier`() {
        // Leaving Filet is always a reasonable answer, so it is never hidden behind a reveal.
        for (kind in FileKind.entries) {
            if (kind == FileKind.FOLDER) continue
            assertTrue(kind.name, HandlerCandidates.offer(kind, HandlerId.TEXT).likely.contains(HandlerId.EXTERNAL))
        }
    }

    // ── shape ──

    @Test
    fun `the two tiers never overlap`() {
        for (kind in FileKind.entries) {
            val offer = HandlerCandidates.offer(kind, HandlerId.ARCHIVE)
            for (h in offer.rest) assertFalse("$kind offers $h twice", offer.likely.contains(h))
        }
    }

    @Test
    fun `nothing is listed twice`() {
        for (kind in FileKind.entries) {
            val all = HandlerCandidates.offer(kind, HandlerId.ARCHIVE).all
            assertEquals("$kind has duplicates: $all", all.size, all.distinct().size)
        }
    }

    @Test
    fun `a folder is only ever opened as a folder`() {
        // Offering the hex viewer for a directory is a control that cannot act.
        val offer = HandlerCandidates.offer(FileKind.FOLDER, HandlerId.ARCHIVE)
        assertEquals(listOf(HandlerId.ARCHIVE), offer.likely)
        assertTrue(offer.rest.isEmpty())
    }

    @Test
    fun `every file kind offers something without revealing anything`() {
        for (kind in FileKind.entries) {
            assertTrue(kind.name, HandlerCandidates.offer(kind, HandlerId.TEXT).likely.isNotEmpty())
        }
    }
}
