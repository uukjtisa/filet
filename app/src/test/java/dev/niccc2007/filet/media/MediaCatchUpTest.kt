package dev.niccc2007.filet.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registering media the gallery has never been told about.
 *
 * Reported twice: videos on the phone, listed correctly by every file manager, and absent from
 * the gallery. Announcing a file as it is written fixes only files written after the fix, so
 * everything already on disk stays invisible - which is what was being reported the second
 * time.
 *
 * The comparison is a pure function because both failures are silent. Miss a file and the
 * gallery stays wrong with no error anywhere; re-announce what is already known and the device
 * does a great deal of work to change nothing.
 */
class MediaCatchUpTest {

    // ── what counts as media ──

    @Test
    fun `pictures video and audio are media`() {
        for (n in listOf("a.jpg", "b.PNG", "c.mp4", "d.mkv", "e.mp3", "f.flac", "g.heic", "h.webm")) {
            assertTrue(n, MediaCatchUp.isMedia(n))
        }
    }

    @Test
    fun `a video is media whatever the case of its extension`() {
        // The reported file was a video, and a name from another device can carry any case.
        assertTrue(MediaCatchUp.isMedia("CLIP.MP4"))
        assertTrue(MediaCatchUp.isMedia("clip.Mp4"))
    }

    @Test
    fun `documents archives and apps are not`() {
        // A gallery was never going to show these, so announcing one is work for nothing.
        for (n in listOf("a.pdf", "b.zip", "c.apk", "d.txt", "e.kt", "f.xapk", "g")) {
            assertFalse(n, MediaCatchUp.isMedia(n))
        }
    }

    @Test
    fun `a hidden file is not media`() {
        // A leading dot hides a file; it does not separate an extension. Reading it as one
        // would push hidden files into the gallery, which is the opposite of what it means.
        assertFalse(MediaCatchUp.isMedia(".mp4"))
        assertFalse(MediaCatchUp.isMedia(".nomedia"))
        assertTrue("a hidden file with a real extension still counts", MediaCatchUp.isMedia(".hidden.mp4"))
    }

    // ── the difference ──

    @Test
    fun `only what the media index has not got is announced`() {
        val onDisk = listOf("/a/1.mp4", "/a/2.mp4", "/a/3.jpg")
        val known = setOf("/a/2.mp4")
        assertEquals(listOf("/a/1.mp4", "/a/3.jpg"), MediaCatchUp.missing(onDisk, known))
    }

    @Test
    fun `nothing missing announces nothing`() {
        val onDisk = listOf("/a/1.mp4", "/a/2.mp4")
        assertTrue(MediaCatchUp.missing(onDisk, onDisk.toSet()).isEmpty())
    }

    @Test
    fun `an empty index means everything is missing`() {
        // A phone that has never been scanned, which is the state the report describes.
        val onDisk = listOf("/a/1.mp4", "/a/2.mp4")
        assertEquals(onDisk, MediaCatchUp.missing(onDisk, emptySet()))
    }

    @Test
    fun `the same file twice is announced once`() {
        assertEquals(listOf("/a/1.mp4"), MediaCatchUp.missing(listOf("/a/1.mp4", "/a/1.mp4"), emptySet()))
    }

    @Test
    fun `the walked order is kept`() {
        // A capped pass has to be predictable: stopping halfway should mean the first half,
        // not an arbitrary half.
        val onDisk = listOf("/z.mp4", "/a.mp4", "/m.mp4")
        assertEquals(onDisk, MediaCatchUp.missing(onDisk, emptySet()))
    }

    @Test
    fun `nothing on disk is not an error`() {
        assertTrue(MediaCatchUp.missing(emptyList(), setOf("/a/1.mp4")).isEmpty())
    }
}
