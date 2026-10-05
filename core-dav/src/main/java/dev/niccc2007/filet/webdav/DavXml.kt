package dev.niccc2007.filet.webdav

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The XML half of WebDAV.
 *
 * Built as strings rather than through a DOM because the shapes are fixed, tiny, and have to
 * come out byte-for-byte the way Explorer expects. The parts that are easy to get wrong and
 * expensive to debug over a network are all here, with tests:
 *
 *  - **Two date formats, and they are not interchangeable.** `getlastmodified` is an HTTP-date
 *    in GMT (RFC 1123); `creationdate` is ISO 8601. Explorer shows a blank or an epoch date
 *    when they are swapped, which looks like the file has no timestamp.
 *  - **A collection has no `getcontentlength`.** Sending one makes Explorer render a folder as
 *    a file of that size.
 *  - **Escaping is not optional.** A file named `A & B<1>.txt` produces invalid XML that
 *    Explorer answers by showing an empty folder rather than an error, so one badly named file
 *    hides the whole directory.
 *  - **207 lists what it could not answer as well as what it could**, in a second propstat
 *    with a 404. A client that asked for a property it did not get and was told nothing tends
 *    to re-ask forever.
 */
object DavXml {

    /** `Tue, 23 Sep 2026 14:05:00 GMT` - the only format `getlastmodified` may take. */
    fun httpDate(at: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(at))

    /** `2026-09-23T14:05:00Z` - the only format `creationdate` may take. */
    fun isoDate(at: Long): String = ISO_DATE.format(Instant.ofEpochMilli(at))

    /**
     * Built once, at class load, and shared.
     *
     * These were `SimpleDateFormat`, constructed inside each function - so a PROPFIND built TWO
     * formatters per directory entry. A folder of 1747 files therefore constructed 3,494 of them
     * plus 3,494 timezone lookups, and `SimpleDateFormat`'s constructor is not cheap on Android:
     * it parses the pattern and pulls locale data every time. It measured at about 2.8 of the 4.4
     * seconds that listing took, and it scaled with the folder - which is exactly how a listing
     * comes to feel like the network being slow when nothing has left the phone yet.
     *
     * `DateTimeFormatter` rather than a pooled `SimpleDateFormat` because this server answers on
     * four threads and `SimpleDateFormat` is not thread-safe. A shared one would have produced
     * mangled dates under concurrent listings, which Explorer reads as corrupt files rather than
     * as a bad date. `DateTimeFormatter` is immutable and safe to share.
     */
    private val HTTP_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    private val ISO_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

