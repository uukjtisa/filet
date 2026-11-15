package dev.niccc2007.filet.nearby

/**
 * What the sending phone's own server has seen of a fast share it pushed, from one address.
 *
 * The push's own reply is one signal and can be lost - a slow answer, a reply that never makes
 * it back - while the other phone carries on and takes the files. Its requests to this phone's
 * server are a second signal that cannot be lost that way: a phone asking about the share holds
 * it, and a phone fetching its files took it.
 */
enum class FastSeen { ASKED, FETCHING }

/** The states a push row has before the other phone answers, beside the answers it sends. */
object PushReply {
    const val SENDING = "sending"
    /** Nothing at its address answered the connection: gone, asleep, or at another address. */
    const val UNREACHED = "unreached"
    /** The push went out and no reply came back in time. It may still have arrived. */
    const val UNANSWERED = "unanswered"
}

/** A push row's words, from the reply and what the server saw. Pure. */
object PushRow {
    /** What the server has seen from one address after a new request; fetching is never undone. */
    fun seen(prev: FastSeen?, fetching: Boolean): FastSeen =
        if (fetching || prev == FastSeen.FETCHING) FastSeen.FETCHING else FastSeen.ASKED

    /**
     * Bug identified here: the row read only the push's own reply, and any failure of it -
     * including a reply that timed out after the push had landed - read "No answer. Is Filet open
     * on it?" while the other phone was showing the pop-up or already taking the files. What the
     * server sees from that phone now outranks a reply that went missing.
     */
    fun detail(said: String?, seen: FastSeen?, paired: Boolean): String = when {
        seen == FastSeen.FETCHING -> "Answered: taking the files"
        said == "blocked" -> "They are not taking pushes from this phone"
        said == "accepted" -> "Sent: it arrives without asking"
        said == "held" -> "Waiting: their pop-ups are off"
        said == "asked" || seen == FastSeen.ASKED -> "Asked: waiting for them to accept"
        said == PushReply.SENDING -> "Sending…"
        said == PushReply.UNANSWERED -> "Sent, no reply yet"
        said == PushReply.UNREACHED -> "Could not reach it. Is Filet open on it? Tap to try again"
        said == null -> if (paired) "Paired" else "Tap to send"
        else -> "No answer. Is Filet open on it?"
    }

    /** Whether the row shows as answered: the other phone has the offer or the files. */
    fun answered(said: String?, seen: FastSeen?): Boolean =
        seen != null || said == "asked" || said == "accepted" || said == "held"
}

/** Addresses as they go into a link. Pure. */
object NetHost {
    /**
     * An address as a link's host and port. An IPv6 address is bracketed, and its interface
     * suffix dropped, which a link cannot carry.
     *
     * Bug identified here: links were made as `http://$host:$port`. Since Android 14 a looked-up
     * phone can come back with an IPv6 address first, and `http://fe80::1c2:8790` is not a link
     * to anything - the push failed before it left. It depended on which address the other phone
     * listed first, so it came and went, and only from the newer phone.
     */
    fun authority(host: String, port: Int): String {
        val h = plain(host)
        return (if (':' in h) "[$h]" else h) + ":" + port
    }

    /**
     * An address as one phone would know another by: an IPv4 address that arrived wrapped in
     * IPv6 (`::ffff:192.168.1.4`, as a dual-stack server sees it) is unwrapped, and an interface
     * suffix is dropped.
     */
    fun plain(address: String): String {
        val a = address.trim().removePrefix("/").substringBefore('%').removeSurrounding("[", "]")
        val v4 = a.removePrefix("::ffff:").removePrefix("::FFFF:")
        return if (v4 != a && v4.count { it == '.' } == 3) v4 else a
    }
}
