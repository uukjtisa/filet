package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.KnownPhone
import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.Attached
import dev.niccc2007.filet.desktop.mount.DavTarget
import dev.niccc2007.filet.desktop.mount.DriveLetters
import dev.niccc2007.filet.desktop.mount.ProbeResult

/**
 * The decisions behind the Phones section, as pure functions.
 *
 * Each is something the window can get wrong while looking fine, so none of them lives in a
 * Composable: which drive belongs to which row, whether a Mount button is enabled, which letters
 * a mount may take, and what address a code turns a phone into.
 */
object MountModel {

    /** The address [peer] plus a typed [code] mounts, or null when the code cannot be one. */
    fun targetOf(peer: Peer, code: String): DavTarget? =
        DavTarget.cleanCode(code)?.let { DavTarget(peer.host, peer.port, peer.secure, peer.base.ifBlank { "/a/" }, it) }

    /**
     * Why the mount dialog's Mount cannot be pressed, or null when it can.
     *
     * The first rule is R1. Rows are rebuilt from the live list, so a phone that leaves the
     * network turns its row offline - and a dialog still open under it must stop offering a Mount
     * that will run for twenty seconds against an address nothing answers on.
     */
    fun mountBlocker(
        online: Boolean,
        code: String,
        platformProblem: String?,
        letterFree: Boolean,
        busy: Boolean,
    ): String? = when {
        !online -> "This device has left the network."
        platformProblem != null -> platformProblem
        !letterFree -> "Every drive letter from D: to Z: is in use. Disconnect one first."
        busy -> "Mounting..."
        DavTarget.cleanCode(code) == null -> "Type the access code shown on the device."
        else -> null
    }

    // ── rows ──

    enum class State { MOUNTED, NOT_MOUNTED, OFFLINE }

    /**
     * One line of the Phones list.
     *
     * [phone] is null for a share on the network that this PC has never mounted, [peer] is null
     * for a saved one that is not on the network now, and [drive] is the OS's own report of it.
     */
    data class PhoneRow(val id: String, val phone: KnownPhone?, val peer: Peer?, val drive: Attached?) {
        val state: State
            get() = when {
                drive != null -> State.MOUNTED
                peer != null -> State.NOT_MOUNTED
                else -> State.OFFLINE
            }
        val online: Boolean get() = peer != null
        val label: String get() = phone?.label ?: peer?.name.orEmpty()
    }

    fun stateLine(row: PhoneRow): String = when (row.state) {
        State.MOUNTED -> "Mounted on ${row.drive!!.where}"
        State.NOT_MOUNTED -> "Not mounted"
        State.OFFLINE -> "Offline"
    }

    /**
     * The live phone [row]'s drive should be on, when the phone now answers at another address
     * than the one its drive was mapped to. Null otherwise.
     */
    fun movedTo(row: PhoneRow): Peer? {
        val d = row.drive?.target ?: return null
        val p = row.peer ?: return null
        return p.takeUnless { it.host.equals(d.host, ignoreCase = true) && it.port == d.port }
    }

    /**
     * The address line under a row.
     *
     * A drive is never hidden for being somewhere the phone is not: it is still mapped, still
     * holds a letter, and still needs Unmount. It says where it points and that no phone
     * answers to it there.
     */
    fun addressLine(row: PhoneRow): String {
        val d = row.drive?.target
        val p = row.peer
        return when {
            d != null && p == null -> "Filet drive at ${d.host}:${d.port}, phone not found there"
            d != null && movedTo(row) != null -> "Drive at ${d.host}:${d.port}; the phone is now at ${p!!.host}:${p.port}"
            p != null -> "${p.host}:${p.port}"
            else -> row.phone?.let { "${it.host}:${it.port}" }.orEmpty()
        }
    }

    /** A live share's row identity: its device's id and share name, or its address when it sent no id. */
    fun idOf(peer: Peer): String = if (peer.deviceId.isNotBlank()) "${peer.deviceId}/${peer.name}" else peer.key

    /** Whether [peer] is the share [phone] was saved from. */
    fun isPeer(phone: KnownPhone, peer: Peer): Boolean = phone.id == idOf(peer) ||
        (phone.name == peer.name && phone.host.equals(peer.host, ignoreCase = true) && phone.port == peer.port)

    /** The same share: host as DNS compares it, port, scheme and code. The base is always `/a/`. */
    fun sameShare(a: DavTarget, b: DavTarget): Boolean =
        a.host.equals(b.host, ignoreCase = true) && a.port == b.port && a.secure == b.secure && a.code == b.code

    /**
     * The drive the OS reports for [phone], if it has one.
     *
     * Bug identified here: after a mount succeeded, the row offered Mount again. A row found its
     * drive only through a map that lived for one run, keyed by the peer's `host:port/name`, and
     * compared whole targets including the base path. A restart, the phone resolving to another
     * of its addresses, or a base spelled differently from the one read back out of the UNC path
     * each broke that link while the drive itself stayed mounted. The row now owns a saved target
     * and is matched on what the OS reports. The letter it was mounted on is the fallback, for a
     * host the OS spells differently.
     */
    fun driveOf(phone: KnownPhone, attached: List<Attached>): Attached? =
        attached.firstOrNull { sameShare(phone.target, it.target) }
            ?: phone.letter?.let { l -> attached.firstOrNull { DriveLetters.letterOf(it.where) == l && it.target.code == phone.code } }

