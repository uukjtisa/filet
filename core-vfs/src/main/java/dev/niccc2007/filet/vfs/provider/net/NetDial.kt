package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException

/**
 * Reaching a remote that has more than one address, for every protocol, once.
 *
 * ## Why it is one place
 *
 * A saved remote holds several addresses, and four providers have to try them. Written per
 * provider that is the same decision four times, and it was already wrong in the one place it
 * existed: WebDAV rotated inside the helper that BUILDS a connection, which performs no network
 * at all, so a dead address was never noticed and the second address was never tried. SMB, SFTP
 * and FTP did not try a second address either - they read the primary and stopped - so the field
 * offering to add more addresses did nothing for three of the four protocols.
 *
 * One declaration, one behaviour, and each provider says only which addresses and what to do
 * with one. [NetFailure] owns what counts as the address being at fault, and [Endpoints] owns
 * the order they are tried in. Both are pure and both have tests.
 *
 * ## Sticky, without a second piece of state
 *
 * There is no in-memory "current address" here. The address that answered is written to the
 * connection as `lastGood`, and [Endpoints.ordered] puts `lastGood` first - so the next
 * operation starts where the last one succeeded, and a restart does too. One fact in one place
 * instead of a cache that can disagree with storage.
 */
object NetDial {

    /**
     * Try [hosts] in order until one is reached, and run [block] against it.
     *
     * @param onGood called with the address that worked, which is how it earns being tried first
     *   next time. Called for a refusal as well as a success: a share that answers *no* is a
     *   share that answered, and the address is fine.
     * @return whatever [block] returned.
     *
     * Bounded by the list, so an unreachable remote fails in one pass rather than cycling. A
     * failure that is an ANSWER - wrong code, no such file, read-only - is rethrown untouched,
     * because trying another address would replace the reason with a timeout from elsewhere.
     */
    inline fun <T> over(
        hosts: List<String>,
        path: VPath,
        onGood: (String) -> Unit,
        block: (String) -> T,
    ): T {
        if (hosts.isEmpty()) throw VfsException.NotFound(path)
        var last: Throwable? = null
        for (h in hosts) {
            try {
                val out = block(h)
                onGood(h)
                return out
            } catch (t: Throwable) {
                if (!NetFailure.isAddressFailure(t)) {
                    if (NetFailure.isAnswer(t)) onGood(h)
                    throw t
                }
                last = t
            }
        }
        // Every address failed to answer. The first failure is as good a report as the last and
        // the last is the one still in hand, so that is the one raised.
        throw last ?: VfsException.NotFound(path)
    }
}
