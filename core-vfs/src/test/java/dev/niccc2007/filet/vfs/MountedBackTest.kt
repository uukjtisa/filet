package dev.niccc2007.filet.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Going up out of a mounted container one level at a time.
 *
 * Bug identified: browsing inside an APK and pressing back left the whole container and landed
 * in the folder it lives in, skipping everything in between. `VPath.parent` is a plain string
 * walk and the `!` that separates the container file from its contents is just a character to
 * it, so from a container root it produced `apk:///storage/Download` - an archive scheme
 * pointing at a directory, which no provider can list.
 *
 * The same addressing is used for every mounted kind, so every one of them had it.
 */
class MountedBackTest {

    private val APK = VPath("apk", "/storage/Download/filet.apk!/com/example/ui")
    private val ZIP = VPath("zip", "/storage/Download/site.zip!/assets/img")

    // ── inside the container ──

    @Test
    fun `up from inside an apk stays inside it`() {
        assertEquals(
            VPath("apk", "/storage/Download/filet.apk!/com/example"),
            MountBoundary.up(APK),
        )
    }

    @Test
    fun `each level comes off one at a time`() {
        // The whole report: back at any depth used to leave the container entirely. Every
        // level has to come off singly, and only the last one crosses out.
        var at: VPath? = APK
        at = MountBoundary.up(at!!)
        assertEquals(VPath("apk", "/storage/Download/filet.apk!/com/example"), at)
        at = MountBoundary.up(at!!)
        assertEquals(VPath("apk", "/storage/Download/filet.apk!/com"), at)
        at = MountBoundary.up(at!!)
        assertEquals("the container root, not the folder it lives in", VPath("apk", "/storage/Download/filet.apk!/"), at)
        at = MountBoundary.up(at!!)
        assertEquals("and only now does it leave", VPath("local", "/storage/Download"), at)
    }

    @Test
    fun `the same holds for a zip`() {
        assertEquals(VPath("zip", "/storage/Download/site.zip!/assets"), MountBoundary.up(ZIP))
    }

    // ── crossing out of it ──

    @Test
    fun `up from a container root lands on the folder the file sits in, on the real filesystem`() {
        val root = VPath("apk", "/storage/Download/filet.apk!/")
        val out = MountBoundary.up(root)
        assertEquals(VPath("local", "/storage/Download"), out)
        assertEquals("the scheme has to change or nothing can list it", "local", out?.scheme)
    }

    @Test
    fun `it never produces an archive scheme pointing at a directory`() {
        // The precise shape of the bug: apk:///storage/Download, which no provider can read.
        val root = VPath("zip", "/storage/site.zip!/")
        val out = MountBoundary.up(root)!!
        assertFalse(
            "produced $out, which is the broken path this fixes",
            out.scheme in MountBoundary.MOUNTED_SCHEMES && !out.path.contains('!'),
        )
    }

    @Test
    fun `a container at the filesystem root still has somewhere to go`() {
        assertEquals(VPath("local", "/"), MountBoundary.up(VPath("zip", "/a.zip!/")))
    }

    @Test
    fun `the host scheme is named rather than assumed`() {
        // A mount of a file that did not come from local storage must not come back local.
        assertEquals(
            VPath("smb", "/share/docs"),
            MountBoundary.up(VPath("zip", "/share/docs/pack.zip!/"), hostScheme = "smb"),
        )
    }

    // ── everything else is untouched ──

    @Test
    fun `an ordinary path is still an ordinary parent walk`() {
        assertEquals(VPath("local", "/storage/Download"), MountBoundary.up(VPath("local", "/storage/Download/a")))
        assertNull("nothing above a root", MountBoundary.up(VPath("local", "/")))
    }

    @Test
    fun `a path in a mounted scheme with no boundary is not treated as a mount`() {
        // Defensive: the scheme alone is not enough, the separator has to be there too.
        assertFalse(MountBoundary.isMounted(VPath("zip", "/storage/Download")))
        assertEquals(VPath("zip", "/storage"), MountBoundary.up(VPath("zip", "/storage/Download")))
    }

    // ── the helpers the router uses ──

    @Test
    fun `a container root is recognised as one`() {
        assertTrue(MountBoundary.isContainerRoot(VPath("apk", "/a/b.apk!/")))
        assertTrue(MountBoundary.isContainerRoot(VPath("apk", "/a/b.apk!")))
        assertFalse(MountBoundary.isContainerRoot(APK))
    }

    @Test
    fun `split separates the container file from the path inside it`() {
        assertEquals("/storage/Download/filet.apk" to "com/example/ui", MountBoundary.split(APK))
        assertNull(MountBoundary.split(VPath("local", "/storage/Download")))
    }

    @Test
    fun `every mounted scheme is covered, so a new one cannot quietly miss out`() {
        for (scheme in MountBoundary.MOUNTED_SCHEMES) {
            val root = VPath(scheme, "/storage/thing!/")
            assertTrue(scheme, MountBoundary.isContainerRoot(root))
            assertEquals(scheme, "local", MountBoundary.up(root)?.scheme)
        }
    }
}
