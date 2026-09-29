package dev.niccc2007.filet.webdav

/**
 * How much room the other side is told there is.
 *
 * ## Why a mounted phone shows the wrong size
 *
 * Nothing was being sent. RFC 4331 adds two properties to a PROPFIND of a collection:
 *
 *     <D:quota-used-bytes>      how much is used
 *     <D:quota-available-bytes> how much more will fit
 *
 * They are what Explorer draws the used/free bar from on a mapped drive. Without them Explorer has
 * no size at all for the mount and falls back to something else - which is why a phone mounted on
 * a PC showed the **C: drive's** numbers. It was not mirroring anything on purpose; the space was
 * empty and Windows filled it.
 *
 * ## The two decisions here
 *
 * **Available is the smaller of the real free space and any cap the share is under.** If a share
 * may only write another 2 GB and the phone has 40 GB free, the honest answer is 2 GB - otherwise
 * a desktop happily starts a 5 GB copy that is guaranteed to fail halfway, which is a worse
 * failure than being told up front.
 *
 * **Used is the volume's used bytes, not the share's.** Adding up a subtree means walking it on
 * every PROPFIND of every folder, and that is exactly the mistake that made the indexed views take
 * 3.3 seconds to return nine entries. A share rooted at a folder reports the volume it lives on,
 * which is what "free space" means to the thing asking.
 */
object DavQuota {

    /**
     * What to advertise, or null when the size is not known.
     *
     * Null rather than zero: zero bytes free is a meaningful and alarming answer, and a client
     * that reads it will refuse to copy anything. A missing property is the honest way to say
     * "no idea", and clients handle its absence - that is the state this whole thing was in.
     */
    data class Report(val usedBytes: Long, val availableBytes: Long)

    /**
     * @param totalBytes the volume's size, or null when it could not be read.
     * @param usableBytes what is actually writable on it, or null.
     * @param capRemaining bytes left under a transfer cap, or null when there is no cap.
     */
    fun of(totalBytes: Long?, usableBytes: Long?, capRemaining: Long? = null): Report? {
        if (totalBytes == null || usableBytes == null) return null
        if (totalBytes <= 0 || usableBytes < 0) return null
        // A volume cannot have more free than it has in total; a provider that reports otherwise
        // is wrong rather than generous, and passing it on makes the client's arithmetic wrong.
        val usable = usableBytes.coerceAtMost(totalBytes)
        val used = (totalBytes - usable).coerceAtLeast(0)
        val available = if (capRemaining == null) usable else minOf(usable, capRemaining.coerceAtLeast(0))
        return Report(usedBytes = used, availableBytes = available)
    }

    /** The two properties, ready to drop into a `<D:prop>` block. Empty when there is nothing to say. */
    fun xml(report: Report?): String {
        if (report == null) return ""
        return "<D:quota-used-bytes>" + report.usedBytes + "</D:quota-used-bytes>\n" +
            "<D:quota-available-bytes>" + report.availableBytes + "</D:quota-available-bytes>\n"
    }
}
