package dev.niccc2007.filet.webdav

import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing this phone offers to the network, and the rules about a set of them.
 *
 * ## Several shares, one socket
 *
 * [DavPath] has always resolved `/a/<code>/rest`, which means the code was already the first
 * thing every request names - so it is the share selector, and nothing had to be invented to get
 * more than one share. They ride one port, one accept loop and one notification, each with its
 * own root, code, write permission and idle clock.
 *
 * A socket per share was the alternative. It costs a port to punch through per share, an
 * announcement per share and a lifecycle per share, and buys nothing: the endpoints were already
 * distinguishable by their code.
 *
 * ## Why the awkward bits are functions and not UI
 *
 * Two rules here can be got wrong quietly, so neither lives in a Composable:
 *
 *  - **Codes must be unique across live shares**, or one share shadows another and the one that
 *    loses is simply unreachable with no error anywhere.
 *  - **A share cannot open itself unless its code is pinned.** A random code mints a new endpoint
 *    every launch, and a desktop stores the endpoint - so auto-start with a random code produces
 *    exactly the broken mapped drive it was meant to prevent. The switch would be a lie.
 */
data class DavShare(
    val id: Long,
    /** What the card and the notification call it. Never the code. */
    val label: String,
    val scope: DavScope = DavScope.SHARED_FOLDER,
    /** A hand-picked folder, which beats [scope] when set. */
    val customRoot: String? = null,
    /** A pinned code, or null for a fresh one each time this share starts. */
    val code: String? = null,
    val writable: Boolean = false,
    /** Whether the flat indexed views are offered beside the real tree. */
    val views: Boolean = true,
    /** Minutes of no traffic before this share closes itself. Zero or less means never. */
    val idleMinutes: Int = 30,
    /** Whether this share comes up on its own when Filet starts. Needs [code]. */
    val autoStart: Boolean = false,
) {
    /** What the row says it is showing, without giving the code away. */
    val rootLabel: String get() = customRoot?.substringAfterLast('/')?.ifBlank { customRoot } ?: scope.label

    /** True when this share holds the socket open for as long as the app runs. */
    val neverIdles: Boolean get() = idleMinutes <= 0
}

object DavShares {

    /** The share the first path segment names, or null when nothing matches. */
    fun select(code: String, live: List<LiveCode>): Long? =
        live.firstOrNull { DavPath.constantTimeEquals(code, it.code) }?.id

    /** A share's id paired with the code it is actually listening on right now. */
    data class LiveCode(val id: Long, val code: String)

    /**
     * Whether [share] may start itself with Filet.
     *
     * Separate from the toggle's stored value on purpose: the stored value is what was asked for,
     * this is whether it can be honoured. A pinned code is the whole of the condition - see the
     * class note for why a random one makes the feature self-defeating.
     */
    fun canAutoStart(share: DavShare): Boolean = !share.code.isNullOrBlank()

    /** The shares that should come up on launch, in a stable order. */
    fun autoStarting(shares: List<DavShare>): List<DavShare> =
        shares.filter { it.autoStart && canAutoStart(it) }.sortedBy { it.id }

    /**
     * The ids of shares whose pinned codes collide.
     *
     * Every member of a colliding group is returned, not just the later ones: which of two shares
     * is "the wrong one" is not for this function to decide, and a card that flags only the second
     * makes the first look fine.
     */
    fun codeClashes(shares: List<DavShare>): Set<Long> {
        val byCode = shares.filter { !it.code.isNullOrBlank() }.groupBy { it.code!! }
        return byCode.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()
    }

    /** True when [wanted] is free to use as a pinned code, ignoring [exceptId]'s own. */
    fun codeAvailable(shares: List<DavShare>, wanted: String, exceptId: Long? = null): Boolean =
        shares.none { it.id != exceptId && it.code == wanted }

    /** A code that is not taken, derived from [wanted] by appending digits rather than rerolling. */
    fun freeCode(shares: List<DavShare>, wanted: String): String {
        if (wanted.isBlank()) return wanted
        if (codeAvailable(shares, wanted)) return wanted
        for (n in 2..99) {
            val candidate = wanted + n
            if (codeAvailable(shares, candidate)) return candidate
        }
        return wanted
    }

