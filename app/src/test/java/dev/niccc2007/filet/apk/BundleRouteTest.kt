package dev.niccc2007.filet.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reaching the installer at all.
 *
 * Bug identified: installing a split bundle was built, tested, and unreachable. The tap router
 * matched `extension == "apk"` and nothing else, so `.xapk`, `.apkm` and `.apks` fell through
 * to the archive branch - they are zips, so the archive table claims them - and mounted as
 * folders. The only door into the installer was the APK inspector, which a bundle could never
 * get to.
 *
 * The previous round's evidence was about the installer and was correct about it. It proved
 * nothing about whether anybody could press the button, which is what actually mattered.
 */
class BundleRouteTest {

    // ── what can be installed ──

    @Test
    fun `the three bundle formats are installable, and so is a plain apk`() {
        assertTrue(BundleRoute.installable("app.apk"))
        assertTrue(BundleRoute.installable("game.xapk"))
        assertTrue(BundleRoute.installable("thing.apkm"))
        assertTrue(BundleRoute.installable("bundle.apks"))
    }

    @Test
    fun `nothing else is`() {
        // Absent rather than blocked: "Install" on a photo is not an action that is
        // unavailable, it is one that makes no sense.
        for (name in listOf("photo.jpg", "notes.txt", "archive.zip", "book.epub", "lib.jar", "movie.mp4")) {
            assertFalse(name, BundleRoute.installable(name))
        }
    }

    @Test
    fun `the check survives untidy names`() {
        assertTrue(BundleRoute.installable("Some.Game.v2.3.1.XAPK"))
        assertTrue(BundleRoute.installable("com.example.app-arm64.apkm"))
        assertFalse("a file merely mentioning apk is not one", BundleRoute.installable("apk-notes.txt"))
        assertFalse("no extension at all", BundleRoute.installable("apk"))
    }

    // ── where a tap goes ──

    @Test
    fun `a plain apk opens the inspector, which is where its Install lives`() {
        assertEquals(BundleRoute.Tap.INSPECT, BundleRoute.tap("app.apk", isDir = false))
    }

    @Test
    fun `a bundle opens where an app opens, not as a zip`() {
        // Reported: tapping an xapk opens it as an archive instead of an overview for
        // installing or managing it like a normal apk. It is an app, so it goes where apps go.
        // The earlier reasoning - that the inspector reads one manifest and a bundle has
        // several - only holds for the splits; the base carries everything shown.
        for (name in listOf("game.xapk", "thing.apkm", "bundle.apks")) {
            assertEquals(name, BundleRoute.Tap.INSPECT, BundleRoute.tap(name, isDir = false))
        }
    }

    @Test
    fun `a bundle and a plain apk go to the same place`() {
        // The point of the fix: there is no second class of app package.
        assertEquals(
            BundleRoute.tap("app.apk", isDir = false),
            BundleRoute.tap("app.xapk", isDir = false),
        )
    }

    @Test
    fun `an ordinary file is left alone`() {
        assertEquals(BundleRoute.Tap.OTHER, BundleRoute.tap("photo.jpg", isDir = false))
        assertEquals(BundleRoute.Tap.OTHER, BundleRoute.tap("Downloads", isDir = true))
    }

    @Test
    fun `a folder named like a package is still a folder`() {
        // A directory called `something.apk` exists more often than it should.
        assertEquals(BundleRoute.Tap.OTHER, BundleRoute.tap("weird.apk", isDir = true))
    }

    // ── the two questions are separate, which is the whole point ──

    @Test
    fun `where a tap goes and whether it installs are independent answers`() {
        // Conflating them is what caused the original hole: the router decided both at once,
        // so anything that opened as an archive silently lost its Install.
        val bundle = "game.xapk"
        assertEquals(BundleRoute.Tap.INSPECT, BundleRoute.tap(bundle, isDir = false))
        assertTrue("where it opens must not decide whether it installs", BundleRoute.installable(bundle))
    }

    @Test
    fun `every bundle extension is covered by both answers`() {
        // A format added to the set without being given a route would go back to falling
        // through to the archive branch unnoticed, which is exactly how this happened.
        for (ext in BundleRoute.BUNDLE_EXTENSIONS) {
            val name = "sample.$ext"
            assertTrue(name, BundleRoute.installable(name))
            assertEquals(name, BundleRoute.Tap.INSPECT, BundleRoute.tap(name, isDir = false))
        }
    }
}
