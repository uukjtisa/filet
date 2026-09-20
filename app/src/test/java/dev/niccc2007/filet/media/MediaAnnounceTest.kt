package dev.niccc2007.filet.media

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which paths the media index should be told about.
 *
 * Reported as: a video sent to Filet over the network did not show up in the gallery. The file
 * was written correctly; the gallery reads MediaStore, and MediaStore is only told about a
 * file when an app announces it.
 *
 * The rule is a pure function because both failure directions are silent. Announce too little
 * and the gallery stays stale with no error anywhere; hand the scanner a path inside a mounted
 * archive and it is given something that is not a file.
 */
class MediaAnnounceTest {

    private fun local(p: String) = VPath.of("local", p)

    // ── the report ──

    @Test
    fun `a file received over the network is announced`() {
        assertTrue(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/Filet/Received/clip.mp4")))
    }

    @Test
    fun `an ordinary file on the device is announced`() {
        assertTrue(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/Download/song.mp3")))
    }

    @Test
    fun `a deleted path is still announced`() {
        // The same call removes a file from the index. Skipping paths that no longer exist is
        // how a deleted photo stays in the gallery as a broken thumbnail.
        assertTrue(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/DCIM/gone.jpg")))
    }

    // ── what must never reach the scanner ──

    @Test
    fun `an entry inside a mounted archive is not a file`() {
        assertFalse(MediaAnnounce.worthAnnouncing(VPath.of("zip", "/storage/emulated/0/a.zip!/inner.png")))
        assertFalse(MediaAnnounce.worthAnnouncing(VPath.of("apk", "/storage/emulated/0/a.apk!/res/x.png")))
    }

    @Test
    fun `a local path that still carries a container marker is refused`() {
        // Defence in depth: the marker decides, not only the scheme.
        assertFalse(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/a.zip!/inner.png")))
    }

    @Test
    fun `a file on a remote is not on this device`() {
        for (scheme in listOf("sftp", "ftp", "peer", "smb")) {
            assertFalse(scheme, MediaAnnounce.worthAnnouncing(VPath.of(scheme, "/home/me/clip.mp4")))
        }
    }

    @Test
    fun `app private directories and thumbnail caches stay out of the gallery`() {
        assertFalse(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/Android/data/dev.niccc2007.filet/files/t.jpg")))
        assertFalse(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/Android/obb/x/y.dat")))
        assertFalse(MediaAnnounce.worthAnnouncing(local("/storage/emulated/0/.thumbnails/1.jpg")))
    }

    @Test
    fun `every path is absolute, so there is no relative case`() {
        // VPath.normalise makes this true by construction; asserted so that a guard for it is
        // not added back as dead code.
        assertEquals("/Download/song.mp3", local("Download/song.mp3").path)
    }

    // ── the batch ──

    @Test
    fun `a mixed batch keeps only the real files`() {
        val paths = listOf(
            local("/storage/emulated/0/Download/a.mp4"),
            VPath.of("zip", "/storage/emulated/0/b.zip!/c.png"),
            local("/storage/emulated/0/Android/data/x/y.jpg"),
            local("/storage/emulated/0/DCIM/d.jpg"),
        )
        assertEquals(
            listOf("/storage/emulated/0/Download/a.mp4", "/storage/emulated/0/DCIM/d.jpg"),
            MediaAnnounce.filesystemPaths(paths),
        )
    }

    @Test
    fun `the same path twice is announced once`() {
        val p = local("/storage/emulated/0/Download/a.mp4")
        assertEquals(1, MediaAnnounce.filesystemPaths(listOf(p, p)).size)
    }

    @Test
    fun `nothing worth announcing yields nothing`() {
        assertTrue(MediaAnnounce.filesystemPaths(emptyList()).isEmpty())
        assertTrue(MediaAnnounce.filesystemPaths(listOf(VPath.of("sftp", "/a/b.mp4"))).isEmpty())
    }
}
