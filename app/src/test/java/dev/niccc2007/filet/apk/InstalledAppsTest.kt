package dev.niccc2007.filet.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsTest {

    private fun app(
        label: String = "Some App",
        versionName: String = "1.2",
        versionCode: Long = 12,
        splits: List<String> = emptyList(),
    ) = InstalledApp(
        packageName = "com.example",
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        apkPath = "/data/app/base.apk",
        splitPaths = splits,
        system = false,
        sizeBytes = 100,
    )

    @Test
    fun `an ordinary name survives intact`() {
        assertEquals("Some App-1.2.apk", InstalledApps.fileNameFor(app()))
    }

    @Test
    fun `a separator in the label cannot escape the folder`() {
        // An app's label is arbitrary text chosen by its author. A slash in it writes outside
        // the destination, which is the whole reason this is a function and not a template.
        //
        // The property is no SEPARATORS and no traversal component - not the absence of dots.
        // Once the slashes are gone the result is a single filename, and dots inside a filename
        // traverse nothing. An earlier version of this banned ".." anywhere, which asserted
        // something stricter than the thing that actually keeps the write inside the folder.
        for (label in listOf("../../etc/pass", "..\\..\\windows", "a/b/c")) {
            val name = InstalledApps.fileNameFor(app(label = label))
            assertFalse(name, name.contains('/'))
            assertFalse(name, name.contains('\\'))
            assertFalse(name, name.removeSuffix(".apk") == "..")
            assertFalse(name, name.removeSuffix(".apk") == ".")
        }
    }

    @Test
    fun `a label that is exactly a traversal component becomes a name`() {
        for (label in listOf("..", ".", "....")) {
            val out = InstalledApps.sanitise(label)
            assertFalse(out, out == "." || out == ".." || out.isBlank())
        }
    }

    @Test
    fun `control characters and separators are replaced, not dropped silently`() {
        for (bad in listOf("a/b", "a\\b", "a\u0000b", "a\nb", "a:b", "a*b", "a?b")) {
            val out = InstalledApps.sanitise(bad)
            assertEquals("$bad kept its length", 3, out.length)
            assertTrue("$bad -> $out", out.startsWith("a") && out.endsWith("b"))
        }
    }

    @Test
    fun `a label that sanitises to nothing still gets a name`() {
        // An all-emoji label is legal and common. Empty here would write ".apk", which the next
        // extraction silently overwrites.
        for (raw in listOf("", "   ", "...", "\u0000\u0000")) {
            assertTrue(InstalledApps.sanitise(raw).isNotBlank())
        }
        assertTrue(InstalledApps.fileNameFor(app(label = "")).endsWith(".apk"))
        assertTrue(InstalledApps.fileNameFor(app(label = "")).length > ".apk".length)
    }

    @Test
    fun `a missing version name falls back to the code rather than dangling`() {
        assertEquals("Some App-12.apk", InstalledApps.fileNameFor(app(versionName = "")))
    }

    @Test
    fun `a very long label is trimmed`() {
        val out = InstalledApps.sanitise("x".repeat(500))
        assertTrue(out.length <= 80)
    }

    @Test
    fun `a split app is described as one and counts all its parts`() {
        val a = app(splits = listOf("/data/app/split_a.apk", "/data/app/split_b.apk"))
        assertTrue(a.split)
        assertEquals(3, a.parts)
        // A folder, not a file: the base alone deliberately lacks the density and ABI
        // resources, so extracting only it produces something that fails at install time.
        assertFalse(InstalledApps.folderNameFor(a).endsWith(".apk"))
    }

    @Test
    fun `an ordinary app is not a split`() {
        assertFalse(app().split)
        assertEquals(1, app().parts)
    }

    @Test
    fun `the folder is inside shared storage and names the app`() {
        assertTrue(InstalledApps.FOLDER.startsWith("Filet/"))
        assertFalse(InstalledApps.FOLDER.startsWith("/"))
    }
}