    /**
     * Escape text for an XML element.
     *
     * Characters XML cannot carry at all are dropped rather than escaped: a control byte in a
     * filename has no valid representation, and `&#x1;` is rejected by the parser just as
     * hard as the raw byte. Dropping loses a character from a name nobody typed on purpose;
     * emitting it loses the whole directory listing.
     */
    fun esc(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c == '"' -> out.append("&quot;")
                c == '\'' -> out.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> out.append(c)
                c.code < 0x20 -> Unit
                c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** One resource in a multi-status. */
    data class Entry(
        val href: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val name: String,
        /**
         * RFC 4331 free/used, for a collection. Null for files and where the size is unknown.
         *
         * On the entry rather than on the whole response because a PROPFIND answers for several
         * resources at once and only collections carry a quota.
         */
        val quota: DavQuota.Report? = null,
    )

    /**
     * A `207 Multi-Status` body for [entries].
     *
     * @param unknown property names the client asked for that this server does not have, which
     *   are reported as 404 inside the same response rather than omitted.
     */
    fun multiStatus(entries: List<Entry>, unknown: List<String> = emptyList()): String {
        val b = StringBuilder(256 + entries.size * 320)
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        b.append("<D:multistatus xmlns:D=\"DAV:\">\n")
        for (e in entries) {
            b.append("<D:response>\n")
            b.append("<D:href>").append(esc(e.href)).append("</D:href>\n")
            b.append("<D:propstat>\n<D:prop>\n")
            b.append("<D:displayname>").append(esc(e.name)).append("</D:displayname>\n")
            if (e.isDir) {
                b.append("<D:resourcetype><D:collection/></D:resourcetype>\n")
                // What Explorer draws the used/free bar from. Absent before, which is why
                // a mounted phone showed numbers belonging to the PC's own disk.
                b.append(DavQuota.xml(e.quota))
            } else {
                b.append("<D:resourcetype/>\n")
                // Only on a file. On a collection it makes Explorer draw a folder as a file.
                b.append("<D:getcontentlength>").append(e.size.coerceAtLeast(0))
                    .append("</D:getcontentlength>\n")
                b.append("<D:getcontenttype>").append(esc(contentType(e.name)))
                    .append("</D:getcontenttype>\n")
            }
            b.append("<D:getlastmodified>").append(httpDate(e.mtime)).append("</D:getlastmodified>\n")
            b.append("<D:creationdate>").append(isoDate(e.mtime)).append("</D:creationdate>\n")
            // Explorer reads this before it will offer to write, even on a read-only share.
            b.append("<D:supportedlock>\n")
            b.append("<D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
            b.append("<D:locktype><D:write/></D:locktype></D:lockentry>\n")
            b.append("</D:supportedlock>\n")
            b.append("</D:prop>\n<D:status>HTTP/1.1 200 OK</D:status>\n</D:propstat>\n")
            if (unknown.isNotEmpty()) {
                b.append("<D:propstat>\n<D:prop>\n")
                for (p in unknown) b.append("<D:").append(esc(p)).append("/>\n")
                b.append("</D:prop>\n<D:status>HTTP/1.1 404 Not Found</D:status>\n</D:propstat>\n")
            }
            b.append("</D:response>\n")
        }
        b.append("</D:multistatus>\n")
        return b.toString()
    }

    /** The body for a successful LOCK. */
    fun lockResponse(token: String, depth: String, timeoutSeconds: Int, owner: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<D:prop xmlns:D=\"DAV:\">\n<D:lockdiscovery>\n<D:activelock>\n" +
            "<D:locktype><D:write/></D:locktype>\n" +
            "<D:lockscope><D:exclusive/></D:lockscope>\n" +
            "<D:depth>" + esc(depth) + "</D:depth>\n" +
            "<D:owner>" + esc(owner) + "</D:owner>\n" +
            "<D:timeout>Second-" + timeoutSeconds + "</D:timeout>\n" +
            "<D:locktoken><D:href>" + esc(token) + "</D:href></D:locktoken>\n" +
            "</D:activelock>\n</D:lockdiscovery>\n</D:prop>\n"

    /**
     * Which properties a PROPFIND body asked for that this server does not carry.
     *
     * Deliberately a string scan and not a parser. The body is a handful of empty elements in
     * a known namespace, and adding an XML parser to the one component that listens - to read
     * a document an unauthenticated client controls - buys a parser's whole attack surface for
     * no capability. Anything not recognised is reported as 404, which is the same answer a
     * full parse would produce.
     */
    fun unknownProps(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val known = setOf(
            "displayname", "resourcetype", "getcontentlength", "getcontenttype",
            "getlastmodified", "creationdate", "supportedlock", "lockdiscovery",
            "allprop", "prop", "propfind", "getetag",
        )
        val out = LinkedHashSet<String>()
        for (m in Regex("<(?:[A-Za-z0-9]+:)?([A-Za-z][A-Za-z0-9]*)\\s*/?>").findAll(body)) {
            val name = m.groupValues[1]
            if (name.lowercase() !in known) out.add(name)
        }
        return out.toList()
    }

    /** Whether a PROPFIND body is an `allprop` request. An empty body means allprop too. */
    fun isAllProp(body: String): Boolean =
        body.isBlank() || Regex("<(?:[A-Za-z0-9]+:)?allprop\\s*/?>").containsMatchIn(body)

    /** A conservative content type, by extension. Explorer only uses it to pick an icon. */
    fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
