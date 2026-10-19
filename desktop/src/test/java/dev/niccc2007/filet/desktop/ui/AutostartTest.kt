package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.Autostart
import dev.niccc2007.filet.desktop.mount.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "Start with this computer" writes, and where, per OS. Never a service on any of them. */
class AutostartTest {
    private val win = "C:\\Program Files\\Filet Desktop\\Filet Desktop.exe"

    @Test
    fun `entry path per os`() {
        assertEquals("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\\Filet Desktop",
            Autostart.entryPath(Platform.WINDOWS, "C:\\Users\\a"))
        assertEquals("/home/a/.config/autostart/filet-desktop.desktop", Autostart.entryPath(Platform.LINUX, "/home/a"))
        assertEquals("/x/autostart/filet-desktop.desktop", Autostart.entryPath(Platform.LINUX, "/home/a", "/x"))
        assertEquals("/home/a/.config/autostart/filet-desktop.desktop", Autostart.entryPath(Platform.LINUX, "/home/a", " "))
        assertEquals("/Users/a/Library/LaunchAgents/dev.niccc2007.filet.desktop.plist", Autostart.entryPath(Platform.MAC, "/Users/a"))
        assertNull(Autostart.entryPath(Platform.OTHER, "/"))
    }

    @Test
    fun `windows run value quotes a path with spaces`() =
        assertEquals("\"$win\" --minimized", Autostart.entryContent(Platform.WINDOWS, win))

    @Test
    fun `linux entry is an xdg autostart application`() {
        val c = Autostart.entryContent(Platform.LINUX, "/opt/filet-desktop/bin/filet-desktop")!!
        assertTrue(c.startsWith("[Desktop Entry]"))
        assertTrue(c.contains("Exec=\"/opt/filet-desktop/bin/filet-desktop\" --minimized"))
        assertTrue(c.contains("Type=Application"))
    }

    @Test
    fun `mac entry is a run-at-load launch agent with the path escaped`() {
        val c = Autostart.entryContent(Platform.MAC, "/Applications/A&B.app/Contents/MacOS/A&B")!!
        assertTrue(c.contains("<key>RunAtLoad</key><true/>"))
        assertTrue(c.contains("<string>/Applications/A&amp;B.app/Contents/MacOS/A&amp;B</string>"))
    }

    @Test
    fun `an entry for another install path reads as off`() {
        assertTrue(Autostart.matches(Platform.WINDOWS, "\"$win\" --minimized", win))
        assertFalse(Autostart.matches(Platform.WINDOWS, "\"D:\\old\\Filet Desktop.exe\" --minimized", win))
        assertFalse(Autostart.matches(Platform.WINDOWS, null, win))
        val lin = Autostart.entryContent(Platform.LINUX, "/opt/f")!!
        assertTrue(Autostart.matches(Platform.LINUX, lin, "/opt/f"))
        assertFalse(Autostart.matches(Platform.LINUX, lin, "/usr/local/g"))
    }
}
