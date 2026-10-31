package dev.niccc2007.filet.docs

/**
 * How documents are read, for every document at once.
 *
 * One setting for all of them rather than one per file, because these are about the reader's
 * eyes and hands, not about the file: somebody who needs larger text needs it in the next
 * README too. Kept in `Prefs`, offered under Settings, Reading and in the reader's own Aa
 * panel, which move the same values.
 *
 * @param size the text size as a percentage, which IS the pinch: pinching and the Aa steps move
 *   this one number, so neither can leave the other behind.
 * @param column whether text is held to a readable width. Tables and code still take the width.
 * @param tables how a table sets its columns. See [TableMode].
 * @param webPictures whether a picture on the web loads by itself, or waits for a tap.
 */
data class ReadingSettings(
    val size: Int = Reading.DEFAULT_SIZE,
    val column: Boolean = true,
    val tables: TableMode = TableMode.FIT,
    val webPictures: Boolean = true,
) {
    /** [size] as the zoom the reader draws at. */
    val zoom: Float get() = size / 100f
}

/** How a table sets its columns. */
enum class TableMode(val label: String) {
    /**
     * Wrap to fit, the way a browser lays out a table with no widths: every column at its widest
     * if that fits, at its narrowest and scrolled if even that does not, and in between the
     * spare width shared out by how much each column wanted. See [TableLayout].
     */
    FIT("Fit"),

    /** Every column at its widest, one line a cell, scrolled sideways. */
    COMFORTABLE("Comfortable"),

    /** Fitted, in smaller type with tighter cells - for a wide table on a narrow phone. */
    COMPACT("Compact"),
    ;

    companion object {
        fun of(name: String?): TableMode = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/** The arithmetic of the reading settings, apart from Compose and storage. */
object Reading {
    const val DEFAULT_SIZE = 100

    /** What the A- and A+ steps move between. The pinch can go past them either way. */
    val STEPS = intArrayOf(80, 90, 100, 110, 120, 130, 140, 150, 160)

    /** The pinch's own bounds, as a percentage: [DocZoom.MIN] to [DocZoom.MAX]. */
    val PINCH_RANGE: IntRange get() = (DocZoom.MIN * 100).toInt()..(DocZoom.MAX * 100).toInt()

    /** A stored size, kept to what the reader can draw - a hand-edited settings file can say anything. */
    fun clampSize(size: Int): Int = size.coerceIn(PINCH_RANGE)

    /** The next step up from [size], or null at or past the last step. */
    fun larger(size: Int): Int? = STEPS.firstOrNull { it > size }

    /** The next step down from [size], or null at or below the first. */
    fun smaller(size: Int): Int? = STEPS.lastOrNull { it < size }

    /** A zoom from the pinch, as the percentage it is stored as. */
    fun sizeOf(zoom: Float): Int = clampSize(Math.round(zoom * 100f))
}
