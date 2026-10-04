package dev.niccc2007.filet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTagsTest {

    @Test
    fun `the app's own tags are recognised in every shape they get written`() {
        for (tag in listOf("v0.1.11", "0.1.11", "v1.0.0", "v0.2", "v1.0.0-rc.2", "v2.0.0+build7")) {
            assertEquals(tag, ReleaseTags.Line.APP, ReleaseTags.lineOf(tag))
            assertTrue(tag, ReleaseTags.isApp(tag))
        }
    }

    @Test
    fun `the desktop tool's tags are a line of their own`() {
        for (tag in listOf(
            "filet-desktop-mount-tool-v0.1.0",
            "filet-desktop-mount-tool-v1.2.3",
            "filet-desktop-mount-tool-v1.0.0-beta.1",
        )) {
            assertEquals(tag, ReleaseTags.Line.DESKTOP, ReleaseTags.lineOf(tag))
            assertFalse(tag, ReleaseTags.isApp(tag))
        }
    }

    @Test
    fun `a desktop tag never wins the phone's update check`() {
        // The whole reason this file exists. Version.parse SEARCHES rather than matches, so the
        // version inside a desktop tag is a perfectly good 0.2.0 - higher than the app's 0.1.11,
        // and attached to a release with a JAR in it and no APK.
        assertTrue(Version.parse("filet-desktop-mount-tool-v0.2.0") > Version.parse("v0.1.11"))
        assertFalse(ReleaseTags.isApp("filet-desktop-mount-tool-v0.2.0"))
    }

    @Test
    fun `a tag of no known shape is unknown rather than quietly an app tag`() {
        // Reported rather than ignored: a typo'd tag that silently classifies as neither is a
        // release nobody can find and nothing complains about.
        for (tag in listOf("nightly", "filet-v0.1.0", "desktop-v0.1.0", "v", "", "0.1.x")) {
            assertEquals(tag, ReleaseTags.Line.UNKNOWN, ReleaseTags.lineOf(tag))
        }
    }

    @Test
    fun `the desktop tag is built in one place`() {
        assertEquals("filet-desktop-mount-tool-v0.1.0", ReleaseTags.desktopTag("0.1.0"))
        assertEquals("filet-desktop-mount-tool-v0.1.0", ReleaseTags.desktopTag("v0.1.0"))
        assertTrue(ReleaseTags.isDesktop(ReleaseTags.desktopTag("3.4.5")))
    }
}
