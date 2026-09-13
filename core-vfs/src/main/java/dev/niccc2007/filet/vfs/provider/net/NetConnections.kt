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
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** SMB share name, or the base path for WebDAV. Unused by SFTP and FTP. */
    val share: String = "",
    val domain: String = "",
    val useTls: Boolean = false,
    val anonymous: Boolean = false,
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
        put("port", c.port)
        put("user", c.user)
        put("password", encrypt(c.password))
        put("share", c.share)
        put("domain", c.domain)
        put("tls", c.useTls)
        put("anon", c.anonymous)
    }

    private fun fromJson(o: JSONObject): NetConnection? = runCatching {
        NetConnection(
            id = o.getString("id"),
            protocol = NetProtocol.valueOf(o.getString("protocol")),
            label = o.optString("label"),
            host = o.optString("host"),
            port = o.optInt("port"),
            user = o.optString("user"),
            password = decrypt(o.optString("password")),
            share = o.optString("share"),
            domain = o.optString("domain"),
            useTls = o.optBoolean("tls"),
            anonymous = o.optBoolean("anon"),
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
