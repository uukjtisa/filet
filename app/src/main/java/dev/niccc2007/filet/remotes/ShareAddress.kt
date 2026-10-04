package dev.niccc2007.filet.remotes

/**
 * A Filet share's address, split where the reader has to do something and joined back again.
 *
 * ## The fault
 *
 * Discovery fills in everything it can, which for a Filet phone is everything except the access
 * code - the code is deliberately never broadcast, because a password that travels with the
 * address is not a password. So the form opened with `http://192.168.100.14:22222/a/` sitting in
 * a text box, finished, with a trailing slash and nothing to say that four more characters belong
 * on the end. A field whose correct value is *what is shown, plus a thing you have to know* is a
 * riddle, and the only person who can answer it is the one who wrote the form.
 *
 * ## Why this is not simply two saved fields
 *
 * The address is one stored value on purpose, and that decision stands: an address and a code kept
 * as separate columns have to be held in step by every path that reads, writes, discovers or edits
 * one, and the bug that produces is a remote whose code is right in one place and stale in
 * another.
 *
 * So there is still exactly one value. This splits it for DISPLAY, so the code can have its own
 * box with its own label, and joins it straight back. The text box and the code box are two
 * editors onto one string, not two strings.
 */
object ShareAddress {

    /** The part before the code, always ending in the separator, and the code itself. */
    data class Parts(val prefix: String, val code: String)

    /**
     * Split [address] at the point where the code begins.
     *
     * The marker is the share prefix the host advertises, `/a/`. Anything after it is the code;
     * anything up to and including it is the part discovery knew. An address that does not contain
     * the marker is handed back whole as the prefix, with no code - which is the honest answer for
     * something that is not a Filet share address yet, including an empty box.
     */
    fun split(address: String): Parts {
        val at = address.lastIndexOf(MARKER)
        if (at < 0) return Parts(address, "")
        val cut = at + MARKER.length
        return Parts(address.take(cut), address.drop(cut))
    }

    /**
     * Put a code back onto a prefix.
     *
     * Tolerant about the separator because both editors write through here: the text box can leave
     * the prefix without its trailing slash, and the code box can be handed something pasted with
     * one on the front. Either way there is exactly one slash in the result.
     */
    fun join(prefix: String, code: String): String {
        val head = prefix.trimEnd('/')
        val tail = code.trim().trim('/')
        if (tail.isEmpty()) return "$head/"
        return "$head/$tail"
    }

    /**
     * What a reader can be told to type, with no code in it yet.
     *
     * Used to decide whether to show the code box as empty-and-waiting rather than as wrong. There
     * is a difference between a form somebody has not finished and a form somebody has got wrong,
     * and showing the second for the first is how a setup screen feels hostile.
     */
    fun awaitingCode(address: String): Boolean =
        address.contains(MARKER) && split(address).code.isBlank()

    /** The advertised share prefix, which is where the typed part ends and the secret begins. */
    const val MARKER = "/a/"
}
