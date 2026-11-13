package dev.niccc2007.filet.desktop.update

import dev.niccc2007.filet.desktop.Autostart
import dev.niccc2007.filet.desktop.mount.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InstallScriptTest {

    private val msi = "C:\\Users\\O'Neil\\AppData\\Local\\Temp\\filet-desktop-network-tool-update\\Filet-Desktop-Network-Tool-9.9.9.msi"
    private val log = "C:\\Users\\O'Neil\\AppData\\Local\\Temp\\filet-desktop-network-tool-update\\install.log"
    private val app = "C:\\Users\\O'Neil\\AppData\\Local\\Filet Desktop Network Tool\\Filet Desktop Network Tool.exe"

    @Test
    fun `paths with spaces and quotes reach msiexec and the launcher whole`() {
        val s = InstallScript.build(listOf(4242L, 4240L), msi, log, app, share = false)
        // Single-quoted literals with the quote doubled; the MSI path also double-quoted for msiexec.
        assertTrue(s.contains("'\"C:\\Users\\O''Neil\\AppData\\Local\\Temp\\filet-desktop-network-tool-update\\Filet-Desktop-Network-Tool-9.9.9.msi\"'"))
        assertTrue(s.contains("Start-Process -FilePath 'C:\\Users\\O''Neil\\AppData\\Local\\Filet Desktop Network Tool\\Filet Desktop Network Tool.exe'"))
        assertTrue(s.contains("Wait-Process -Id 4242,4240 -Timeout 90"))
        assertTrue(s.contains("if (Get-Process -Id 4242,4240) { exit 1 }"))
        assertTrue(s.contains("'/qn'"))
        assertFalse(s.contains("--share"))
    }

    @Test
    fun `sharing comes back only when it was on`() {
        assertTrue(InstallScript.build(listOf(1L), msi, log, app, share = true).contains("@('--share') + \$a"))
    }

    @Test
    fun `the command is carried as UTF-16LE base64, as -EncodedCommand reads it`() {
        val s = InstallScript.build(listOf(1L), msi, log, app, share = true)
        assertEquals(s, String(Base64.getDecoder().decode(InstallScript.encode(s)), Charsets.UTF_16LE))
    }

    // ── the login entry after the install moved ──

    private val now = "C:\\Users\\me\\AppData\\Local\\Filet Desktop Network Tool\\Filet Desktop Network Tool.exe"
    private val old = "C:\\Program Files\\Filet Desktop\\Filet Desktop.exe"
    private val beforeRename = "C:\\Users\\me\\AppData\\Local\\Filet Desktop\\Filet Desktop.exe"

    @Test
    fun `a login entry for a removed install is stale and is pointed at this one`() {
        assertTrue(Autostart.stale(Platform.WINDOWS, "\"$old\" --minimized", now) { false })
        // The rename moved the install: the old name's launcher, gone, is this tool's too.
        assertTrue(Autostart.stale(Platform.WINDOWS, "\"$beforeRename\" --minimized", now) { false })
    }

    @Test
    fun `an entry for this install, another live install, or another program is left alone`() {
        assertFalse(Autostart.stale(Platform.WINDOWS, "\"$now\" --minimized", now) { false })
        assertFalse(Autostart.stale(Platform.WINDOWS, "\"$old\" --minimized", now) { true })
        assertFalse(Autostart.stale(Platform.WINDOWS, "\"C:\\Tools\\other.exe\" --minimized", now) { false })
        assertFalse(Autostart.stale(Platform.WINDOWS, null, now) { false })
        assertFalse(Autostart.stale(Platform.LINUX, "\"$old\" --minimized", now) { false })
    }
}
