package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.link.DriveLink
import dev.niccc2007.filet.vfs.provider.net.Endpoints
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * What a scanned network drive is to the remotes already saved.
 *
 * The add dialog is only as good as this answer, and the expensive mistake is merging two
 * different drives: a saved remote quietly pointed at somebody else's share. So a match is made
 * on the device ID wherever both sides carry one, never on the access code alone - two phones can
 * pick the same code, and the code is the one thing a person types identically everywhere.
 */
sealed interface DriveMatch {
    /** Nothing saved is this drive. */
    data object New : DriveMatch

    /** Already saved, at an address the remote knows. */
    data class Known(val saved: NetConnection) : DriveMatch

    /** Saved, and the same device by its ID, now at an address the remote does not know. */
    data class Moved(val saved: NetConnection) : DriveMatch

    /**
     * The same share path at another address, from a remote saved before device IDs existed (or a
     * link without one). Possibly the same drive, possibly not; the dialog offers both answers.
     */
    data class Maybe(val saved: NetConnection) : DriveMatch

    companion object {
        fun of(drive: DriveLink, saved: List<NetConnection>): DriveMatch {
            val same = saved.filter { it.protocol == NetProtocol.WEBDAV && it.share.trim('/') == drive.share }
            val asked = drive.hosts.map { it.lowercase() }.toSet()
            fun hosts(c: NetConnection) = (listOf(c.host) + c.altHosts).map { it.lowercase() }
            fun sameDevice(c: NetConnection) = c.deviceId.isNotEmpty() && c.deviceId == drive.deviceId
            fun unknownDevice(c: NetConnection) = c.deviceId.isEmpty() || drive.deviceId.isEmpty()

            same.firstOrNull { c ->
                c.port == drive.port && hosts(c).any { it in asked } && (sameDevice(c) || unknownDevice(c))
            }?.let { return Known(it) }
            same.firstOrNull { sameDevice(it) }?.let { return Moved(it) }
            same.firstOrNull { unknownDevice(it) }?.let { return Maybe(it) }
            return New
        }

        /** [drive] as a new saved remote. */
        fun remote(drive: DriveLink, id: String): NetConnection = NetConnection(
            id = id,
            protocol = NetProtocol.WEBDAV,
            label = drive.name,
            host = drive.hosts.first(),
            altHosts = drive.hosts.drop(1),
            port = drive.port,
            user = "",
            password = "",
            share = drive.share,
            useTls = drive.tls,
            anonymous = false,
            deviceId = drive.deviceId,
        )

        /**
         * [saved] taught the drive's addresses. Appended, as discovery does, so an address that
         * has never answered does not displace one that has; the port and device ID follow the
         * link, which is the newest word on both.
         */
        fun withAddresses(saved: NetConnection, drive: DriveLink): NetConnection {
            var known = listOf(saved.host) + saved.altHosts
            for (h in drive.hosts) known = Endpoints.learn(known, h) ?: known
            return saved.copy(
                altHosts = known.drop(1),
                port = drive.port,
                deviceId = drive.deviceId.ifEmpty { saved.deviceId },
            )
        }
    }
}
