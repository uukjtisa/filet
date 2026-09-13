package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * One browser that has been let in.
 *
 * Typing the PIN mints a grant, and the grant is a **URL of its own** -
 * `http://<ip>:<port>/a/<token>/` - rather than a cookie. Three reasons that is better here:
 *
 * 1. It survives a browser that drops cookies, a different tab, a different browser.
 * 2. The user can see it. A cookie is invisible; a row in the Nearby tab saying "Firefox on
 *    192.168.1.14, let in 6 minutes ago" is something you can look at and revoke.
 * 3. Revocation becomes real and immediate, instead of "clear your cookies".
 *
 * **Nothing expires by itself unless the user asks it to.** That is deliberate and it is the
 * user's call: an access that vanishes mid-download because a timer fired is a worse failure
 * than one that outlives its usefulness. [expiresAt] and [idleRevokeMinutes] are both opt-in.
 */
data class AccessGrant(
    val token: String,
    /** Browser and OS, shortened - enough to tell two laptops apart, not a fingerprint. */
    val label: String,
    /** The address it was granted from. */
    val fromAddress: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    /** Epoch millis, or 0 for "never" - the default. */
    val expiresAt: Long = 0,
    /** Revoke after this many minutes with no request, or 0 for "never" - the default. */
    val idleRevokeMinutes: Int = 0,
) {
    fun urlFor(address: String, port: Int) = "http://$address:$port/a/$token/"

    /** A grant is dead only if the user asked for a rule that has now fired. */
    fun isExpired(now: Long): Boolean {
        if (expiresAt in 1..now) return true
        if (idleRevokeMinutes > 0 && now - lastSeenAt > idleRevokeMinutes * 60_000L) return true
        return false
    }
}

/**
 * The live set of granted endpoints.
 *
 * Persisted, because a share that survives the app being swapped out of memory but silently
 * drops everyone who was already browsing is a worse experience than either extreme.
 */
class AccessGrants(private val prefs: Prefs) {

    private val random = SecureRandom()
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<AccessGrant>> = _all.asStateFlow()

    /** Defaults applied to a new grant. Both "never" unless the user says otherwise. */
    var defaultLifetimeMinutes: Int
        get() = prefs.getLong(KEY_LIFETIME, 0L).toInt()
        set(v) = prefs.putLong(KEY_LIFETIME, v.toLong())

    var defaultIdleMinutes: Int
        get() = prefs.getLong(KEY_IDLE, 0L).toInt()
        set(v) = prefs.putLong(KEY_IDLE, v.toLong())

    @Synchronized
    fun mint(label: String, fromAddress: String): AccessGrant {
        val now = System.currentTimeMillis()
        val lifetime = defaultLifetimeMinutes
        val grant = AccessGrant(
            token = newToken(),
            label = label,
            fromAddress = fromAddress,
            createdAt = now,
            lastSeenAt = now,
            expiresAt = if (lifetime > 0) now + lifetime * 60_000L else 0L,
            idleRevokeMinutes = defaultIdleMinutes,
        )
        _all.value = _all.value + grant
        save()
        return grant
    }

    /**
     * @return the grant, or null if the token is unknown or its own rule has fired.
     *
     * Touches `lastSeenAt`, which is what makes the idle rule mean "idle" rather than "old".
     */
    @Synchronized
    fun use(token: String): AccessGrant? {
        val now = System.currentTimeMillis()
        val grant = _all.value.firstOrNull { it.token == token } ?: return null
        if (grant.isExpired(now)) {
            revoke(token)
            return null
        }
        val touched = grant.copy(lastSeenAt = now)
        _all.value = _all.value.map { if (it.token == token) touched else it }
        // Deliberately not saved on every request: this is a hot path, and losing a few
        // minutes of "last seen" across a restart costs nothing.
        return touched
    }

    @Synchronized
    fun revoke(token: String) {
        _all.value = _all.value.filterNot { it.token == token }
        save()
    }

    @Synchronized
    fun revokeAll() {
        _all.value = emptyList()
        save()
    }

    /** Drop grants whose own rule has fired. Called from the service's idle sweep. */
    @Synchronized
    fun sweep() {
        val now = System.currentTimeMillis()
        val kept = _all.value.filterNot { it.isExpired(now) }
        if (kept.size != _all.value.size) {
            _all.value = kept
            save()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun load(): List<AccessGrant> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AccessGrant(
                    token = o.getString("token"),
                    label = o.optString("label", "browser"),
                    fromAddress = o.optString("from", ""),
                    createdAt = o.optLong("at"),
                    lastSeenAt = o.optLong("seen"),
                    expiresAt = o.optLong("exp"),
                    idleRevokeMinutes = o.optInt("idle"),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save() {
        val arr = JSONArray()
        for (g in _all.value) {
            arr.put(
                JSONObject()
                    .put("token", g.token).put("label", g.label).put("from", g.fromAddress)
                    .put("at", g.createdAt).put("seen", g.lastSeenAt)
                    .put("exp", g.expiresAt).put("idle", g.idleRevokeMinutes)
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object {
        const val KEY = "nearby.grants"
        const val KEY_LIFETIME = "nearby.grant.lifetime"
        const val KEY_IDLE = "nearby.grant.idle"
    }
}
