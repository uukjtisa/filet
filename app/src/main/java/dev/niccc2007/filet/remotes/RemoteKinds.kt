package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * What you are connecting to, and what that implies.
 *
 * ## Why this exists
 *
 * The add-a-remote form asked for a protocol, a host, a port, a "share", a user and a password,
 * in that order, with nothing on it explaining any of them. That is a form only somebody who
 * already knows the answer can fill in - and the report that started this was exactly that: *"i
 * literally have no idea how to add a network location of my pc"*.
 *
 * The fix is not visual. It is that the **first question should be the one the person can
 * answer**: what is the thing at the other end? A Windows PC, a NAS, another phone. Everything
 * else - which protocol, which port, what the middle part of the path is called and whether it is
 * even needed - follows from that and should be filled in rather than asked for.
 *
 * ## Why a kind is not just a protocol
 *
 * SMB, SFTP, FTP and WebDAV are four protocols, but they are not four ANSWERS to "what are you
 * connecting to". A Windows PC and a NAS both speak SMB and need completely different explanation:
 * on Windows the share name is a folder you right-clicked and chose Share on; on a NAS it is
 * usually a fixed name the box was set up with. Another phone running Filet speaks WebDAV, but so
 * does a Nextcloud server, and the thing to tell somebody differs entirely.
 *
 * So a kind carries the protocol AND the words. The mapping is here, with tests, because getting
 * it wrong produces a form that is confidently mislabelled - worse than the unlabelled one it
 * replaced.
 */
enum class RemoteKind(
    val title: String,
    /** One line, on the chooser, saying how to recognise this is the right one. */
    val blurb: String,
) {
    WINDOWS_PC(
        "A Windows PC",
        "A folder you shared from a Windows computer",
    ),
    FILET_DESKTOP(
        "A computer running Filet Desktop",
        "The Filet share tool, running on a PC or laptop",
    ),
    FILET_PHONE(
        "Another phone running Filet",
        "A phone with hosting turned on in Remotes",
    ),
    NAS(
        "A NAS or home server",
        "Synology, QNAP, TrueNAS, a Raspberry Pi",
    ),
    MAC_OR_LINUX(
        "A Mac or Linux computer",
        "File sharing turned on in system settings",
    ),
    OTHER(
        "Something else",
        "Pick the protocol yourself",
    ),
    ;
}

/**
 * Everything the form should know once a kind is chosen.
 *
 * @param middleLabel what the middle part of the address is called for this kind, or null when
 *   this kind does not use one. It is the single most confusing field on the old form: it is the
 *   "share" on SMB, the "base path" on WebDAV, and nothing at all on SFTP.
 */
data class KindSpec(
    val protocol: NetProtocol,
    val defaultPort: Int,
    val hostLabel: String,
    val hostHint: String,
    val middleLabel: String?,
    val middleHint: String,
    val middleHelp: String,
    val needsCredentials: Boolean,
    /** Shown once, under the fields. The thing somebody has to go and do on the other machine. */
    val setupHint: String,
) {
    val usesMiddle: Boolean get() = middleLabel != null
}

object RemoteKinds {

    fun specFor(kind: RemoteKind): KindSpec = when (kind) {
        RemoteKind.WINDOWS_PC -> KindSpec(
            protocol = NetProtocol.SMB,
            defaultPort = 445,
            hostLabel = "PC name or address",
            hostHint = "192.168.1.5",
            middleLabel = "Shared folder name",
            middleHint = "Users",
            middleHelp = "The name Windows gave the folder when you shared it - not its full " +
                "path. Right-click the folder on the PC, Properties, Sharing, and it is the " +
                "name under Network Path.",
            needsCredentials = true,
            setupHint = "The folder has to be shared on the PC first: right-click it, " +
                "Properties, Sharing, Share.",
        )

        RemoteKind.FILET_DESKTOP -> KindSpec(
            protocol = NetProtocol.WEBDAV,
            defaultPort = 8322,
            hostLabel = "Computer's address",
            hostHint = "192.168.1.5",
            middleLabel = "Share name",
            middleHint = "pc",
            middleHelp = "Shown in the Filet Desktop window. Usually just `pc`.",
            needsCredentials = true,
            setupHint = "Start Filet Desktop on the computer. It prints its address, the " +
                "share name and the password.",
        )

        RemoteKind.FILET_PHONE -> KindSpec(
            protocol = NetProtocol.WEBDAV,
            defaultPort = 8321,
            hostLabel = "Other phone's address",
            hostHint = "192.168.1.9",
            middleLabel = "Access code",
            middleHint = "KTR9",
            middleHelp = "The four characters the other phone shows on its hosting card.",
            needsCredentials = false,
            setupHint = "On the other phone: Remotes, Network and root, start a share. It " +
                "shows an address and a code. Phones on the same Wi-Fi appear by themselves " +
                "under Phones on this network.",
        )

        RemoteKind.NAS -> KindSpec(
            protocol = NetProtocol.SMB,
            defaultPort = 445,
            hostLabel = "NAS name or address",
            hostHint = "192.168.1.20",
            middleLabel = "Share name",
            middleHint = "home",
            middleHelp = "The share the NAS was set up with - `home`, `media`, `volume1` and " +
                "so on. It is listed in the NAS's own web page.",
            needsCredentials = true,
            setupHint = "Use the same account you sign in to the NAS web page with.",
        )

        RemoteKind.MAC_OR_LINUX -> KindSpec(
            protocol = NetProtocol.SFTP,
            defaultPort = 22,
            hostLabel = "Computer's address",
            hostHint = "192.168.1.7",
            // SFTP has no share concept at all. Asking for one was pure noise.
            middleLabel = null,
            middleHint = "",
            middleHelp = "",
            needsCredentials = true,
            setupHint = "Needs Remote Login on a Mac, or an SSH server on Linux. The account " +
                "is the one you log in to that computer with.",
        )

        RemoteKind.OTHER -> KindSpec(
            protocol = NetProtocol.WEBDAV,
            defaultPort = 80,
            hostLabel = "Address",
            hostHint = "192.168.1.5",
            middleLabel = "Path",
            middleHint = "",
            middleHelp = "The part of the address after the host and port, if there is one.",
            needsCredentials = true,
            setupHint = "",
        )
    }

