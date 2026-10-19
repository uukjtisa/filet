package dev.niccc2007.filet.desktop.ui

import java.util.concurrent.TimeUnit

/**
 * Whether Windows will let a phone find and reach this PC at all.
 *
 * Bug identified here: the PC never appeared on the phone, with nothing wrong in Filet. Windows
 * files every network as Public or Private, and a Wi-Fi joined without answering its "make this
 * PC discoverable" question is Public. The inbound rule Windows creates the first time a program
 * listens is scoped to the profile in force at that moment - Private, on a machine set up on a
 * wired network - so on a Public Wi-Fi the multicast announcement replies and the share port are
 * both dropped by the firewall. The tool advertises and serves correctly and nothing arrives. The
 * window now says so and names the network, because there is no other place this shows up.
 *
 * Read through `Get-NetConnectionProfile`, as numbers - `NetworkCategory` is 0 Public, 1 Private,
 * 2 Domain - so the reading is the same on a Windows in any language.
 */
object NetCheck {

    data class Profile(val alias: String, val category: Int) {
        val public: Boolean get() = category == 0
    }

    /** `Wi-Fi|0` per line, as printed by [read]'s command. Pure. */
    fun parse(text: String): List<Profile> = text.lineSequence().mapNotNull { l ->
        val alias = l.substringBeforeLast('|', "").trim()
        val cat = l.substringAfterLast('|', "").trim().toIntOrNull()
        if (alias.isEmpty() || cat == null) null else Profile(alias, cat)
    }.toList()

    /** The sentence for the window, or null when every connected network is Private or Domain. Pure. */
    fun warning(profiles: List<Profile>): String? {
        val pub = profiles.filter { it.public }.map { it.alias }
        if (pub.isEmpty()) return null
        return "Windows has ${pub.joinToString(" and ")} set as a Public network, so it hides this PC from phones " +
            "and blocks its shares. If this is your home network, set it to Private: Network settings, open " +
            "that network, choose Private network."
    }

    /** Windows only. Empty when it cannot be read, which shows no warning rather than a wrong one. */
    fun read(): List<Profile> = runCatching {
        val p = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-NetConnectionProfile | ForEach-Object { \$_.InterfaceAlias + '|' + [int]\$_.NetworkCategory }",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return emptyList() }
        parse(out)
    }.getOrDefault(emptyList())

    /** Opens Windows' own page for the setting. */
    fun openSettings() {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:network-status")) }
    }
}
