package dev.niccc2007.filet.log

import java.util.concurrent.CopyOnWriteArraySet

/**
 * What a log line may not carry, removed in one place.
 *
 * Every line the logger writes passes through [line] on the log thread, and every run read back
 * for an issue report passes through it again - so a caller that forgets is covered, and a run
 * written before a rule existed is covered when it is attached. The rules are here and nowhere
 * else because "each caller redacts its own" is the same leak the first time any one of them
 * does not.
 *
 * ## What goes, and what stays
 *
 * - **Gone:** passwords, access codes, PINs, tokens, keys, cookies and sessions written as
 *   `name=value`, `name: value` or JSON; the whole user-info part of a URL (`smb://anna:pw@host`
 *   loses both - a share's user name is very often a person's name); `Bearer`/`Basic` credentials;
 *   the password of a WiFi QR payload; recognisable token formats (GitHub, JWT, AWS, Slack, `sk-`);
 *   e-mail addresses; long mixed-case base64 runs; and any value registered with [secret].
 * - **Kept:** file paths, host names and LAN addresses. A path under `/storage/emulated/0` says
 *   which folder, not who; it is also the single most useful fact in a file manager's log.
 *
 * A third party's name in free text cannot be found by a pattern, which is why [secret] exists:
 * code that holds a value it knows to be private registers it once, and from then on it is
 * scrubbed from every line, including exception messages the code never wrote.
 */
object Redact {

    const val MASK = "‹redacted›"
    const val EMAIL_MASK = "‹email›"

    /** Lines longer than this are cut before any rule runs, which also bounds the rules' work. */
    const val MAX_LINE = 8_000

    private val secrets = CopyOnWriteArraySet<String>()

    /**
     * Scrub [value] from every line from now on. Values under four characters are ignored: a
     * two-digit code would erase every "12" in the log, and is not a secret worth that.
     */
    fun secret(value: String?) {
        if (value != null && value.length >= 4) secrets += value
    }

    /** For tests: the registry is process-wide. */
    internal fun forgetSecrets() = secrets.clear()

    private const val KEYS =
        "password|passwd|passphrase|pwd|pass|secret|token|access[ _-]?token|refresh[ _-]?token|" +
            "access[ _-]?code|accesscode|pin|psk|api[ _-]?key|apikey|private[ _-]?key|key|" +
            "authorization|auth|cookie|session|sessionid|sid|user|username|login|ssid"

    // A keyed value. The key is kept so the line still says what was there.
    private val KEYED = Regex(
        """(\b(?:$KEYS)\b["']?\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&)\]}"']+)""",
        RegexOption.IGNORE_CASE,
    )

    // scheme://user[:password]@ - both halves go.
    private val USERINFO = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*)://[^\s/@]+@""")

    private val BEARER = Regex("""(\b(?:bearer|basic)\s+)[A-Za-z0-9._~+/=-]{6,}""", RegexOption.IGNORE_CASE)

    private val WIFI_QR = Regex("""(WIFI:[^\n]*?\bP:)((?:\\.|[^;\\\n])*)""", RegexOption.IGNORE_CASE)

    private val KNOWN_TOKENS = Regex(
        "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|" +
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|" +
            "AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})",
    )

    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b""")

    // Forty characters of base64 with no separators. Filenames almost always carry `_`, `-`,
    // spaces or a dot inside that length; a credential almost never does. `/` is left out of
    // the class on purpose: with it, a long enough path is one forty-character run.
    private val LONG_TOKEN = Regex("""[A-Za-z0-9+=]{40,}""")

    /** The line with everything above removed. Never throws: a rule failing keeps the cut line. */
    fun line(s: String): String = try {
        var out = if (s.length > MAX_LINE) s.substring(0, MAX_LINE) + "… (" + (s.length - MAX_LINE) + " more chars)" else s
        for (v in secrets) if (out.contains(v)) out = out.replace(v, MASK)
        out = WIFI_QR.replace(out) { it.groupValues[1] + MASK }
        out = USERINFO.replace(out) { it.groupValues[1] + "://" + MASK + "@" }
        out = BEARER.replace(out) { it.groupValues[1] + MASK }
        out = KEYED.replace(out) { it.groupValues[1] + MASK }
        out = KNOWN_TOKENS.replace(out, MASK)
        out = EMAIL.replace(out, EMAIL_MASK)
        out = LONG_TOKEN.replace(out) { if (looksRandom(it.value)) MASK else it.value }
        out
    } catch (t: Throwable) {
        if (s.length > MAX_LINE) s.substring(0, MAX_LINE) else s
    }

    private fun looksRandom(s: String): Boolean =
        s.any { it.isUpperCase() } && s.any { it.isLowerCase() } && s.any { it.isDigit() }
}