    /**
     * The spec for a kind, with the protocol overridden.
     *
     * Only [RemoteKind.OTHER] offers a protocol picker, and when the protocol moves, so do the
     * port and whether a middle part exists - otherwise picking SFTP leaves an SMB share-name box
     * on screen asking for something SFTP has no concept of.
     */
    fun specFor(kind: RemoteKind, protocol: NetProtocol): KindSpec {
        val base = specFor(kind)
        if (base.protocol == protocol) return base
        return base.copy(
            protocol = protocol,
            defaultPort = defaultPortFor(protocol),
            middleLabel = when (protocol) {
                NetProtocol.SMB -> "Share name"
                NetProtocol.WEBDAV -> "Path"
                // Neither has one.
                NetProtocol.SFTP, NetProtocol.FTP -> null
            },
        )
    }

    fun defaultPortFor(protocol: NetProtocol): Int = when (protocol) {
        NetProtocol.SMB -> 445
        NetProtocol.SFTP -> 22
        NetProtocol.FTP -> 21
        NetProtocol.WEBDAV -> 80
    }

    /**
     * The kind an existing connection most likely came from, for the edit form.
     *
     * A guess, and it only decides which words are shown - never what is sent. A WebDAV connection
     * on the hosting port is almost certainly another phone; on the desktop tool's port, the
     * desktop tool; otherwise something else.
     */
    fun kindFor(c: NetConnection): RemoteKind = when (c.protocol) {
        NetProtocol.SMB -> RemoteKind.WINDOWS_PC
        NetProtocol.SFTP -> RemoteKind.MAC_OR_LINUX
        NetProtocol.FTP -> RemoteKind.OTHER
        NetProtocol.WEBDAV -> when (c.port) {
            8321 -> RemoteKind.FILET_PHONE
            8322 -> RemoteKind.FILET_DESKTOP
            else -> RemoteKind.OTHER
        }
    }

    /**
     * A blank connection for a kind, with everything the kind already implies filled in.
     *
     * The point of the whole exercise: by the time the fields appear, the protocol and port are
     * decided and the person is only asked what they actually know.
     */
    fun blank(kind: RemoteKind, id: String): NetConnection {
        val spec = specFor(kind)
        return NetConnection(
            id = id,
            protocol = spec.protocol,
            label = "",
            host = "",
            port = spec.defaultPort,
            user = "",
            password = "",
            share = "",
            anonymous = !spec.needsCredentials,
        )
    }

    /**
     * A name to save under when none was typed.
     *
     * Never blank: a row with no name is indistinguishable from the next one, and the host is the
     * thing a person recognises.
     */
    fun nameFor(typed: String, host: String, kind: RemoteKind): String {
        val t = typed.trim()
        if (t.isNotEmpty()) return t
        val h = host.trim()
        if (h.isNotEmpty()) return h
        return specFor(kind).let { kind.title }
    }

    /**
     * The base path a Filet phone share actually lives at, built from the access code.
     *
     * A hosting phone serves at `/a/<code>`, and the code is deliberately NOT broadcast - a
     * password travelling beside the address is not a password. So discovery fills in the host,
     * the port and the `a/` prefix, and the one field left is the code.
     *
     * The form therefore shows the CODE, not the path. Showing the raw base path meant a
     * discovered phone arrived with `a` in a box labelled "Access code", which is neither the
     * code nor anything a person could correct without knowing the URL shape.
     */
    fun shareFromCode(kind: RemoteKind, typed: String): String {
        val code = typed.trim().trim('/')
        if (kind != RemoteKind.FILET_PHONE) return code
        if (code.isEmpty()) return ""
        // Already a full path - somebody pasted `a/KTR9` rather than typing `KTR9`.
        if (code.startsWith("a/")) return code
        return "a/" + code
    }

    /** The code to show in the form for a stored share path. The inverse of [shareFromCode]. */
    fun codeFromShare(kind: RemoteKind, share: String): String {
        val s = share.trim().trim('/')
        if (kind != RemoteKind.FILET_PHONE) return s
        // `a` alone is the prefix with no code yet, which is what discovery supplies.
        if (s == "a") return ""
        return s.removePrefix("a/")
    }

    /** Whether the form has enough to save. Host is the only thing with no sensible default. */
    fun canSave(host: String, middle: String, spec: KindSpec): Boolean {
        if (host.isBlank()) return false
        // A share name is part of the address for SMB - without it there is nothing to connect to,
        // and the failure it produces is an unhelpful access-denied.
        if (spec.usesMiddle && spec.protocol == NetProtocol.SMB && middle.isBlank()) return false
        return true
    }
}
