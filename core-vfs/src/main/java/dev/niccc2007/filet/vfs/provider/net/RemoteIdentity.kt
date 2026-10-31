package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.VPath

/**
 * What a saved remote IS, as far as anything stored against it is concerned. SEARCH.md §13.7.
 *
 * The index stores a share's rows under a volume whose uuid is the share's root path, and that
 * path carries the connection id - not the host - so renaming a server or moving it to another
 * address leaves every stored row correct. Two edits do not: changing which share an SMB entry
 * opens, or the base path of a WebDAV one, makes `/<id>/notes.txt` name a different file; and
 * changing the protocol changes the scheme in the root itself. Those rows are not stale, they
 * are WRONG, and at the save site they look exactly like the harmless edits beside them.
 *
 * ## Every field decides
 *
 * [RULES] names every property of [NetConnection] and what changing it does. Kotlin cannot make
 * a new field fail to compile here - destructuring and `copy` both tolerate extra components -
 * so the enforcement is two-sided instead: `RemoteIdentityTest` compares [RULES] against the
 * class's declared fields by reflection, and `tools/check-remote-identity.mjs` compares them in
 * the source. A field added to NetConnection without a rule here fails both, which is the
 * point: the default for an undecided field is not allowed to be "harmless".
 */
object RemoteIdentity {

    enum class Effect {
        /** How the share is reached, shown or remembered. Stored paths still mean the same files. */
        NONE,

        /** What stored paths mean. Every row under the share has to go. */
        DROP,

        /** [DROP] for the protocols that use [NetConnection.share], [NONE] for the rest. */
        DROP_WHERE_SHARE_IS_A_PATH,
    }

    class Rule(val field: String, val effect: Effect, val read: (NetConnection) -> Any?)

    /** Every field of [NetConnection], in declaration order. The §13.7 table, as code. */
    val RULES: List<Rule> = listOf(
        // A different id is a different entry with a different root - nothing stored carries over.
        Rule("id", Effect.DROP) { it.id },
        Rule("protocol", Effect.DROP) { it.protocol },
        Rule("label", Effect.NONE) { it.label },
        Rule("host", Effect.NONE) { it.host },
        Rule("altHosts", Effect.NONE) { it.altHosts },
        Rule("lastGood", Effect.NONE) { it.lastGood },
        Rule("deviceId", Effect.NONE) { it.deviceId },
        Rule("port", Effect.NONE) { it.port },
        Rule("user", Effect.NONE) { it.user },
        Rule("password", Effect.NONE) { it.password },
        // SMB's share name and WebDAV's base path are the prefix every stored path sits under.
        // SFTP and FTP do not read the field, so an edit there changes nothing that is stored.
        Rule("share", Effect.DROP_WHERE_SHARE_IS_A_PATH) { it.share },
        // Authentication and transport: who is asking and how, not what the paths name.
        Rule("domain", Effect.NONE) { it.domain },
        Rule("useTls", Effect.NONE) { it.useTls },
        Rule("anonymous", Effect.NONE) { it.anonymous },
        // A landing folder inside the share, not a prefix of anything stored.
        Rule("startPath", Effect.NONE) { it.startPath },
        Rule("readOnly", Effect.NONE) { it.readOnly },
        Rule("passive", Effect.NONE) { it.passive },
        Rule("timeoutSeconds", Effect.NONE) { it.timeoutSeconds },
        Rule("privateKey", Effect.NONE) { it.privateKey },
        Rule("keyPassphrase", Effect.NONE) { it.keyPassphrase },
        Rule("lastUsedAt", Effect.NONE) { it.lastUsedAt },
        // Whether rows are kept at all. Switching it off drops them - see [rowsLost] - but it
        // never changes what a kept row means.
        Rule("indexed", Effect.NONE) { it.indexed },
    )

    /** True when this edit changes what an already-stored path MEANS, rather than how it is reached. */
    fun meaningChanged(old: NetConnection, new: NetConnection): Boolean = RULES.any { r ->
        r.read(old) != r.read(new) && when (r.effect) {
            Effect.NONE -> false
            Effect.DROP -> true
            Effect.DROP_WHERE_SHARE_IS_A_PATH -> usesSharePath(old.protocol) || usesSharePath(new.protocol)
        }
    }

    /**
     * Whether saving [new] over [old] leaves rows the index must drop: the meaning changed, or
     * the share stopped being indexed. Either way every row under [old]'s root goes.
     */
    fun rowsLost(old: NetConnection, new: NetConnection): Boolean =
        meaningChanged(old, new) || (old.indexed && !new.indexed)

    private fun usesSharePath(p: NetProtocol): Boolean = when (p) {
        NetProtocol.SMB, NetProtocol.WEBDAV -> true
        NetProtocol.SFTP, NetProtocol.FTP -> false
    }

    /**
     * The share's root, exactly as its provider writes it - which is also its index volume's
     * uuid once stringified. Built by the provider's own [netPath], never by concatenating: a
     * hand-built `smb:///n1a2b3c/` with a trailing slash would silently never match.
     */
    fun rootOf(c: NetConnection): VPath = netPath(c.protocol.scheme, c.id, "/")

    /** The index volume uuid for [c]. */
    fun volumeUuid(c: NetConnection): String = rootOf(c).toString()

    /** The uuids of every share somebody has opted in to indexing. Feeds the crawl gate. */
    fun indexedUuids(saved: List<NetConnection>): Set<String> =
        saved.filter { it.indexed }.map(::volumeUuid).toSet()

    /**
     * Whether a stored volume uuid belongs to a share that no longer exists - the startup sweep.
     * Only uuids in a network scheme can be orphans; a local volume is never this function's to
     * judge, so it answers false for those whatever is saved.
     */
    fun isOrphan(uuid: String, saved: List<NetConnection>): Boolean {
        val scheme = uuid.substringBefore("://", "")
        if (NetProtocol.entries.none { it.scheme == scheme }) return false
        return saved.none { volumeUuid(it) == uuid }
    }
}
