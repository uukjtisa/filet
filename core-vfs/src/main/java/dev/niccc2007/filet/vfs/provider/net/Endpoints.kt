package dev.niccc2007.filet.vfs.provider.net

/**
 * One remote, several ways to reach it.
 *
 * ## The thing this fixes
 *
 * A saved remote used to BE an address, so the same tablet on home Wi-Fi and on a phone hotspot
 * was two entries: two sets of credentials, two storage cards, and a new one to type out every
 * time the network changed. A device is not its IP address. It has an identity, and addresses
 * are routes to it.
 *
 * ## Ordering, which is the whole decision
 *
 * 1. **The one that worked last, first.** Networks are sticky - the address that answered a
 *    minute ago is overwhelmingly likely to answer now, so the common case costs one request
 *    rather than a sweep.
 * 2. **Then the declared order**, primary first, because that is the one chosen deliberately.
 * 3. **Learned addresses last.** They were inferred; something typed outranks something guessed.
 *
 * De-duplicated case-insensitively, because a host is not case-sensitive and trying the same
 * box twice under two spellings is two timeouts for one answer.
 */
object Endpoints {

    /**
     * Addresses to try, best first.
     *
     * @param primary the address typed into the entry. Blank is tolerated rather than rejected
     *   - a remote that has only ever been discovered has no typed address at all.
     * @param extra additional addresses, typed or learned, in declaration order.
     * @param lastGood the address that most recently answered, if any.
     */
    fun ordered(primary: String, extra: List<String>, lastGood: String?): List<String> {
        val out = LinkedHashSet<String>()
        fun add(raw: String?) {
            val a = raw?.trim().orEmpty()
            if (a.isEmpty()) return
            // LinkedHashSet keeps first-seen order; the lowercase key is what makes two
            // spellings of one host count as one entry.
            if (out.none { it.equals(a, ignoreCase = true) }) out += a
        }
        add(lastGood)
        add(primary)
        extra.forEach(::add)
        return out.toList()
    }

    /**
     * Parse the addresses field, which is one address per line.
     *
     * Blank lines and stray whitespace are dropped rather than becoming empty entries that
     * each cost a timeout. Commas are accepted as well as newlines, because a field that looks
     * like a list invites both and refusing one of them is a papercut with no upside.
     */
    fun parse(raw: String): List<String> =
        raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /** Back to the field's text, one per line. */
    fun format(addresses: List<String>): String = addresses.joinToString("\n")

    /**
     * Add a newly discovered address to what is already known.
     *
     * @return the new list, or null when there is nothing to add - so a caller can tell
     *   "learned something" from "saw the same thing again" without comparing lists, and avoid
     *   a write to storage on every single discovery packet.
     *
     * Appended rather than promoted: a discovered address has not answered a real request yet.
     * It earns its place at the front by working, through [lastGood].
     */
    fun learn(known: List<String>, discovered: String): List<String>? {
        val a = discovered.trim()
        if (a.isEmpty()) return null
        if (known.any { it.equals(a, ignoreCase = true) }) return null
        if (known.size >= MAX) return null
        return known + a
    }

    /**
     * The address to try after [current] stopped answering.
     *
     * Wraps, so a remote that comes back on an address already tried is found again rather than
     * being stuck past the end of the list. An unknown [current] - a stale value from before the
     * addresses were edited - starts again at the front rather than returning null, because
     * "this address is not in the list" is the one case where the list is certainly better.
     *
     * @return null when there is nowhere else to go: an empty list, or a single address that is
     *   already the one in use. Null means *do not rotate*, and the caller then reports the real
     *   failure rather than a different address's failure.
     */
    fun after(candidates: List<String>, current: String?): String? {
        if (candidates.isEmpty()) return null
        val at = candidates.indexOfFirst { it.equals(current?.trim(), ignoreCase = true) }
        if (at < 0) return candidates.first()
        if (candidates.size < 2) return null
        return candidates[(at + 1) % candidates.size]
    }

    /**
     * A ceiling on how many addresses one remote may carry.
     *
     * Every address is a potential timeout when the remote is unreachable, so an unbounded list
     * that learns one per network change is a remote that gets slower to fail forever.
     */
    const val MAX = 8
}
