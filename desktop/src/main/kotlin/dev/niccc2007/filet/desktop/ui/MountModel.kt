package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.Attached
import dev.niccc2007.filet.desktop.mount.DavTarget
import dev.niccc2007.filet.desktop.mount.ProbeResult

/**
 * The decisions behind the "Phones on this network" section, as pure functions.
 *
 * Each is something the window can get wrong while looking fine, so none of them lives in a
 * Composable: whether a Mount button is enabled, which device a mounted drive belongs to, and what
 * address a code turns a peer into.
 */
object MountModel {

    /** The address [peer] plus a typed [code] mounts, or null when the code cannot be one. */
    fun targetOf(peer: Peer, code: String): DavTarget? =
        DavTarget.cleanCode(code)?.let { DavTarget(peer.host, peer.port, peer.secure, peer.base.ifBlank { "/a/" }, it) }

    /**
     * Why Mount cannot be pressed for [peer], or null when it can.
     *
     * The first rule is R1. The peer list is live, so a device that leaves the network drops out
     * of [current] - and a code field still open under its row must stop offering a Mount that
     * will run for twenty seconds against an address nothing answers on.
     */
    fun mountBlocker(
        peer: Peer,
        current: List<Peer>,
        code: String,
        platformProblem: String?,
        letterFree: Boolean,
        busy: Boolean,
    ): String? = when {
        current.none { it.key == peer.key } -> "This device has left the network."
        platformProblem != null -> platformProblem
        !letterFree -> "Every drive letter from D: to Z: is in use. Disconnect one first."
        busy -> "Mounting..."
        targetOf(peer, code) == null -> "Type the access code shown on the device."
        else -> null
    }

    /**
     * The name to show for a mounted drive.
     *
     * The target remembered at mount time wins, because one device can offer several shares on
     * one port and only the code tells them apart. A drive mapped in an earlier run has no such
     * memory, so it falls back to whichever device is answering on that host and port, and then
     * to the address itself - never blank, never a guess presented as certain.
     */
    fun labelFor(mount: Attached, peers: List<Peer>, remembered: Map<String, DavTarget>): String {
        remembered.entries.firstOrNull { it.value == mount.target }?.let { (key, _) ->
            peers.firstOrNull { it.key == key }?.let { return it.name }
            return key.substringAfter('/')
        }
        return peers.firstOrNull { it.host == mount.target.host && it.port == mount.target.port }?.name
            ?: "${mount.target.host}:${mount.target.port}"
    }

    /** The drive [peer] is mounted as, if this run mounted it and the OS still has it. */
    fun mountedAs(peer: Peer, attached: List<Attached>, remembered: Map<String, DavTarget>): Attached? {
        val t = remembered[peer.key] ?: return null
        return attached.firstOrNull { it.target == t }
    }

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
