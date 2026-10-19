package dev.niccc2007.filet.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dev.niccc2007.filet.desktop.mount.Platform
import java.io.File

/**
 * "Start with this computer", as a checkbox and never as a service.
 *
 * Decision: a per-user login entry on every OS. A service starts before anyone logs in, has no
 * window to explain itself and no obvious way to stop, and on Windows it runs as a different
 * account that cannot see the user's mapped drives - so it would share folders nobody can see
 * being shared and mount drives into a session nobody is in. What each OS gets:
 *
 *  - Windows: a value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
 *  - Linux: an XDG autostart entry, `~/.config/autostart/filet-desktop.desktop`. Not a systemd
 *    user unit: those start before the graphical session exists, and a window needs one.
 *  - macOS: a LaunchAgent plist in `~/Library/LaunchAgents`, loaded by launchd at next login.
 *
 * The checkbox reads the entry back from the OS rather than from a saved preference, so it can
 * never show as on when nothing was written - or when the entry points at an older install.
 */
object Autostart {
    const val NAME = "Filet Desktop"
    const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    const val LINUX_FILE = "filet-desktop.desktop"
    const val MAC_LABEL = "dev.niccc2007.filet.desktop"
    const val FLAG = "--minimized"

    /**
     * The installed launcher, or null when running from a build.
     *
     * `jpackage` launchers set `jpackage.app-path`. A Gradle `run` has only `java` with a classpath
     * that disappears on the next clean, and writing that into a login entry produces one that
     * fails silently at every login - so with no launcher the checkbox is disabled and says why.
     */
    fun launcher(): String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    /** Where the entry lives on [platform], for [home] and an optional `XDG_CONFIG_HOME`. Pure. */
    fun entryPath(platform: Platform, home: String, xdgConfig: String? = null): String? = when (platform) {
        Platform.WINDOWS -> "HKEY_CURRENT_USER\\$RUN_KEY\\$NAME"
        Platform.LINUX -> (xdgConfig?.takeIf { it.isNotBlank() } ?: "$home/.config") + "/autostart/$LINUX_FILE"
        Platform.MAC -> "$home/Library/LaunchAgents/$MAC_LABEL.plist"
        Platform.OTHER -> null
    }

    /** What gets written for [exe]. Pure, so each form is tested. */
    fun entryContent(platform: Platform, exe: String): String? = when (platform) {
        // Quoted: the install path has a space in it, and an unquoted Run value with a space is
        // read as a program and arguments - `C:\Program` - and silently does nothing.
        Platform.WINDOWS -> "\"$exe\" $FLAG"
        Platform.LINUX -> buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=$NAME")
            appendLine("Exec=\"${exe.replace("\"", "\\\"")}\" $FLAG")
            appendLine("X-GNOME-Autostart-enabled=true")
            appendLine("Terminal=false")
        }
        Platform.MAC -> """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$MAC_LABEL</string>
  <key>ProgramArguments</key><array><string>${xml(exe)}</string><string>$FLAG</string></array>
  <key>RunAtLoad</key><true/>
</dict>
</plist>
"""
        Platform.OTHER -> null
    }

    /** Whether [existing] launches [exe]. An entry for another install path reads as off. */
    fun matches(platform: Platform, existing: String?, exe: String): Boolean =
        existing != null && entryContent(platform, exe)?.let { want ->
            if (platform == Platform.WINDOWS) existing.trim() == want else existing.contains(if (platform == Platform.MAC) xml(exe) else exe)
        } == true

    /** Null when the checkbox can be used, or the reason it is disabled. */
    fun unavailable(platform: Platform = Platform.current): String? = when {
        platform == Platform.OTHER -> "Starting with the computer is not supported on this system."
        launcher() == null -> "Available in the installed app, not when run from a build."
        else -> null
    }

    fun isEnabled(platform: Platform = Platform.current): Boolean {
        val exe = launcher() ?: return false
        return matches(platform, read(platform), exe)
    }

    /** Writes or removes the entry, then reads it back. The return is what the OS now holds. */
    fun set(on: Boolean, platform: Platform = Platform.current): Boolean {
        val exe = launcher() ?: return false
        runCatching {
            when (platform) {
                Platform.WINDOWS ->
                    if (on) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, entryContent(platform, exe)!!)
                    else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME))
                        Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
                Platform.LINUX, Platform.MAC -> {
                    val f = File(path(platform) ?: return@runCatching)
                    if (on) { f.parentFile.mkdirs(); f.writeText(entryContent(platform, exe)!!) } else f.delete()
                }
                Platform.OTHER -> Unit
            }
            Unit
        }
        return isEnabled(platform)
    }

    private fun path(platform: Platform) =
        entryPath(platform, System.getProperty("user.home").orEmpty(), System.getenv("XDG_CONFIG_HOME"))

    private fun read(platform: Platform): String? = runCatching {
        when (platform) {
            Platform.WINDOWS ->
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME))
                    Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME) else null
            Platform.LINUX, Platform.MAC -> path(platform)?.let { File(it) }?.takeIf { it.isFile }?.readText()
            Platform.OTHER -> null
        }
    }.getOrNull()

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
