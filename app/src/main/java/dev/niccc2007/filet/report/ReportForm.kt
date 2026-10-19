package dev.niccc2007.filet.report

/**
 * The decisions behind the report form, with no Android in them.
 *
 * Everything here can be wrong in a way that matters - a report sent with a field the person
 * never saw, a log of four megabytes nobody was told about, a device line that names more than
 * it should - and none of it needs a screen to be right or wrong. So it is plain Kotlin, and the
 * screen and the client are thin layers over it.
 *
 * ## What the app knows about the server, all of it
 *
 * [ENDPOINT] and nothing else. No token, no key, no secret, no signing material, nothing that
 * identifies the repository's owner: the server creates the issue with its own credential, which
 * never leaves it. An APK is a public file and anything compiled into it is public; a credential
 * in here, however it was disguised, would be a write credential on a public repository.
 */
object ReportForm {

    /**
     * Where a report is sent. The only server-related value in the app.
     *
     * Blank until the backend is deployed. [ReportClient] refuses to send while it is blank and
     * says so on the form, rather than posting somewhere that does not exist.
     */
    const val ENDPOINT = ""

    /** Longest title GitHub accepts comfortably; past this it is a description, not a title. */
    const val TITLE_MAX = 200

    /** Description and steps together. A report is read, and nobody reads past this. */
    const val BODY_MAX = 20_000

    /** How many pictures one report may carry. */
    const val IMAGES_MAX = 5

    /** One picture, after it has been shrunk to fit. See [imageVerdict]. */
    const val IMAGE_MAX_BYTES = 2L * 1024 * 1024

    /** All pictures together. Base64 adds a third on the wire, so this is about 8 MB sent. */
    const val IMAGES_TOTAL_MAX_BYTES = 6L * 1024 * 1024

    /**
     * How much of a log is attached: the LAST this many bytes.
     *
     * The end of a run is where its failure is, and a full run can be five megabytes. Larger
     * logs are cut to their tail and the form says so before sending, with both sizes.
     */
    const val LOG_MAX_BYTES = 1L * 1024 * 1024

    /** The three kinds of report, and what the server is told. */
    enum class Kind(val wire: String, val label: String) {
        BUG("bug", "Something is broken"),
        FEATURE("feature", "An idea"),
        OTHER("other", "Something else"),
    }

    /** A log the person chose to attach, as the logger gave it. */
    data class Log(val name: String, val text: String, val fullBytes: Long)

    /** A picture, already shrunk, with its type as declared by the decoder that wrote it. */
    data class Image(val name: String, val mime: String, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Image && other.name == name && other.mime == mime &&
            other.bytes.contentEquals(bytes)
        override fun hashCode() = name.hashCode() * 31 + bytes.size
    }

    /**
     * Everything the form holds.
     *
     * [device] and [log] are null until somebody ticks them, and their ticks start off. Opt-in
     * means off until touched; a checkbox that starts on is the app deciding to publish
     * something on somebody's behalf.
     */
    data class Draft(
        val kind: Kind = Kind.BUG,
        val title: String = "",
        val description: String = "",
        val steps: String = "",
        val device: String? = null,
        val log: Log? = null,
        val images: List<Image> = emptyList(),
    )

    /** Why Send is not available, or null when it is. */
    fun blocker(d: Draft): String? = when {
        d.title.isBlank() -> "Add a title"
        d.description.isBlank() -> "Add a description"
        d.title.length > TITLE_MAX -> "The title is over $TITLE_MAX characters"
        d.description.length + d.steps.length > BODY_MAX -> "The description is over ${BODY_MAX / 1000}k characters"
        d.images.size > IMAGES_MAX -> "Up to $IMAGES_MAX pictures"
        d.images.sumOf { it.bytes.size.toLong() } > IMAGES_TOTAL_MAX_BYTES -> "The pictures are over ${mb(IMAGES_TOTAL_MAX_BYTES)} together"
        else -> null
    }

    fun canSend(d: Draft): Boolean = blocker(d) == null

    /** Whether another picture of [size] bytes may be added to [current]. */
    sealed interface ImageVerdict {
        data object Ok : ImageVerdict
        data class No(val why: String) : ImageVerdict
    }

