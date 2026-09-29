package dev.niccc2007.filet.vfs.provider.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class NetProtocol(val scheme: String, val defaultPort: Int, val label: String) {
    SMB("smb", 445, "SMB / Windows share"),
    SFTP("sftp", 22, "SFTP"),
    FTP("ftp", 21, "FTP"),
    WEBDAV("dav", 80, "WebDAV"),
}

/**
 * A saved remote.
 *
 * [id] is what appears in a VPath (`smb:///<id>/Documents/notes.txt`) rather than the host,
 * so renaming a server or changing its address does not invalidate every bookmark and
 * shortcut that points into it.
 */
data class NetConnection(
    val id: String,
    val protocol: NetProtocol,
    val label: String,
    /**
     * The address typed into the entry, and the one tried first when nothing is known to work.
     *
     * Kept as a single field rather than folded into [altHosts] so every existing caller keeps
     * working: this is still "the address of this remote" for anything that only needs one.
     */
    val host: String,

    /**
     * Other ways to reach the SAME device - a hotspot address, an mDNS name, a second network.
     *
     * A device is not its IP. The tablet on home Wi-Fi and the tablet on a phone hotspot is one
     * device with two routes, and making it two saved entries duplicates the credentials, the
     * storage card and the work of setting it up again. See [Endpoints] for the ordering.
     *
     * Some of these are typed and some are learned from discovery; they are not distinguished
     * here because by the time an address is in this list it is simply a route that may work.
     */
    val altHosts: List<String> = emptyList(),

    /**
     * The address that most recently answered.
     *
     * Stored rather than held in memory so a restart does not go back to sweeping: the network
     * a device was last reached on is almost always the network it is still on.
     */
    val lastGood: String = "",

    /**
     * The advertising device's stable id, when this remote was created from a discovered host.
     *
     * The key that makes learning an address safe. Matching on the access code instead would
     * mean two shares that happen to share a code could teach each other addresses, and Filet
     * would then send credentials to whichever answered - so an empty id learns nothing.
     */
    val deviceId: String = "",
    val port: Int,
    val user: String,
    val password: String,
    /** SMB share name, or the base path for WebDAV. Unused by SFTP and FTP. */
    val share: String = "",
    val domain: String = "",
    val useTls: Boolean = false,
    val anonymous: Boolean = false,

    /**
     * The folder inside the share to open at, rather than its root.
     *
     * A NAS whose root is fifteen department folders is a share you navigate past every single
     * time. Empty means the root, which is the old behaviour.
     */
    val startPath: String = "",

    /**
     * Refuse writes to this share from inside Filet.
     *
     * Not a security control - the server decides what is really permitted, and it is the only
     * thing that can. This is a guard against the accident: a drag that lands in the wrong pane
     * on a share holding the only copy of something.
     */
    val readOnly: Boolean = false,

    /**
     * FTP passive mode.
     *
     * On by default because a phone is behind NAT essentially always, and active mode asks the
     * server to open a connection back to it - which is the thing NAT exists to prevent. The
     * switch exists because some old servers only speak active.
     */
    val passive: Boolean = true,

    /** How long to wait on a connection before giving up. */
    val timeoutSeconds: Int = 15,

    /**
     * An OpenSSH or PEM private key for SFTP, in place of a password.
     *
     * Held with the password, under the same keystore-backed encryption, because it is the
     * same kind of secret and storing it anywhere weaker would undo the point of encrypting
     * the password at all.
     */
    val privateKey: String = "",

    /** Passphrase for [privateKey], if it has one. */
    val keyPassphrase: String = "",
)

/**
 * Saved remotes and their credentials.
 *
 * Passwords are encrypted with an **AES-GCM key held in the Android keystore**, so what lands
 * in shared preferences is ciphertext and the key itself is not extractable from the device.
 * Storing them in plaintext would be the single worst decision in this file, and "it is app-
 * private storage" stops being true the moment the device is rooted - which, for this app's
 * audience, is often.
 *
 * This is not a secret manager. It protects against casual disclosure, not against an
 * attacker who already has code execution as this app.
 */
