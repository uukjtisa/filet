package dev.niccc2007.filet.browser

/**
 * What the bottom nav bar does while a selection is running.
 *
 * It used to do nothing, and that was the fault: the selection bar appeared ABOVE a nav bar
 * that stayed at full height, so two full-width bands sat over the file list at the one
 * moment you most want to see the file list. Worse than the height, every destination in the
 * lower band abandons the selection you are in the middle of making - it was chrome offering
 * to throw away your work, drawn at the size of a headline.
 *
 * Hiding it outright fixes that and nothing is stranded by it: `Clear` is pinned on the right
 * of the selection bar where it cannot scroll away, and system Back clears the selection
 * before it does anything else.
 *
 * But "it vanished" is its own surprise - reported within a minute of it shipping - so this is
 * a choice rather than a decision made for everybody, and the middle value is the one that
 * actually answers the complaint: the bar stays where it is and drops its captions, which is
 * most of the height for none of the meaning. The icons are the part you aim at; the 9.5sp
 * word under each one is what makes the band tall.
 */
enum class NavDuringSelection(val id: String, val label: String) {
    /** No nav bar at all until the selection ends. */
    HIDDEN("hidden", "Hidden"),

    /** Still there, icons only - about half the height. */
    COMPACT("compact", "Compact"),

    /** Exactly as it is the rest of the time. */
    FULL("full", "Full");

    companion object {
        val DEFAULT = HIDDEN

        /**
         * Unknown and absent both mean the default.
         *
         * Settings written by a future version land here on a downgrade, and a stored value
         * nobody recognises must not be a crash or an empty bar.
         */
        fun of(stored: String?): NavDuringSelection =
            entries.firstOrNull { it.id == stored } ?: DEFAULT

        /** The line under the control, which has to say what the current choice COSTS. */
        fun summary(mode: NavDuringSelection): String = when (mode) {
            HIDDEN -> "The nav bar steps aside, so only the selection bar covers the list. " +
                "Clear is pinned on the bar and Back still ends the selection."
            COMPACT -> "The nav bar stays, without its labels. Half the height, same targets."
            FULL -> "Both bars at full height. Tapping a destination drops the selection."
        }
    }
}