    /** @param current the sizes of the pictures already on the report, in bytes. */
    fun imageVerdict(current: List<Long>, size: Long): ImageVerdict = when {
        current.size >= IMAGES_MAX -> ImageVerdict.No("Up to $IMAGES_MAX pictures in one report")
        size > IMAGE_MAX_BYTES -> ImageVerdict.No("That picture is over ${mb(IMAGE_MAX_BYTES)} even after shrinking")
        current.sum() + size > IMAGES_TOTAL_MAX_BYTES ->
            ImageVerdict.No("That would put the pictures over ${mb(IMAGES_TOTAL_MAX_BYTES)} together")
        else -> ImageVerdict.Ok
    }

    /**
     * The tail of a log that fits, and a sentence saying what was cut.
     *
     * The sentence is shown on the form before sending. Sending four megabytes without saying
     * so is the failure this exists to prevent; cutting it without saying so is the other one.
     */
    fun fitLog(text: String, max: Long = LOG_MAX_BYTES): Pair<String, String?> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= max) return text to null
        // Cut on a line boundary so the first line attached is a whole line - unless the cut
        // already landed on one, when skipping to the next newline would lose a good line.
        val start = (bytes.size - max).toInt()
        val tail = String(bytes, start, max.toInt(), Charsets.UTF_8)
        val clean = if (bytes[start - 1] == '\n'.code.toByte()) tail else tail.substringAfter('\n', tail)
        return clean to "Only the last ${mb(max)} of ${mb(bytes.size.toLong())} - the end of a run is where a failure is."
    }

    /**
     * What gets published about the phone, as the person will see it before sending.
     *
     * A list of lines and not a free-form dump, because every line here goes onto a public
     * page under somebody's name. Nothing identifies the phone itself: no serial, no Android ID,
     * no account, no network name, no IP address. Make, model, Android version and the app's
     * version are what a fault depends on; the rest is what a heat or lag report needs.
     */
    data class Device(
        val manufacturer: String,
        val model: String,
        val android: String,
        val sdk: Int,
        val abis: List<String>,
        val appVersion: String,
        val appCode: Int,
        val flavour: String,
        val ramMb: Long?,
        val lowRam: Boolean?,
        val thermal: String?,
        val batterySaver: Boolean?,
        val screen: String?,
        val locale: String?,
    )

    fun deviceText(d: Device): String = buildList {
        add("Phone: " + d.manufacturer.trim() + " " + d.model.trim())
        add("Android: " + d.android + " (API " + d.sdk + ")")
        add("Filet: " + d.appVersion + " (" + d.appCode + ", " + d.flavour + ")")
        add("Architecture: " + d.abis.joinToString(", ").ifEmpty { "unknown" })
        d.ramMb?.let { add("Memory: " + it + " MB" + if (d.lowRam == true) ", low-RAM device" else "") }
        d.thermal?.let { add("Thermal state: $it") }
        d.batterySaver?.let { add("Battery saver: " + if (it) "on" else "off") }
        d.screen?.let { add("Screen: $it") }
        d.locale?.let { add("Language: $it") }
    }.joinToString("\n")

    /**
     * The issue text the server publishes, built here so the preview and the request agree.
     *
     * The server adds nothing of its own to the body beyond a footer saying it came from the
     * app; what the person reviewed is what is posted.
     */
    fun issueBody(d: Draft): String = buildString {
        append(d.description.trim())
        if (d.steps.isNotBlank()) {
            append("\n\n### Steps to reproduce\n\n")
            append(d.steps.trim())
        }
        d.device?.takeIf { it.isNotBlank() }?.let {
            append("\n\n### Device\n\n```\n")
            append(it.trim())
            append("\n```")
        }
    }

    /**
     * The request, as JSON text.
     *
     * Written by hand rather than through `org.json`, because the unit-test `android.jar` ships
     * that package as stubs that throw - and the request body is the one thing here that most
     * needs a test.
     *
     * @param installId a random id made once per install, never derived from the device. It is
     *   what the server rate-limits on, alongside the address the request came from.
     * @param submissionId made once per draft and kept across retries, so a retry after a
     *   timeout that did in fact reach the server is recognised rather than filed twice.
     * @param base64 the encoder, passed in so a test does not need `android.util.Base64`.
     */
    fun requestJson(
        d: Draft,
        installId: String,
        submissionId: String,
        base64: (ByteArray) -> String,
    ): String = buildString {
        append('{')
        field("v", 1); append(',')
        field("installId", installId); append(',')
        field("submissionId", submissionId); append(',')
        field("type", d.kind.wire); append(',')
        field("title", d.title.trim()); append(',')
        field("body", issueBody(d)); append(',')
        append("\"device\":"); if (d.device.isNullOrBlank()) append("null") else str(d.device.trim()); append(',')
        append("\"log\":")
        val log = d.log
        if (log == null) {
            append("null")
        } else {
            append('{')
            field("name", log.name); append(',')
            field("text", log.text); append(',')
            field("fullBytes", log.fullBytes)
            append('}')
        }
        append(",\"images\":[")
        d.images.forEachIndexed { i, img ->
            if (i > 0) append(',')
            append('{')
            field("name", img.name); append(',')
            field("mime", img.mime); append(',')
            field("data", base64(img.bytes))
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.field(name: String, value: String) { str(name); append(':'); str(value) }
    private fun StringBuilder.field(name: String, value: Number) { str(name); append(':'); append(value) }

    private fun StringBuilder.str(s: String) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(String.format("%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * A title to start a crash report from, taken from the crash report's own trace.
     *
     * The first line of a stack trace is `fully.qualified.Exception: message`. The package says
     * nothing a reader can use and pushes the message out of the title, so the class is cut to
     * its simple name. A suggestion only: it lands in an editable field, and nothing is sent
     * until somebody presses Send.
     */
    fun crashTitle(report: String): String {
        val line = report.lineSequence().firstOrNull {
            Regex("""^[\w.$]+(Exception|Error|Throwable)\b""").containsMatchIn(it.trim())
        }?.trim() ?: return "Crash"
        val cls = line.substringBefore(':').substringAfterLast('.')
        val msg = line.substringAfter(':', "").trim()
        val title = if (msg.isEmpty()) "Crash: $cls" else "Crash: $cls: $msg"
        return if (title.length <= 120) title else title.take(119) + "…"
    }

    fun mb(bytes: Long): String {
        val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
        return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
    }

    /** "1.2 MB" or "340 KB": for the size beside a log or a picture. */
    fun size(bytes: Long): String = if (bytes >= 1024 * 1024) mb(bytes) else "${(bytes + 1023) / 1024} KB"

    /** What the server answered. */
    sealed interface Outcome {
        data class Filed(val url: String) : Outcome
        /** [retryable] is false for a refusal that will not change on a retry - a rejected picture. */
        data class Failed(val message: String, val retryable: Boolean) : Outcome
    }

    /**
     * The words for a failure, from the server's error code.
     *
     * Every one of them says what to do next. "Something went wrong" is the message that gets a
     * report abandoned, and this screen exists to get reports sent.
     */
    fun failureText(code: String?, httpStatus: Int, serverMessage: String?): Outcome.Failed = when (code) {
        "rate_limited" -> Outcome.Failed(
            "Too many reports from this phone just now. Your report is kept here - try again later.",
            retryable = true,
        )
        "too_large" -> Outcome.Failed(
            "The server turned this down as too large. Remove a picture or the log and send again.",
            retryable = false,
        )
        "invalid", "bad_attachment" -> Outcome.Failed(
            (serverMessage ?: "The server could not accept part of this report.") + " Nothing was filed.",
            retryable = false,
        )
        else -> when {
            httpStatus == 0 -> Outcome.Failed(
                "Could not reach the server. Check the connection - your report is kept here.",
                retryable = true,
            )
            httpStatus >= 500 -> Outcome.Failed(
                "The server had a problem filing it. Your report is kept here - try again.",
                retryable = true,
            )
            else -> Outcome.Failed(
                (serverMessage ?: "The server refused the report ($httpStatus).") + " Your report is kept here.",
                retryable = true,
            )
        }
    }
}