    /** The next free id. Monotonic, so ordering by id is creation order. */
    fun newId(shares: List<DavShare>): Long = (shares.maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * A label that is not already in use, so two rows are never called the same thing.
     *
     * Rows are how a person tells one share from another - the code is deliberately not on
     * display - so duplicate labels make the list unreadable even though nothing is broken.
     */
    fun freeLabel(shares: List<DavShare>, wanted: String): String {
        val taken = shares.map { it.label }.toSet()
        if (wanted !in taken) return wanted
        for (n in 2..99) {
            val candidate = "$wanted $n"
            if (candidate !in taken) return candidate
        }
        return wanted
    }

    /**
     * Whether going from [old] to [new] requires the share to be restarted to take effect.
     *
     * Only the three things that SHAPE THE ENDPOINT do: where it is rooted, and the code it
     * answers on. A desktop stores `http://phone:port/a/<code>` and resolves paths under it, so
     * moving the root or the code under a live mount leaves it holding an address that no longer
     * means what it did - which looks to somebody at the PC like every file vanished at once.
     *
     * Everything else is policy and applies immediately. That distinction is the whole of this
     * function, and getting it wrong in the permissive direction breaks a live mount, while
     * getting it wrong in the strict direction produces a setting that appears to do nothing -
     * which is what happened to "allow changes": it was saved, the share kept running on its old
     * value, and the only hint was a toast about the next restart.
     */
    fun needsRestart(old: DavShare, new: DavShare): Boolean =
        old.scope != new.scope ||
            old.customRoot != new.customRoot ||
            old.code != new.code

    /** The address a desktop mounts. The one string that has to stay put between launches. */
    fun endpoint(host: String, port: Int, code: String): String = "http://$host:$port/a/$code"

    /** The share a fresh install hosts, so the list is never empty and never needs seeding twice. */
    fun default(): DavShare = DavShare(id = 1L, label = "My share")

    /**
     * Replace [share] in [shares], or append it when its id is not there yet.
     *
     * Insertion keeps creation order rather than moving an edited share to the end, because a list
     * that reorders itself when you change a setting is a list you lose your place in.
     */
    fun upsert(shares: List<DavShare>, share: DavShare): List<DavShare> =
        if (shares.none { it.id == share.id }) shares + share
        else shares.map { if (it.id == share.id) share else it }

    /**
     * Remove [id], keeping at least one share.
     *
     * The floor is not tidiness: hosting with no shares is a button that starts a server with
     * nothing behind it, and the card would have no row to explain itself with.
     */
    fun remove(shares: List<DavShare>, id: Long): List<DavShare> =
        shares.filterNot { it.id == id }.ifEmpty { shares }

    /**
     * The stored share list, or one folded out of a pre-list installation.
     *
     * An upgrade must not forget a pinned code or a chosen folder. Those two were the whole of the
     * endpoint a desktop had already mapped, and losing them silently breaks the mapping on the PC
     * - which is the exact failure the persistence was added to stop, so reintroducing it here
     * would be a poor joke.
     *
     * Once a list exists it wins outright and the legacy values are ignored, so this cannot
     * resurrect an old setting over a deliberate edit.
     */
    fun migrate(stored: String?, legacyCode: String?, legacyRoot: Any?): List<DavShare> {
        if (!stored.isNullOrBlank()) return decode(stored)
        val code = legacyCode?.takeIf { it.isNotBlank() }
        val root = legacyRoot?.toString()?.takeIf { it.isNotBlank() }
        if (code == null && root == null) return listOf(default())
        return listOf(default().copy(code = code, customRoot = root))
    }

    // ---- codec ----------------------------------------------------------------------------
    //
    // Explicit rather than reflective, so a field rename cannot silently drop a stored share,
    // and every read has a default - a share missing a key loads with the safe value, not at all.

    fun encode(shares: List<DavShare>): String {
        val arr = JSONArray()
        for (s in shares) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("scope", s.scope.name)
                    s.customRoot?.let { put("root", it) }
                    s.code?.let { put("code", it) }
                    put("writable", s.writable)
                    put("views", s.views)
                    put("idle", s.idleMinutes)
                    put("auto", s.autoStart)
                },
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<DavShare> {
        if (raw.isNullOrBlank()) return listOf(default())
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> one(arr.optJSONObject(i)) }
        }.getOrNull()
        // A stored list that will not parse falls back to the default rather than to nothing.
        // Losing the shares is bad; losing the ability to host at all is worse.
        return parsed?.takeIf { it.isNotEmpty() } ?: listOf(default())
    }

    private fun one(o: JSONObject?): DavShare? {
        if (o == null) return null
        val id = o.optLong("id", 0L)
        if (id <= 0L) return null
        return DavShare(
            id = id,
            label = o.optString("label", "Share").ifBlank { "Share" },
            scope = DavScope.entries.firstOrNull { it.name == o.optString("scope") }
                ?: DavScope.SHARED_FOLDER,
            customRoot = o.optString("root").takeIf { it.isNotBlank() },
            code = o.optString("code").takeIf { it.isNotBlank() },
            // Writing now PERSISTS, and the reasoning it replaces is worth keeping.
            //
            // It used to load as false always, on the grounds that turning a phone into a writable
            // drive is a decision for the session in front of you. That held while a session was
            // something a person started by hand thirty seconds earlier. Shares are now stored and
            // can start themselves with the app, so forcing it off made the editor's toggle do
            // nothing that survived a restart - a dead switch, and the reason writing "felt like
            // it was still read".
            //
            // The safety is paid in visibility instead: a writable share says so on its row and in
            // the card header. A NEW share still defaults to off.
            writable = o.optBoolean("writable", false),
            views = o.optBoolean("views", true),
            idleMinutes = o.optInt("idle", 30),
            autoStart = o.optBoolean("auto", false),
        )
    }
}
