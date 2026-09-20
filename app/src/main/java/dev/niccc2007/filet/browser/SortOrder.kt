package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.SortKey

/**
 * What the sort direction toggle means, and what it should be called.
 *
 * Bug identified: one boolean drove four comparators that were all written ascending, and it
 * simply reversed whichever one was active. So turning on "Descending" gave newest-first for
 * dates and largest-first for sizes - both useful - and **Z to A for names**, which is not a
 * list anybody wants and not what the same word produced on the other three.
 *
 * The flag now means **most relevant first**, and every comparator is written so its
 * unreversed order already is that: A to Z, newest, largest, type A to Z. One meaning for one
 * toggle, across all four.
 *
 * ## The naming problem that caused it
 *
 * Z to A genuinely *is* descending alphabetically, which is why it was written that way and
 * why the bug is not a typo - the code was correct about a word and wrong about the screen.
 * The word cannot carry both meanings, so it is no longer shown: [label] names the actual
 * direction for the sorter in use. Nobody has to work out what descending means for a name.
 */
object SortOrder {

    /**
     * The direction a fresh install starts in.
     *
     * Most relevant first. Names come out A to Z as they always did, and dates now come out
     * newest first, which is what a date sort is almost always wanted for.
     */
    const val DEFAULT_DESCENDING = true

    /**
     * What the toggle should read right now.
     *
     * @param descending whether the sort is in its most-relevant-first direction.
     */
    fun label(key: SortKey, descending: Boolean): String = when (key) {
        SortKey.NAME -> if (descending) "A to Z" else "Z to A"
        SortKey.SIZE -> if (descending) "Largest first" else "Smallest first"
        SortKey.MODIFIED -> if (descending) "Newest first" else "Oldest first"
        SortKey.TYPE -> if (descending) "Type A to Z" else "Type Z to A"
    }

    /** One line for the settings row, which has no sort key in front of it to refer to. */
    fun settingsSummary(descending: Boolean): String =
        if (descending) "A to Z, newest first, largest first"
        else "Z to A, oldest first, smallest first"
}
