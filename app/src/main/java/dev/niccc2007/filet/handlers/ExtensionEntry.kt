package dev.niccc2007.filet.handlers

/**
 * Typing an extension into the default-opener settings.
 *
 * Two faults, both of which made the screen feel like it was arguing:
 *
 *  1. **An unknown type was refused.** Adding `mcaddon` reached a dead end saying nothing on
 *     the device could open it - a claim Filet has no grounds for. It comes from asking the
 *     package manager which apps declare a mime type, and an extension nothing has registered
 *     resolves to the wildcard, which is deliberately treated as "no answer". Minecraft opens
 *     `.mcaddon` perfectly well; it simply never told Android so. The list is now offered
 *     rather than withheld, exactly as the per-file opener already does.
 *  2. **Leading dots were eaten silently.** Typing `.mcaddon` stored `mcaddon` with no word
 *     said. That is right nearly always and wrong invisibly when it is wrong, so the
 *     correction is now offered instead of applied.
 *
 * The dot handling is here because it is a decision with three defensible answers and no way
 * to tell from inside which one was meant.
 */
object ExtensionEntry {

    /** What to do about the dots somebody typed. */
    enum class Choice {
        /** `..mcaddon` becomes `mcaddon`. What a file's extension actually is. */
        DROP_DOTS,

        /** `..mcaddon` becomes `.mcaddon`. One dot, as most people write an extension. */
        ONE_DOT,

        /** Exactly what was typed. Rare and occasionally correct, so it stays available. */
        KEEP,
    }

    /**
     * @param cleaned the extension with no leading dots, lowercased and trimmed. Always the
     *   value a file will actually be matched against.
     * @param typed what was entered, tidied of whitespace and case but with its dots intact.
     * @param leadingDots how many dots were typed in front.
     * @param askAboutDots whether the dots are worth raising. More than one always is; a
     *   single leading dot is how everybody writes an extension and is silently fine.
     */
    data class Verdict(
        val cleaned: String,
        val typed: String,
        val leadingDots: Int,
        val askAboutDots: Boolean,
    ) {
        /** Whether this is usable at all. */
        val valid: Boolean get() = cleaned.isNotEmpty()
    }

    /** The longest extension worth accepting. Long enough for `sqlite3` and `webmanifest`. */
    const val MAX = 16

    fun inspect(raw: String): Verdict {
        val tidy = raw.trim().lowercase().take(MAX + 4)
        val dots = tidy.takeWhile { it == '.' }.length
        val cleaned = tidy.dropWhile { it == '.' }
            // Only the leading dots are a question. A `tar.gz` typed in full keeps its inner
            // dot, because that is a real compound extension rather than a slip.
            .trim()
            .take(MAX)
        return Verdict(
            cleaned = cleaned,
            typed = tidy,
            leadingDots = dots,
            // One dot is how an extension is written and needs no conversation. Two or more is
            // a slip worth mentioning, which is the case that was reported.
            askAboutDots = dots > 1 && cleaned.isNotEmpty(),
        )
    }

    /** What gets stored for a given answer. */
    fun resolve(verdict: Verdict, choice: Choice): String = when (choice) {
        Choice.DROP_DOTS -> verdict.cleaned
        Choice.ONE_DOT -> "." + verdict.cleaned
        Choice.KEEP -> verdict.typed
    }

    /** The sentence shown when the dots are worth raising. */
    fun dotQuestion(verdict: Verdict): String =
        "You typed ${verdict.leadingDots} dots in front of \"${verdict.cleaned}\". " +
            "An extension is usually stored without any."
}
