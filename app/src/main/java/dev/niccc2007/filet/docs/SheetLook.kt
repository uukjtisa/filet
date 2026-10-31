package dev.niccc2007.filet.docs

import kotlin.math.pow

/**
 * A workbook's colours: what its references name, and which ink can be read on them.
 *
 * Pure, no Android, and tested on its own - see `SheetLookTest`. Every function here is a place
 * a cell can come out the wrong colour without anything failing, which is the kind of mistake
 * that only a test catches.
 *
 * ## Where a colour comes from
 *
 * An XLSX names a colour one of four ways, and a reader that handles only the first draws most
 * of a real workbook's formatting as nothing:
 *
 * - `rgb="FF4472C4"` - the colour itself, alpha first.
 * - `theme="4" tint="0.4"` - a slot in the workbook's theme, lightened or darkened. This is what
 *   the desktop's own colour picker writes for every swatch in its top block, so it is the
 *   COMMON case, not the exotic one.
 * - `indexed="22"` - the legacy 64-entry palette, still written by older producers and by the
 *   desktop for a handful of defaults.
 * - `auto="1"` - automatic, which for a font means "the window text colour".
 */
internal object SheetLook {

    // ── themes ──

    /** The slots of a theme's colour scheme, in the order a `theme` attribute counts them. */
    val THEME_SLOTS = listOf(
        "lt1", "dk1", "lt2", "dk2",
        "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
        "hlink", "folHlink",
    )

