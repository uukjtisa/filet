package dev.niccc2007.filet.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the tool remembers between runs.
 *
 * [deviceId] is the TXT `id` this PC advertises: minted once and kept, so the same PC on another
 * network is recognisable as the same PC rather than a second saved entry on the phone. It is
 * also how discovery recognises its own announcement coming back.
 */
data class Config(
    val shares: List<Share> = emptyList(),
    val account: Account,
    val port: Int = DEFAULT_PORT,
    val autostart: Boolean = false,
    val deviceId: String,
    /** The LAN address picked by hand when the PC has several, or null to choose automatically. */
    val host: String? = null,
) {
    companion object {
        /**
         * 8322 and not the phone's 8321. A PC that also runs an Android emulator, or forwards a
         * phone's port over adb, would otherwise lose the preferred port to it and the endpoint
         * would land somewhere random.
         */
        const val DEFAULT_PORT = 8322

        fun fresh(): Config = Config(account = Account(Account.generate()), deviceId = UUID.randomUUID().toString())
    }
}

/**
 * The file format, explicit rather than reflective so a renamed field cannot silently drop a
 * stored share, and every read has a default.
 */
object ConfigCodec {

    fun encode(c: Config): String = JSONObject().apply {
        put("version", 1)
        put("port", c.port)
        put("autostart", c.autostart)
        put("deviceId", c.deviceId)
        put("password", c.account.password)
        c.host?.let { put("host", it) }
        put("shares", JSONArray().apply {
            for (s in c.shares) put(JSONObject().apply {
                put("path", s.path)
                put("name", s.name)
                put("writable", s.writable)
                put("tag", s.tag)
                if (s.code.isNotBlank()) put("code", s.code)
            })
        })
    }.toString(2)

    /**
     * Read [raw], filling anything missing or untrustworthy from [fresh].
     *
     * A share that fails the drive-root guard is DROPPED on load, not served: a hand-edited file
     * is still not allowed to publish `C:\`. Unparseable input yields a fresh config rather than
     * an exception, because a corrupt file must not stop the tool from starting at all - but the
     * caller is told via [Decoded.repaired] so it saves the repaired copy instead of re-minting a
     * password on every launch.
     */
    fun decode(raw: String?, fresh: () -> Config = { Config.fresh() }): Decoded {
        val base = fresh()
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Decoded(base, true)
        var repaired = false
        val password = o.optString("password").takeIf { Account.valid(it) }
            ?: base.account.password.also { repaired = true }
        val id = o.optString("deviceId").takeIf { it.isNotBlank() } ?: base.deviceId.also { repaired = true }
        val port = o.optInt("port", Config.DEFAULT_PORT).takeIf { it in 1..65535 }
            ?: Config.DEFAULT_PORT.also { repaired = true }
        val arr = o.optJSONArray("shares") ?: JSONArray()
        val read = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = s.optString("path")
            if (ShareGuard.refusal(path) != null) { repaired = true; return@mapNotNull null }
            Share(
                path = path,
                name = s.optString("name").ifBlank { path.replace('\\', '/').trimEnd('/').substringAfterLast('/') },
                writable = s.optBoolean("writable", false),
                tag = s.optString("tag"),
                // A hand-set code that would not pass the setter is dropped back to the
                // account's, rather than served: it may collide or be unreadable.
                code = s.optString("code").let { c -> if (c.isEmpty() || Account.valid(c)) c else "".also { repaired = true } },
            )
        }
        val shares = Shares.withTags(read)
        if (shares != read) repaired = true
        return Decoded(
            Config(
                shares, Account(password), port, o.optBoolean("autostart", false), id,
                host = o.optString("host").takeIf { it.isNotBlank() },
            ),
            repaired,
        )
    }

    data class Decoded(val config: Config, val repaired: Boolean)
}

/**
 * Where the config lives, per OS, and how it is written.
 *
 * - **Windows:** `%LOCALAPPDATA%\Filet Desktop\config.json`. Local, not roaming: shares name
 *   folders on THIS machine's disks, and a roaming profile would carry `D:\Pictures` to a PC
 *   where it means nothing or something else.
 * - **macOS:** `~/Library/Application Support/Filet Desktop/config.json`, the platform's place.
 * - **Linux and the rest:** `$XDG_CONFIG_HOME/filet-desktop/config.json`, else `~/.config/...`.
 *
 * A JSON file and not `java.util.prefs`. On Windows Preferences writes to the registry with
 * escaped key names and an 8 KB value ceiling, which a share list can exceed, and it cannot be
 * read, backed up or deleted by anyone without a registry editor. The file holds the password,
 * which the window shows openly anyway; on POSIX it is still created owner-only.
 */
class ConfigStore(val file: Path = defaultFile()) {

    @Synchronized
    fun load(): Config {
        val raw = runCatching { Files.readString(file) }.getOrNull()
        val decoded = ConfigCodec.decode(raw)
        if (decoded.repaired) save(decoded.config)
        return decoded.config
    }

    /**
     * Write to a sibling and move it into place - truncate last.
     *
     * Writing the file in place truncates it first, so a crash or a full disk mid-write leaves an
     * empty config, which reloads as a fresh one with a NEW password and every phone's saved
     * connection silently stops working.
     */
    @Synchronized
    fun save(c: Config) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = dir.resolve(file.fileName.toString() + ".part")
        Files.writeString(tmp, ConfigCodec.encode(c))
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultFile(): Path = configDir(
            System.getProperty("os.name").orEmpty(),
            System::getenv,
            System.getProperty("user.home").orEmpty(),
        ).resolve("config.json")

        /** Pure, so each OS's answer is testable on any one OS. */
        fun configDir(os: String, env: (String) -> String?, home: String): Path {
            val name = os.lowercase()
            return when {
                name.startsWith("windows") ->
                    Paths.get(env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Local", "Filet Desktop")
                name.startsWith("mac") || name.contains("darwin") ->
                    Paths.get(home, "Library", "Application Support", "Filet Desktop")
                else ->
                    Paths.get(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "filet-desktop")
            }
        }
    }
}