    /**
     * Every saved phone in saved order, then every share on the network that none of them is.
     *
     * Saved phones stay listed while offline, so a stale one can be forgotten and a mounted one
     * whose phone has gone can still be unmounted.
     */
    fun rows(known: List<KnownPhone>, live: List<Peer>, attached: List<Attached>): List<PhoneRow> {
        val claimed = HashSet<String>()
        val saved = known.map { k ->
            val p = live.firstOrNull { it.key !in claimed && isPeer(k, it) }?.also { claimed += it.key }
            PhoneRow(k.id, k, p, driveOf(k, attached))
        }
        return saved + live.filter { it.key !in claimed }.map { PhoneRow(idOf(it), null, it, null) }
    }

    /**
     * [known] plus a saved entry for every Filet drive the OS has that no entry claims.
     *
     * Those are drives mapped before this list was saved, or by `--mount` from a terminal, and
     * Windows reattaches them at every login; without this they would be mounted with no row to
     * unmount them from. The name comes from the one live share on that host and port. With
     * several, or none, the address stands in: shares on one port differ only by their code,
     * which is never announced, so picking one would show a name that may be wrong.
     */
    fun adopt(known: List<KnownPhone>, attached: List<Attached>, live: List<Peer>): List<KnownPhone> {
        var out = known
        for (a in attached) {
            if (out.any { driveOf(it, listOf(a)) != null }) continue
            val t = a.target
            val peer = live.singleOrNull { it.host.equals(t.host, ignoreCase = true) && it.port == t.port }
            val name = peer?.name ?: "${t.host}:${t.port}"
            val id = peer?.let(::idOf)?.takeIf { w -> out.none { it.id == w } } ?: "${t.host}:${t.port}/${t.code}"
            out = out + KnownPhone(id, name, name, t.host, t.port, t.code, t.secure, t.base, peer?.deviceId.orEmpty(), DriveLetters.letterOf(a.where))
        }
        return out
    }

    /** [known] with [phone] saved, in place of its own entry and of any other entry for the same share. */
    fun upsert(known: List<KnownPhone>, phone: KnownPhone): List<KnownPhone> {
        fun mine(k: KnownPhone) = k.id == phone.id || sameShare(k.target, phone.target)
        val at = known.indexOfFirst(::mine)
        if (at < 0) return known + phone
        return known.mapIndexedNotNull { i, k -> if (i == at) phone else k.takeUnless(::mine) }
    }

    // ── drive letters ──

    /** One choice in the letter picker. [replaces] names the Filet drive that is unmounted first. */
    data class LetterChoice(val letter: Char, val replaces: String? = null) {
        val text: String get() = if (replaces == null) "$letter:" else "replace $letter: ($replaces)"
    }

    /**
     * The letters a mount may take, from `Z:` down: every free one, and every one a Filet drive
     * holds, which is unmounted first. A letter any other drive holds is never offered - a USB
     * stick, an office share, a remembered drive whose server is away. [own] is the letter the
     * drive being moved is on, and is left out.
     *
     * [taken] is every letter in use, Filet's included; [filet] is Filet's own, with their names.
     */
    fun letterChoices(taken: Set<Char>, filet: Map<Char, String>, own: Char? = null): List<LetterChoice> =
        DriveLetters.CANDIDATES.mapNotNull { l ->
            when {
                l == own -> null
                l in filet -> LetterChoice(l, filet.getValue(l))
                l in taken -> null
                else -> LetterChoice(l)
            }
        }

    /**
     * The choice a picker starts on: [preferred] when it is free, else the first free letter.
     * Only when nothing is free does it start on a replacement, which its text spells out.
     */
    fun firstChoice(choices: List<LetterChoice>, preferred: Char?): LetterChoice? =
        choices.firstOrNull { it.letter == preferred && it.replaces == null }
            ?: choices.firstOrNull { it.replaces == null }
            ?: choices.firstOrNull()

    // ── health ──

    /**
     * A mounted share's state, carried from one probe to the next.
     *
     * Bug identified here: one failed probe marked a working host "Not answering". A phone's
     * Wi-Fi sleeps between packets and a PROPFIND can miss its window, so a single timeout said
     * nothing about the host and condemned it for the next thirty seconds. Silence now has to
     * repeat [MISSES_TO_CONDEMN] times in a row. An HTTP refusal is different: the share answered,
     * so it is reported at once, with its own reason, and never as silence.
     */
    data class Health(val answering: Boolean?, val misses: Int = 0, val note: String? = null)

    const val MISSES_TO_CONDEMN = 2

    fun nextHealth(prev: Health?, r: ProbeResult): Health = when (r) {
        is ProbeResult.Ok -> Health(true)
        // It answered. A 403 on a drive that worked means the device restarted sharing with a
        // new code, which is worth saying in those words.
        is ProbeResult.Refused -> Health(
            false, 0,
            if (r.status == 403) "The device no longer accepts this drive's code. It was probably restarted with a new one; unmount and mount again." else r.message,
        )
        ProbeResult.Unreachable -> {
            val misses = (prev?.misses ?: 0) + 1
            if (misses >= MISSES_TO_CONDEMN) Health(false, misses)
            else Health(prev?.answering, misses, prev?.note)
        }
    }
}
