package dev.niccc2007.filet.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the pieces of a split bundle.
 *
 * Every assertion here is a failure that does not appear at install time. Picking the wrong
 * architecture installs cleanly and crashes on launch; dropping the density split installs
 * cleanly and renders with no drawables; dropping a feature module installs cleanly and breaks
 * one screen. The install succeeding proves nothing, which is why the choice is tested here
 * instead of being trusted on a device.
 */
class SplitPackageTest {

    private val bundle = listOf(
        "base.apk",
        "split_config.arm64_v8a.apk",
        "split_config.armeabi_v7a.apk",
        "split_config.x86_64.apk",
        "split_config.hdpi.apk",
        "split_config.xxhdpi.apk",
        "split_config.xxxhdpi.apk",
        "split_config.en.apk",
        "split_config.tl.apk",
        "split_config.es.apk",
        "split_dynamic_feature.apk",
        "manifest.json",
        "icon.png",
    )

    // ── the format is recognised at all ──

    @Test
    fun `the three bundle formats need a session install and a plain apk does not`() {
        // This is the whole reason these three could not be installed: they were treated as
        // zips to browse, and handed to the system installer, which cannot take a bundle.
        assertTrue(SplitPackage.isSplitBundle("app.xapk"))
        assertTrue(SplitPackage.isSplitBundle("app.apkm"))
        assertTrue(SplitPackage.isSplitBundle("app.apks"))
        assertFalse(SplitPackage.isSplitBundle("app.apk"))
        assertFalse(SplitPackage.isSplitBundle("photos.zip"))
    }

    @Test
    fun `the check is case insensitive, because downloads are not tidy`() {
        assertTrue(SplitPackage.isSplitBundle("Some.App.v3.XAPK"))
    }

    // ── the architecture, which is the one that must be exact ──

    @Test
    fun `the device's preferred architecture wins`() {
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a", "armeabi-v7a"), "xxhdpi", listOf("en"))
        assertTrue(picked.contains("split_config.arm64_v8a.apk"))
        assertFalse("a second architecture is dead weight at best", picked.contains("split_config.armeabi_v7a.apk"))
        assertFalse(picked.contains("split_config.x86_64.apk"))
    }

    @Test
    fun `a 32-bit device takes the 32-bit split and never the 64-bit one`() {
        // The failure this prevents: arm64 libraries on an armeabi-v7a device install happily
        // and the app dies on its first native call.
        val picked = SplitPackage.pick(bundle, listOf("armeabi-v7a", "armeabi"), "hdpi", listOf("en"))
        assertTrue(picked.contains("split_config.armeabi_v7a.apk"))
        assertFalse(picked.contains("split_config.arm64_v8a.apk"))
    }

    @Test
    fun `preference order is honoured, not just membership`() {
        // A 64-bit device reports both. Taking "any match" would be satisfied by the 32-bit
        // split and would quietly give up every 64-bit optimisation in the app.
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), "xxhdpi", listOf("en"))
        assertTrue(picked.contains("split_config.arm64_v8a.apk"))
    }

    // ── density is nearest, never nothing ──

    @Test
    fun `the exact density is used when the bundle has it`() {
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertTrue(picked.contains("split_config.xxhdpi.apk"))
        assertFalse(picked.contains("split_config.hdpi.apk"))
    }

    @Test
    fun `a missing density falls to the nearest rather than being dropped`() {
        // An app installed with no density split has no drawables at all. Slightly wrong
        // drawables are not in the same category as none.
        val thin = listOf("base.apk", "split_config.hdpi.apk", "split_config.xxxhdpi.apk")
        val picked = SplitPackage.pick(thin, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertTrue("xxhdpi is one step from xxxhdpi and two from hdpi", picked.contains("split_config.xxxhdpi.apk"))
    }

    // ── language ──

    @Test
    fun `the device languages are installed and others are not`() {
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("en", "tl"))
        assertTrue(picked.contains("split_config.en.apk"))
        assertTrue(picked.contains("split_config.tl.apk"))
        assertFalse(picked.contains("split_config.es.apk"))
    }

    @Test
    fun `a language the bundle does not carry still leaves the app with strings`() {
        // Otherwise an app installs with no localisation split and comes up blank.
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("ja"))
        assertTrue("fall back to English rather than to nothing", picked.contains("split_config.en.apk"))
    }

    // ── the base, and anything not understood ──

    @Test
    fun `the base is always first, because the session has to start with it`() {
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertEquals("base.apk", picked.first())
    }

    @Test
    fun `feature modules are installed rather than silently discarded`() {
        // The conservative direction. A few unnecessary kilobytes beats an app with a screen
        // that crashes because its module was left out of the session.
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertTrue(picked.contains("split_dynamic_feature.apk"))
    }

    @Test
    fun `bundle metadata is never written into the session`() {
        val picked = SplitPackage.pick(bundle, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertFalse(picked.contains("manifest.json"))
        assertFalse(picked.contains("icon.png"))
    }

    @Test
    fun `an apks holding only one apk still installs`() {
        // The degenerate bundle - a single universal APK in a wrapper, which is most of what a
        // "universal" download from APKMirror actually is.
        val one = listOf("base.apk", "manifest.json")
        assertEquals(listOf("base.apk"), SplitPackage.pick(one, listOf("arm64-v8a"), "xxhdpi", listOf("en")))
    }

    @Test
    fun `a zip with no apk inside produces nothing rather than a broken session`() {
        assertEquals(emptyList<String>(), SplitPackage.pick(listOf("readme.txt"), listOf("arm64-v8a"), "xxhdpi", listOf("en")))
    }

    @Test
    fun `the other naming convention in the wild is understood`() {
        // APKMirror writes "<package>.config.arm64_v8a.apk" rather than "split_config.".
        val alt = listOf("com.example.app.apk", "com.example.app.config.arm64_v8a.apk", "com.example.app.config.xxhdpi.apk")
        val picked = SplitPackage.pick(alt, listOf("arm64-v8a"), "xxhdpi", listOf("en"))
        assertEquals("com.example.app.apk", picked.first())
        assertTrue(picked.contains("com.example.app.config.arm64_v8a.apk"))
        assertTrue(picked.contains("com.example.app.config.xxhdpi.apk"))
    }

    @Test
    fun `an OBB beside the apks is reported, since it has to be placed by hand`() {
        val withObb = bundle + "Android/obb/com.example.app/main.1.com.example.app.obb"
        assertTrue(SplitPackage.extras(withObb).any { it.endsWith(".obb") })
        assertFalse("metadata is not an extra", SplitPackage.extras(withObb).any { it.endsWith("manifest.json") })
    }
}