    /**
     * The default Office theme, for a workbook that ships no theme part.
     *
     * Bug this guards against: the scheme lists its slots dark-first - `dk1`, `lt1`, `dk2`,
     * `lt2` - and the index counts them LIGHT-first, so `theme="0"` is white and `theme="1"` is
     * black. Indexing the scheme in file order inverts every header that uses the theme's first
     * four slots, which is most of them.
     */
    val OFFICE_THEME: IntArray = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE7E6E6.toInt(), 0xFF44546A.toInt(),
        0xFF4472C4.toInt(), 0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(),
        0xFF5B9BD5.toInt(), 0xFF70AD47.toInt(), 0xFF0563C1.toInt(), 0xFF954F72.toInt(),
    )

    /** A theme part's scheme by slot name, put in index order, with the default for any gap. */
    fun theme(bySlot: Map<String, Int>): IntArray =
        IntArray(THEME_SLOTS.size) { i -> bySlot[THEME_SLOTS[i]] ?: OFFICE_THEME[i] }

    // ── the legacy palette ──

    /** The 64 colours `indexed` counts into when the workbook does not redefine them. */
    val INDEXED: IntArray = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    ).let { rgb -> IntArray(rgb.size) { rgb[it] or OPAQUE } }

    /** Index 64 is the system foreground and 65 the system background: automatic, both. */
    const val SYSTEM_FOREGROUND = 64
    const val SYSTEM_BACKGROUND = 65

    /**
     * Palette entry [i], from the workbook's own `indexedColors` when it has them. Null for the
     * two system entries and for anything out of range, so the caller decides what automatic
     * means where it is.
     */
    fun indexed(i: Int, custom: IntArray?): Int? = when {
        custom != null && i in custom.indices -> custom[i]
        i in INDEXED.indices -> INDEXED[i]
        else -> null
    }

    // ── one colour reference ──

    /**
     * A colour reference as written, before it is resolved. The four forms are in the class
     * note; [tint] applies to all of them.
     */
    data class Ref(
        val rgb: Int? = null,
        val theme: Int? = null,
        val indexed: Int? = null,
        val auto: Boolean = false,
        val tint: Double = 0.0,
    )

    /** [ref] as a colour. Null for automatic and for a reference to nothing. */
    fun resolve(ref: Ref?, theme: IntArray, palette: IntArray?): Int? {
        if (ref == null || ref.auto) return null
        val base = ref.rgb
            ?: ref.theme?.let { theme.getOrNull(it) }
            ?: ref.indexed?.let { indexed(it, palette) }
            ?: return null
        return if (ref.tint == 0.0) base else tint(base, ref.tint)
    }

    /**
     * `FF4472C4` or `4472C4` to an opaque colour.
     *
     * The alpha byte is dropped on purpose. The desktop ignores it, and producers write `00` there
     * as often as `FF` - honouring it would draw a whole family of files' fills as transparent.
     */
    fun rgb(s: String?): Int? {
        val hex = s?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 && hex.length != 8) return null
        val v = hex.takeLast(6).toLongOrNull(16) ?: return null
        return v.toInt() or OPAQUE
    }

    /**
     * Lighten or darken by [tint], -1 to 1, the way the desktop does: on the LUMINANCE of the
     * colour in HLS space, not by mixing in white or black. A mix gets the same light swatch
     * for a light tint and visibly the wrong one for a dark tint of a saturated accent.
     */
    fun tint(argb: Int, tint: Double): Int {
        val t = tint.coerceIn(-1.0, 1.0)
        val (h, l, s) = toHls(argb)
        val lum = if (t < 0) l * (1 + t) else l * (1 - t) + t
        return fromHls(h, lum.coerceIn(0.0, 1.0), s)
    }

    // ── fills ──

    /**
     * A `patternFill` as the colour it reads as.
     *
     * Solid is its foreground. A hatch is drawn on the desktop as foreground lines on the
     * background, and at the size of a phone cell the lines are not resolvable anyway - so it is
     * the two mixed by how much of the cell the lines cover, which is the colour the eye takes
     * from it at a distance.
     *
     * The defaults are the format's: a missing foreground is the system foreground (black) and
     * a missing background the system background (white).
     *
     * @return null for no fill, which includes `none` and a pattern this does not know.
     */
    fun pattern(type: String?, fg: Int?, bg: Int?): Int? {
        val cover = when (type) {
            null, "", "none" -> return null
            "solid" -> return fg ?: BLACK
            "gray0625" -> 0.0625
            "gray125" -> 0.125
            "lightGray", "lightHorizontal", "lightVertical", "lightDown", "lightUp", "lightTrellis" -> 0.25
            "lightGrid" -> 0.4
            "mediumGray", "darkHorizontal", "darkVertical", "darkDown", "darkUp", "darkGrid" -> 0.5
            "darkGray", "darkTrellis" -> 0.75
            else -> return null
        }
        return mix(bg ?: WHITE, fg ?: BLACK, cover)
    }

    /** A gradient as one colour: the mean of its stops. Null when it has none. */
    fun gradient(stops: List<Int>): Int? {
        if (stops.isEmpty()) return null
        var r = 0
        var g = 0
        var b = 0
        for (c in stops) {
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = stops.size
        return OPAQUE or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** [a] moved [t] of the way to [b], channel by channel. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
        }
        return OPAQUE or ch(16) or ch(8) or ch(0)
    }

    // ── ink that can be read ──

    /**
     * The colour a cell's text is drawn in, or null for "the sheet's own ink".
     *
     * Decision: the file's colours are drawn where they were chosen against something the file
     * also controls, and not where they were chosen against paper.
     *
     * - **On a fill**, the font's colour is the file's statement about that fill and is drawn as
     *   written - white on the dark blue of a header band is the whole point of the band. An
     *   automatic font on a fill is black or white, whichever reads on it.
     * - **With no fill** the ground is this app's, which may be dark. A font colour that keeps
     *   [MIN_CONTRAST] against it is drawn - red for a negative, green for a gain. One that
     *   does not - navy, which is near-invisible on a dark screen - falls back to the sheet's
     *   ink. The cheap version draws every font colour as written, and on a dark theme a
     *   column of navy figures reads as a column of empty cells.
     *
     * @param ground the colour actually behind an unfilled cell.
     */
    fun ink(style: CellStyle?, ground: Int): Int? {
        if (style == null) return null
        val fill = style.fill
        if (fill != null) return style.ink ?: readableOn(fill)
        val ink = style.ink ?: return null
        return ink.takeIf { contrast(it, ground) >= MIN_CONTRAST }
    }

    /** Black or white, whichever reads better on [fill]. */
    fun readableOn(fill: Int): Int =
        if (contrast(BLACK, fill) >= contrast(WHITE, fill)) BLACK else WHITE

    /**
     * WCAG's large-text threshold. Cell text is small, but this decides only whether a colour the
     * author chose survives on a ground they did not; the stricter 4.5 throws away ordinary reds
     * and greens on a dark ground, which read perfectly well.
     */
    const val MIN_CONTRAST = 3.0

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG relative luminance, 0 for black and 1 for white. */
    fun luminance(argb: Int): Double {
        fun lin(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    // ── HLS ──

    private fun toHls(argb: Int): Triple<Double, Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return Triple(0.0, l, 0.0)
        val d = max - min
        val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
            g -> ((b - r) / d + 2) / 6
            else -> ((r - g) / d + 4) / 6
        }
        return Triple(h, l, s)
    }

    private fun fromHls(h: Double, l: Double, s: Double): Int {
        fun ch(v: Double) = Math.round(v * 255).toInt().coerceIn(0, 255)
        if (s == 0.0) {
            val v = ch(l)
            return OPAQUE or (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            val t = if (t0 < 0) t0 + 1 else if (t0 > 1) t0 - 1 else t0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return OPAQUE or (ch(hue(h + 1.0 / 3)) shl 16) or (ch(hue(h)) shl 8) or ch(hue(h - 1.0 / 3))
    }

    private const val OPAQUE = 0xFF shl 24
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}
