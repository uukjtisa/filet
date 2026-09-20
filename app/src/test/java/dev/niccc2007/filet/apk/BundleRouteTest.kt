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
    fun `a bundle opens the archive browser, and that is deliberate`() {
        // Looking inside one is reasonable, and the inspector reads a single manifest while a
        // bundle has several. A bundle inspector is later work; until then Install is reached
        // from the menu instead of not at all.
        for (name in listOf("game.xapk", "thing.apkm", "bundle.apks")) {
            assertEquals(name, BundleRoute.Tap.MOUNT, BundleRoute.tap(name, isDir = false))
        }
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
    fun `mounting and installing are independent answers`() {
        // Conflating them is what caused the hole: the router decided both at once, so
        // anything that opened as an archive silently lost its Install.
        val bundle = "game.xapk"
        assertEquals(BundleRoute.Tap.MOUNT, BundleRoute.tap(bundle, isDir = false))
        assertTrue("mounting it must not cost it the Install action", BundleRoute.installable(bundle))
    }

    @Test
    fun `every bundle extension is covered by both answers`() {
        // A format added to the set without being given a route would go back to falling
        // through to the archive branch unnoticed, which is exactly how this happened.
        for (ext in BundleRoute.BUNDLE_EXTENSIONS) {
            val name = "sample.$ext"
            assertTrue(name, BundleRoute.installable(name))
            assertEquals(name, BundleRoute.Tap.MOUNT, BundleRoute.tap(name, isDir = false))
        }
    }
}
