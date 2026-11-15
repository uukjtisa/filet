package dev.niccc2007.filet.portal

/**
 * The free file hosts Portal spreads a send over. One entry per host, so adding a host is one
 * entry here: where to post, what it calls the file field, how long it keeps files, and which
 * domains its links live on.
 *
 * The limits come from the hosts' published figures as gathered on 2026-11-15, and they change
 * without notice. Nothing relies on them beyond the plan: a host that refuses an upload hands
 * the part to the next one, so a host that shrank its limit costs a retry, not a failed send.
 */
enum class PortalHost(
    val id: String,
    val label: String,
    /** The largest file the host takes, in bytes, as sealed. */
    val maxBytes: Long,
    /** The longest the host keeps a file, in milliseconds. */
    val keepsUpTo: Long,
    /** Domains its download links may be on; a subdomain of one counts. */
    val domains: List<String>,
    private val endpoint: String,
    private val fileField: String,
) {
    LITTERBOX(
        "litterbox", "Litterbox", 1_000_000_000L, 72 * HOUR,
        listOf("litter.catbox.moe"),
        "https://litterbox.catbox.moe/resources/internals/api.php", "fileToUpload",
    ),
    ZERO_X0(
        "0x0", "0x0.st", 512L * 1024 * 1024, 30 * DAY,
        listOf("0x0.st"),
        "https://0x0.st", "file",
    ),
    UGUU(
        "uguu", "Uguu", 128L * 1024 * 1024, 3 * HOUR,
        listOf("uguu.se"),
        "https://uguu.se/upload?output=text", "files[]",
    ),
    TMPFILES(
        "tmpfiles", "tmpfiles", 100L * 1024 * 1024, 1 * HOUR,
        listOf("tmpfiles.org"),
        "https://tmpfiles.org/api/v1/upload", "file",
    );

    /** Whether the host keeps a file at least [wanted] milliseconds. */
    fun keeps(wanted: Long): Boolean = keepsUpTo >= wanted

    /** What the host actually keeps a file for when [wanted] is asked. */
    fun lifetime(wanted: Long): Long = when (this) {
        LITTERBOX -> LITTER_TIMES.first { it >= wanted.coerceAtMost(72 * HOUR) }
        ZERO_X0 -> wanted.coerceIn(HOUR, keepsUpTo)
        UGUU, TMPFILES -> keepsUpTo
    }

    /** The upload: where it goes, the form fields before the file, and the file's field name. */
    fun request(wanted: Long): Upload = when (this) {
        LITTERBOX -> Upload(
            endpoint,
            listOf("reqtype" to "fileupload", "time" to "${lifetime(wanted) / HOUR}h"),
            fileField,
        )
        // A secret link is long and unlisted; expires is in hours.
        ZERO_X0 -> Upload(endpoint, listOf("expires" to "${lifetime(wanted) / HOUR}", "secret" to "1"), fileField)
        UGUU, TMPFILES -> Upload(endpoint, emptyList(), fileField)
    }

    /**
     * The download link in a host's answer, or null when it gave none or one on a domain it does
     * not own. Read loosely, because hosts answer in plain text or JSON and escape slashes in it.
     */
    fun answer(body: String): String? {
        val url = URL_IN.find(body.replace("\\/", "/"))?.value?.trimEnd('.', ',', ';') ?: return null
        val direct = when (this) {
            // tmpfiles answers with its page; the file itself is under /dl/.
            TMPFILES -> url.replaceFirst(Regex("^https://tmpfiles\\.org/(?!dl/)"), "https://tmpfiles.org/dl/")
            else -> url
        }
        return direct.takeIf { owns(it) }
    }

    /** Whether [url] is an https link on one of this host's domains. */
    fun owns(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        if ('@' in host || ':' in host) return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    data class Upload(val url: String, val fields: List<Pair<String, String>>, val fileField: String)

    companion object {
        fun of(id: String): PortalHost? = entries.firstOrNull { it.id == id }

        /** Whether [url] is on any Portal host, the only places a scanned Portal link may send Filet. */
        fun anyOwns(url: String): Boolean = entries.any { it.owns(url) }

        private val URL_IN = Regex("""https://[^\s"'<>\\]+""")
        private val LITTER_TIMES = listOf(1 * HOUR, 12 * HOUR, 24 * HOUR, 72 * HOUR)
    }
}

const val HOUR = 60L * 60 * 1000
const val DAY = 24 * HOUR
