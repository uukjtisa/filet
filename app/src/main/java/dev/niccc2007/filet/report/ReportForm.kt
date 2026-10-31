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
     * Set at build time from `filet.reportEndpoint` in `local.properties` (see app/build.gradle.kts),
     * blank when it is not set. [ReportClient] refuses to send while it is blank and says so on the
     * form, rather than posting somewhere that does not exist. A URL, not a secret: everything
     * that grants anything lives on the server.
     */
    val ENDPOINT: String = dev.niccc2007.filet.BuildConfig.REPORT_ENDPOINT

    /**
     * HTTPS, or plain HTTP to this phone's own loopback - the test server reached over
     * `adb reverse`, where nothing leaves the cable. Anything else is refused before sending.
     */
    fun endpointIsSafe(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith("https://")) return true
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host == "localhost"
    }

    // ── the server's limits ──
    //
    // Every one of these is the SERVER's number (report-api/lib/config.mjs), and
    // tools/check-report-contract.mjs fails the build if the two drift. They used to be the app's
    // own guesses - a 200-character title, five 2 MB pictures, a megabyte of log - and the server
    // refused all of them, so the form let somebody build a report that could never be filed.

    const val TITLE_MIN = 3
    const val TITLE_MAX = 120

    /** Description and steps together, as the server counts its `body`. */
    const val BODY_MIN = 10
    const val BODY_MAX = 10_000

    /** The phone details, as the server counts its `device`. */
    const val DEVICE_MAX = 2_000

    const val IMAGES_MAX = 3

    /** One picture, after it has been shrunk to fit. See [imageVerdict]. */
    const val IMAGE_MAX_BYTES = 1_000_000L

    /** All pictures together: three at the most, and base64 adds a third on the wire. */
    const val IMAGES_TOTAL_MAX_BYTES = 3_000_000L

    /**
     * How much of a log is attached: the LAST this much.
     *
     * The server keeps 30,000 characters from the end, because the end of a run is where its
     * failure is. Cut here to the same size, so what the form says will be published is what is.
     */
    const val LOG_MAX_BYTES = 30_000L

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
        /**
         * The heat and lag snapshot, when added. Travels with the log rather than the phone
         * details: it is a few thousand characters, and the server holds the details to
         * [DEVICE_MAX].
         */
        val snapshot: String? = null,
    )

    /** Why Send is not available, or null when it is. */
    fun blocker(d: Draft): String? = when {
        d.title.isBlank() -> "Add a title"
        d.description.isBlank() -> "Add a description"
        d.title.trim().length < TITLE_MIN -> "The title needs at least $TITLE_MIN characters"
        d.title.trim().length > TITLE_MAX -> "The title is over $TITLE_MAX characters"
        requestBody(d).length < BODY_MIN -> "Say a little more - at least $BODY_MIN characters"
        requestBody(d).length > BODY_MAX -> "The description is over ${BODY_MAX / 1000}k characters"
        (d.device?.trim()?.length ?: 0) > DEVICE_MAX -> "The phone details are over $DEVICE_MAX characters"
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
        // The page you get from "Open on GitHub instead", and the form's own preview: everything
        // in one body, because there is no server to lay the parts out.
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
     * The `body` the server receives: what was written, and nothing else.
     *
     * Not [issueBody]. The phone details travel in their own `device` field, which the server
     * lays out under its own heading - folding them into `body` as well put them on the issue
     * twice and counted them against the description's limit.
     */
    fun requestBody(d: Draft): String = buildString {
        append(d.description.trim())
        if (d.steps.isNotBlank()) {
            append("\n\n### Steps to reproduce\n\n")
            append(d.steps.trim())
        }
    }

    /**
     * The `log` the server receives: the snapshot whole, then as much of the chosen log as fits.
     * Null when neither was added.
     */
    fun wireLog(d: Draft): String? {
        val snap = d.snapshot?.trim()?.takeIf { it.isNotEmpty() }?.let { "[heat and lag snapshot]\n$it" }
        val room = LOG_MAX_BYTES - (snap?.toByteArray()?.size?.plus(2) ?: 0)
        val log = d.log?.let { l ->
            // The header line is written after the cut, so its room is kept back first -
            // otherwise the cut fits and the header pushes the whole thing past the limit.
            val header = HEADER_ROOM
            if (room <= header + 200) null else {
                val (fitted, _) = fitLog(l.text, room - header)
                logText(l.copy(text = fitted))
            }
        }
        return listOfNotNull(snap, log).joinToString("\n\n").ifEmpty { null }
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
        field("body", requestBody(d)); append(',')
        append("\"device\":"); if (d.device.isNullOrBlank()) append("null") else str(d.device.trim()); append(',')
        // TEXT, not an object. The server refuses anything else with `invalid_field`, and this
        // used to send `{name, text, fullBytes}` - so every report with a log attached was turned
        // away, and marked retryable, so "Try again" failed the same way for ever. Which log it
        // was rides in its first line, where the reader of the issue can see it.
        append("\"log\":")
        val log = wireLog(d)
        if (log == null) append("null") else str(log)
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

    /** Kept back for [logText]'s first line, which names the log and says how much of it came. */
    private const val HEADER_ROOM = 160L

    /** The log as the server receives it: its name and size on the first line, then the text. */
    fun logText(log: Log): String {
        val cut = if (log.fullBytes > log.text.toByteArray().size) ", last " + size(log.text.toByteArray().size.toLong()) + " of " + size(log.fullBytes) else ""
        return "[" + log.name + cut + "]\n" + log.text
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
    /**
     * What a response means, from the fields read out of it. Pure, so every case in the server's
     * table (`report-api/README.md`, "Request and response") has a test here.
     *
     * Success is `issue.url`: `{"ok": true, "issue": {"number": 42, "url": "..."}}`. This used to
     * read a top-level `url`, which the server never sends - so a report that WAS filed came back
     * as "answered without a link", and the reporter was told to check before sending again.
     * A replayed draft answers 200 with the same body, and is the same success.
     */
    fun outcomeOf(httpStatus: Int, issueUrl: String?, code: String?, serverMessage: String?, retryAfter: Long?): Outcome {
        if (httpStatus in 200..299) {
            val url = issueUrl.orEmpty()
            return if (url.startsWith("https://github.com/")) {
                Outcome.Filed(url)
            } else {
                Outcome.Failed(
                    "The server answered without a link to the issue. It may have been filed - " +
                        "check the project's issues before sending again.",
                    retryable = false,
                )
            }
        }
        return failureText(code, httpStatus, serverMessage, retryAfter)
    }

    /** "in 30 minutes", from a Retry-After in seconds. */
    fun later(retryAfter: Long?): String = when {
        retryAfter == null || retryAfter <= 0 -> "later"
        retryAfter < 90 -> "in a minute"
        retryAfter < 3600 -> "in ${(retryAfter + 59) / 60} minutes"
        else -> "in ${(retryAfter + 3599) / 3600} hours"
    }

    fun failureText(code: String?, httpStatus: Int, serverMessage: String?, retryAfter: Long? = null): Outcome.Failed = when (code) {
        "rate_limited" -> Outcome.Failed(
            "Too many reports just now. Your report is kept here - try again ${later(retryAfter)}.",
            retryable = true,
        )
        "in_progress" -> Outcome.Failed(
            "This report is still being filed. Try again in a few seconds - it will not be filed twice.",
            retryable = true,
        )
        "duplicate" -> Outcome.Failed(
            "This exact report was already filed in the last day, so it was not filed again.",
            retryable = false,
        )
        "request_too_large", "too_many_images", "image_too_large" -> Outcome.Failed(
            "The server turned this down as too large. Remove a picture or the log and send again.",
            retryable = false,
        )
        "image_type", "image_corrupt" -> Outcome.Failed(
            "The server could not read one of the pictures. Remove it and send again.",
            retryable = false,
        )
        "invalid_field", "bad_json", "unsupported_media_type" -> Outcome.Failed(
            (serverMessage ?: "The server could not accept part of this report.") + " Nothing was filed.",
            retryable = false,
        )
        "not_configured" -> Outcome.Failed(
            "The report server is not set up yet. Your report is kept here; Open on GitHub sends it there instead.",
            retryable = false,
        )
        "limiter_unavailable", "upstream_busy", "upstream_failed" -> Outcome.Failed(
            "The report server is busy. Your report is kept here - try again ${later(retryAfter)}.",
            retryable = true,
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