class NetConnections(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("filet-net", Context.MODE_PRIVATE)

    fun all(): List<NetConnection> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { emptyList() }
    }

    fun byId(id: String): NetConnection? = all().firstOrNull { it.id == id }

    /**
     * Addresses to try for [c], best first. See [Endpoints] for the ordering.
     */
    fun candidates(c: NetConnection): List<String> =
        Endpoints.ordered(c.host, c.altHosts, c.lastGood.takeIf { it.isNotBlank() })

    /**
     * Remember that [host] answered.
     *
     * Written only when it CHANGES. A remote in steady use would otherwise rewrite the whole
     * connection store on every request, encrypting each password again on the way through.
     */
    fun noteGood(id: String, host: String) {
        val c = byId(id) ?: return
        if (c.lastGood.equals(host, ignoreCase = true)) return
        save(c.copy(lastGood = host))
    }

    /**
     * Record an address discovered for [id], if it is genuinely new.
     *
     * @return true when something was learned, which is also the signal not to write on every
     *   repeat sighting - discovery repeats constantly by design.
     */
    /**
     * Teach every remote that belongs to [deviceId] about an address it was seen at.
     *
     * @return true when anything was written, which is the signal not to act on the constant
     *   repeat sightings discovery produces by design.
     *
     * A blank [deviceId] learns nothing. An address is a place credentials get sent, so it is
     * added only when the far end proved which device it is.
     */
    fun learnForDevice(deviceId: String, host: String): Boolean {
        if (deviceId.isBlank() || host.isBlank()) return false
        var changed = false
        for (c in all()) {
            if (!c.deviceId.equals(deviceId, ignoreCase = true)) continue
            if (learnAddress(c.id, host)) changed = true
        }
        return changed
    }

    fun learnAddress(id: String, host: String): Boolean {
        val c = byId(id) ?: return false
        val known = listOf(c.host) + c.altHosts
        val grown = Endpoints.learn(known, host) ?: return false
        save(c.copy(altHosts = grown.drop(1)))
        return true
    }

    /** The fields [Sightings] compares a discovered host against. */
    private fun NetConnection.forMatching() =
        Sightings.Saved(id, deviceId, host, altHosts, port)

    /**
     * Write whatever a discovered host teaches the saved remotes.
     *
     * The whole of the learning behaviour, in one call, so the screen that sees a sighting does
     * not also decide what it means. [Sightings] holds the decision and its test.
     *
     * @return true when anything was written, which is the signal to redraw. Almost always
     *   false: discovery re-announces every few seconds by design, and a write per packet
     *   re-encrypts every stored password on the way through.
     */
    fun learnFrom(seen: Sightings.Seen): Boolean {
        val learnings = Sightings.learningsFor(all().map { it.forMatching() }, seen)
        var changed = false
        for (l in learnings) {
            val c = byId(l.connectionId) ?: continue
            when (l) {
                is Sightings.Learning.Address -> if (learnAddress(c.id, l.host)) changed = true
                is Sightings.Learning.DeviceId -> {
                    save(c.copy(deviceId = l.deviceId))
                    changed = true
                }
            }
        }
        return changed
    }

    /** The saved remote a discovered host already is, or null when it is genuinely new. */
    fun savedFor(seen: Sightings.Seen): NetConnection? =
        Sightings.savedFor(all().map { it.forMatching() }, seen)?.let { byId(it) }

    /**
     * Attach a discovered host to a saved remote because the person said so.
     *
     * The escape hatch for the one case automatic learning cannot reach: an entry that has no
     * device id AND whose address has already changed has nothing left to match on, so it is
     * stranded - every entry saved before ids existed is one network change away from this. One
     * tap fixes it permanently, because from then on the entry has the id and follows the device
     * by itself.
     *
     * Trusted because it is an assertion, not an inference. It writes the id and the address
     * together; a link that only learned the address would be stranded again next time.
     */
    fun link(id: String, seen: Sightings.Seen): Boolean {
        val c = byId(id) ?: return false
        val known = listOf(c.host) + c.altHosts
        val grown = Endpoints.learn(known, seen.host)
        val next = c.copy(
            deviceId = seen.deviceId.ifBlank { c.deviceId },
            altHosts = grown?.drop(1) ?: c.altHosts,
        )
        if (next == c) return false
        save(next)
        return true
    }

    fun save(connection: NetConnection) {
        val list = all().filterNot { it.id == connection.id } + connection
        write(list)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    fun newId(): String = "n" + System.currentTimeMillis().toString(36)

    private fun write(list: List<NetConnection>) {
        val arr = JSONArray()
        for (c in list) arr.put(toJson(c))
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(c: NetConnection) = JSONObject().apply {
        put("id", c.id)
        put("protocol", c.protocol.name)
        put("label", c.label)
        put("host", c.host)
        put("altHosts", org.json.JSONArray(c.altHosts))
        put("lastGood", c.lastGood)
        put("deviceId", c.deviceId)
        put("port", c.port)
        put("user", c.user)
        put("password", encrypt(c.password))
        put("share", c.share)
        put("domain", c.domain)
        put("tls", c.useTls)
        put("anon", c.anonymous)
        put("startPath", c.startPath)
        put("readOnly", c.readOnly)
        put("passive", c.passive)
        put("timeout", c.timeoutSeconds)
        // The key is a secret of the same kind as the password and gets the same treatment.
        put("privateKey", encrypt(c.privateKey))
        put("keyPassphrase", encrypt(c.keyPassphrase))
    }

    private fun fromJson(o: JSONObject): NetConnection? = runCatching {
        NetConnection(
            id = o.getString("id"),
            protocol = NetProtocol.valueOf(o.getString("protocol")),
            label = o.optString("label"),
            host = o.optString("host"),
            altHosts = o.optJSONArray("altHosts")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }.orEmpty(),
            lastGood = o.optString("lastGood"),
            deviceId = o.optString("deviceId"),
            port = o.optInt("port"),
            user = o.optString("user"),
            password = decrypt(o.optString("password")),
            share = o.optString("share"),
            domain = o.optString("domain"),
            useTls = o.optBoolean("tls"),
            anonymous = o.optBoolean("anon"),
            startPath = o.optString("startPath"),
            readOnly = o.optBoolean("readOnly"),
            // Defaulted true rather than false: a connection saved before this field existed
            // was passive, and reading a missing boolean as false would silently switch every
            // one of them to active mode on the next launch.
            passive = o.optBoolean("passive", true),
            timeoutSeconds = o.optInt("timeout", 15).coerceIn(3, 120),
            privateKey = decrypt(o.optString("privateKey")),
            keyPassphrase = decrypt(o.optString("keyPassphrase")),
        )
    }.getOrNull()

    // ── crypto ──

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // The IV is generated per encryption and stored with the ciphertext. Reusing one
            // with GCM is catastrophic, so it is never derived or fixed.
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(body, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            val (ivB64, bodyB64) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(bodyB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private companion object {
        const val KEY = "connections"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "filet-net-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * Splits `scheme:///<connectionId>/remote/path` into its two halves.
 *
 * The connection id is the first segment, so every network provider shares one addressing
 * rule and a VPath stays a plain string.
 */
internal fun splitNetPath(path: dev.niccc2007.filet.vfs.VPath): Pair<String, String> {
    val segs = path.segments
    if (segs.isEmpty()) return "" to "/"
    val id = segs[0]
    val rest = segs.drop(1).joinToString("/")
    return id to "/$rest"
}

internal fun netPath(scheme: String, id: String, remote: String): dev.niccc2007.filet.vfs.VPath =
    dev.niccc2007.filet.vfs.VPath.of(scheme, "/$id/${remote.trimStart('/')}")
