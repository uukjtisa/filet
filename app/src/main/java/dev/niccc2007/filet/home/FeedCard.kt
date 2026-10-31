package dev.niccc2007.filet.home

/**
 * How a Home feed card - New files, Recently opened - is drawn: how many rows, and how tight.
 *
 * Both are set in Home's edit mode, under the card's own row (see `SectionEditor`'s settings
 * slot). Pure, so what each density draws is a test rather than a screenshot.
 */
internal object FeedCard {

    /** The row counts offered. Eight is what both cards showed before it could be set. */
    val COUNTS = listOf(3, 5, 8, 12)
    const val DEFAULT_COUNT = 8

    /** The most any card can ask for, so the feed publishes that many and a card takes its own. */
    const val MAX_COUNT = 12

    /**
     * Past this, the second line folds into the first: "name · 2h". Below it the time keeps its
     * column on the right and the origin keeps its own line.
     */
    const val FOLD_AT = 0.8f

    /** [n] as one of [COUNTS] - a value typed into filet.json lands on the nearest offered. */
    fun snap(n: Int): Int = COUNTS.minBy { kotlin.math.abs(it - n) }

    /**
     * What a row looks like at [density], 0 Comfortable to 1 Compact.
     *
     * Comfortable is exactly the row as it was before density existed, so a phone that never
     * opens the editor sees nothing change. Every measure moves together along one line; only
     * the fold is a step, because a line cannot be half folded.
     */
    fun metrics(density: Float): Metrics {
        val d = density.coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float) = a + (b - a) * d
        return Metrics(
            padV = lerp(7f, 2.5f),
            thumb = lerp(30f, 20f),
            corner = lerp(9f, 6f),
            glyph = lerp(15f, 11f),
            gap = lerp(11f, 8f),
            nameSp = lerp(12.5f, 12f),
            folded = d >= FOLD_AT,
        )
    }

    /** One row's measures, in dp and sp. */
    data class Metrics(
        val padV: Float,
        val thumb: Float,
        val corner: Float,
        val glyph: Float,
        val gap: Float,
        val nameSp: Float,
        val folded: Boolean,
    )
}
