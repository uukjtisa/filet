package dev.niccc2007.filet.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

// The folder: the tab icons' depth, Filet's two blues, and an open front that what is inside
// stands behind. Drawn on the mock's own grid, through its 88-unit view box, so every number
// below is the mock's number. The mock is Desktop\filet-browser-icons-mock.html, `folderBody`.

/** What a folder holds, as its icon draws it. */
sealed interface Inside {
    /** Nothing at all. */
    data object Empty : Inside

    /**
     * Drawn pages: while what is inside is still being read, and below the size where three
     * things can be told apart.
     */
    data object Pages : Inside

    /** Up to three things standing in it. [lone] puts a single one down in the folder, peeking. */
    data class Things(val things: List<Thing>, val lone: Boolean = true) : Inside
}

/** One thing standing in a folder. */
sealed interface Thing {
    /** A picture of its own: a photo, a video frame, an album cover. Null while it loads. */
    data class Picture(val image: ImageBitmap?, val video: Boolean = false) : Thing

    /** A file drawn as its type, by the icon name `FileTypes` gives it. */
    data class File(val icon: String) : Thing

    /** A folder, with a sheet in it when it has anything in it. */
    data class Folder(val full: Boolean) : Thing
}

/**
 * How a folder is dressed: an emblem on its front, in a colour.
 *
 * @param emblem an [EMBLEMS] id, or null for none.
 * @param colour an [ACCENTS] name. Filet blue is "steel".
 * @param cover the name of a picture inside the folder to show in front of the rest, or null.
 * @param showEmblem off hides the emblem on this folder and keeps it saved. On by default, and a
 *   record saved before it existed reads on.
 * @param paint this folder's own paint, from Change icon, or null to follow its place's Folders
 *   choice. See [folderDraw].
 */
data class FolderLook(
    val emblem: String? = null,
    val colour: String = "steel",
    val cover: String? = null,
    val showEmblem: Boolean = true,
    val paint: FolderPaint? = null,
) {
    companion object {
        val PLAIN = FolderLook()
    }
}

/** What a folder's colour paints: the emblem on its front, or the whole folder. */
enum class FolderPaint { EMBLEM, WHOLE }

/**
 * What a folder draws as, in one place.
 *
 * @param legacy the Legacy folder (pictures fanned inside) rather than the drawn one.
 * @param outline the bare Legacy outline: nothing else counts.
 * @param emblem the emblem to draw, or null when there is none or it is hidden.
 * @param whole the whole folder in its colour, rather than the emblem.
 */
data class FolderDraw(
    val legacy: Boolean,
    val outline: Boolean,
    val emblem: String?,
    val whole: Boolean,
    val cover: String?,
)

/**
 * The one rule for what a folder draws as, from the place's Folders choice and its own record.
 *
 * Legacy outline is the bare outline whatever the record says. Otherwise the folder's own paint
 * wins; with none, Drawn, whole colour paints the whole folder and the rest paint the emblem. An
 * emblem shows only when there is one and its Show emblem is on. Nothing here clears anything:
 * a hidden emblem is still in [look].
 */
fun folderDraw(place: FolderDrawing, look: FolderLook): FolderDraw {
    if (place == FolderDrawing.OUTLINE) {
        return FolderDraw(legacy = true, outline = true, emblem = null, whole = false, cover = null)
    }
    val paint = look.paint ?: if (place == FolderDrawing.DRAWN_WHOLE) FolderPaint.WHOLE else FolderPaint.EMBLEM
    return FolderDraw(
        legacy = place == FolderDrawing.WITH_PICTURES,
        outline = false,
        emblem = look.emblem.takeIf { look.showEmblem },
        whole = paint == FolderPaint.WHOLE,
        cover = look.cover,
    )
}

/** Every emblem id, in the picker's order, and the groups they fall in. */
val EMBLEM_IDS: List<String> get() = EMBLEMS.map { it.id }
val EMBLEM_GROUPS: List<String> get() = EMBLEMS.map { it.group }.distinct()
fun emblemLabel(id: String): String? = EMBLEMS.firstOrNull { it.id == id }?.label
fun emblemGroup(id: String): String? = EMBLEMS.firstOrNull { it.id == id }?.group
val COLOUR_IDS: List<String> get() = ACCENTS.keys.toList()

private val INSIDE = rr(20f, 39f, 60f, 44f, 4f)
private const val BACK_LIP = "M22.5,25.6 H41.2 l6,8 H78"
private const val FRONT_LIP = "M25.2,55.2 H87"
private const val EMPTY_LINE = "M22,41 H78"
private val PAGE_BEHIND = rr(27f, 31f, 38f, 40f, 3f)
private val PAGE_FRONT = rr(38f, 32f, 38f, 40f, 3f)
private val PAGE_LINE_1 = rr(43f, 38.5f, 22f, 3f, 1.5f)
private val PAGE_LINE_2 = rr(43f, 45f, 27f, 3f, 1.5f)
private val INK_LINES = Color(0xFF4E7382)

// A picture standing in it: a white card, the picture inside its own frame.
private val CARD = rr(-13f, -16f, 26f, 32f, 2.4f)
private val CARD_PICTURE = rr(-11.5f, -14.5f, 23f, 29f, 0.6f)
private val PLAY_DISC = circle(0f, -2f, 5.2f)
private const val PLAY = "M-1.8,-5 L3,-2 L-1.8,1z"

// A file standing in it: a page with its corner folded and a band in its type's colour.
private const val MINI_PAGE = "M-11,-16 H5 L11,-10 V14 a2,2 0 0 1 -2,2 H-9 a2,2 0 0 1 -2,-2 V-14 a2,2 0 0 1 2,-2z"
private const val MINI_CORNER = "M5,-16 V-12 a2,2 0 0 0 2,2 H11z"
private const val MINI_BAND = "M-11,2.5 h22 v6 h-22z"

/**
 * Draw a folder filling this scope.
 *
 * @param ground the shadow under it; off where the folder is itself drawn inside something.
 */
fun DrawScope.drawFolder(inside: Inside, look: FolderLook = FolderLook.PLAIN, ground: Boolean = true, whole: Boolean = false) {
    onGrid {
        folderBody(inside, look, ground, whole && look.colour != "steel")
    }
}

/**
 * Whether a folder that is always drawn (a tab chip, a home-screen icon) wears its colour all
 * over: its own paint, else the lists' Folders choice. The Legacy looks never reach those places,
 * so only the paint is taken from the choice.
 */
fun wholeFor(lists: FolderDrawing, look: FolderLook): Boolean =
    (look.paint ?: if (lists == FolderDrawing.DRAWN_WHOLE) FolderPaint.WHOLE else FolderPaint.EMBLEM) == FolderPaint.WHOLE

/**
 * The whole-folder colours, the colour mock's `bb-`, `bi-` and `bf-`: the back in the mid and
 * dark tones, the inside darker still, the front in the light ones.
 */
private class WholeInks(a: Accent) {
    val back: Ink = Ink.Ramp(arrayOf(0f to mix(a.mid, Color.White, 0.1f), 0.5f to a.mid, 1f to a.deep))
    val inside: Ink = Ink.Ramp(arrayOf(0f to mix(a.deep, Color.Black, 0.25f), 1f to mix(a.deep, Color.Black, 0.6f)))
    val front: Ink = Ink.Ramp(arrayOf(0f to mix(a.light, Color.White, 0.45f), 0.35f to a.light, 0.7f to mix(a.light, a.mid, 0.5f), 1f to a.mid))
}

private fun DrawScope.folderBody(inside: Inside, look: FolderLook, ground: Boolean, tinted: Boolean) {
    val w = if (tinted) WholeInks(accent(look.colour)) else null
    if (ground) ground(54f, 88.5f, 31f, 3.6f)
    fill(FOLDER_BACK, w?.back ?: grad("gSlate"))
    line(BACK_LIP, Color.White, 1.6f, alpha = 0.22f)
    fill(INSIDE, w?.inside ?: grad("gDeep"))
    when (inside) {
        Inside.Empty -> line(EMPTY_LINE, Color.Black, 3f, alpha = 0.18f, cap = StrokeCap.Butt)
        Inside.Pages -> pages(look, tinted)
        is Inside.Things -> {
            if (inside.things.isEmpty()) pages(look, tinted) else things(inside)
        }
    }
    fill(FOLDER_FRONT, w?.front ?: grad("gSteel"))
    line(FRONT_LIP, Color.White, 1.8f, alpha = 0.5f)
    // Hidden by its own Show emblem, never cleared. See [FolderLook.showEmblem].
    look.emblem?.takeIf { look.showEmblem }?.let { emblemOnFront(it, look.colour, tinted) }
}

/** Two sheets, the one behind in the folder's colour, or gold on a plain or whole-coloured folder. */
private fun DrawScope.pages(look: FolderLook, tinted: Boolean = false) {
    val behind = if (look.colour == "steel" || tinted) accent("gold").ramp else accent(look.colour).ramp
    withTransform({ rotate(-7f, Offset(46f, 51f)) }) { fill(PAGE_BEHIND, behind) }
    withTransform({ rotate(5f, Offset(57f, 51f)) }) {
        fill(PAGE_FRONT, grad("gPaper"))
        fill(PAGE_LINE_1, ink(INK_LINES), alpha = 0.55f)
        fill(PAGE_LINE_2, ink(INK_LINES), alpha = 0.55f)
    }
}

/** Which slots [count] things stand in, the first one in front. */
internal fun slotsFor(count: Int, lone: Boolean): List<Char> = when (count) {
    1 -> listOf(if (lone) 'P' else 'M')
    2 -> listOf('L', 'R')
    else -> listOf('M', 'L', 'R')
}

private fun DrawScope.things(inside: Inside.Things) {
    val shown = inside.things.take(3)
    val slots = slotsFor(shown.size, inside.lone)
    // Back to front: the sides first, the middle last.
    val order = shown.indices.sortedBy { if (slots[it] == 'M') 1 else 0 }
    for (i in order) {
        val slot = FOLDER_SLOTS.getValue(slots[i])
        withTransform({
            translate(slot.x, slot.y)
            rotate(slot.lean, Offset.Zero)
        }) {
            thing(shown[i], mirror = slots[i] == 'L')
        }
    }
}

private fun DrawScope.thing(t: Thing, mirror: Boolean) {
    when (t) {
        is Thing.Picture -> {
            fill(CARD, ink(Color(0xFFF3F7F8)))
            t.image?.let { picture(it) }
            if (t.video) {
                fill(PLAY_DISC, ink(Color.Black), alpha = 0.45f)
                fill(PLAY, ink(Color.White))
            }
        }
        is Thing.File -> miniFile(t.icon)
        is Thing.Folder -> {
            if (mirror) withTransform({ scale(-1f, 1f, Offset.Zero) }) { subFolder(t.full) } else subFolder(t.full)
        }
    }
}

/** The picture, cropped to fill its frame from the middle. */
private fun DrawScope.picture(image: ImageBitmap) {
    val frame = path(CARD_PICTURE)
    val b = frame.getBounds()
    val want = b.width / b.height
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    val (sw, sh) = if (iw / ih > want) (ih * want) to ih else iw to (iw / want)
    val sx = ((iw - sw) / 2f).roundToInt()
    val sy = ((ih - sh) / 2f).roundToInt()
    clipPath(frame) {
        withTransform({
            translate(b.left, b.top)
            scale(b.width / sw, b.height / sh, Offset.Zero)
        }) {
            drawImage(image, IntOffset(sx, sy), IntSize(sw.roundToInt(), sh.roundToInt()), dstSize = IntSize(sw.roundToInt(), sh.roundToInt()))
        }
    }
}

private fun DrawScope.miniFile(icon: String) {
    val family = TYPE_FAMILIES[icon] ?: TYPE_FAMILIES.getValue("other")
    val a = accent(family.accent)
    fill(MINI_PAGE, grad("gPaper"))
    fill(MINI_CORNER, grad("gSteel"))
    family.glyph?.let { g ->
        withTransform({ translate(-7.2f, -13f); scale(0.6f, 0.6f, Offset.Zero) }) {
            glyph(TYPE_GLYPHS.getValue(g), a.ramp, a.mid, a.light)
        }
    }
    fill(MINI_BAND, a.ramp)
}

/**
 * A portrait gold folder about its centre, drawn as a folder is: a back with its tab at the top
 * right, and a front covering nearly all of it. When it has anything in it, a sheet shows between
 * the two, above the front's edge.
 */
private fun DrawScope.subFolder(full: Boolean) {
    fill(SUB_BACK, grad("gSubBack"))
    line(SUB_TAB_LIP, Color.White, 1.1f, alpha = 0.35f)
    if (full) {
        fill(SUB_SHEET, grad("gPaper"))
        for (l in SUB_LINES) fill(l, ink(INK_LINES), alpha = 0.5f)
    }
    fill(SUB_FRONT, grad("gSubFront"), rim = Color(0xFF8A6A2A).copy(alpha = 0.45f))
    line(SUB_LIP, Color.White, 1.3f, alpha = 0.5f)
}

/** The emblem across the front: drawn as a scene where there is one, the glyph larger where not. */
private fun DrawScope.emblemOnFront(id: String, colour: String, whole: Boolean = false) {
    val scene = RICH_EMBLEMS[id]
    if (scene != null) {
        richScene(scene, colour, whole)
        return
    }
    val parts = EMBLEMS.firstOrNull { it.id == id }?.parts ?: return
    emblemGlyph(parts, colour, 54.5f, 69f, 1.32f, whole)
}

/** A scene emblem on its 40 by 30 box, across the folder's front: the mock's `richOn`. */
internal fun DrawScope.richScene(parts: List<RichPart>, colour: String, whole: Boolean = false) {
    val a = accent(colour)
    val gold = accent("gold")
    // On a whole-coloured folder the scene is white over the colour: the mock's richOn(whole).
    val white = Color(0xFFF6FAFB)
    val fillOf: (Char) -> Ink = { t ->
        when (t) {
            'm' -> if (whole) ink(white) else a.ramp
            'l' -> ink(mix(a.light, Color.White, if (whole) 0.7f else 0.45f))
            'w' -> ink(Color(0xFFF8FBFC))
            'd' -> ink(if (whole) mix(a.deep, Color.Black, 0.15f) else a.deep)
            'k' -> ink(mix(a.deep, Color.Black, 0.38f))
            'g' -> gold.ramp
            else -> ink(Color(0xFFE5534B))
        }
    }
    val lineOf: (Char) -> Color = { t ->
        when (t) {
            'm' -> if (whole) white else a.mid
            'l' -> mix(a.light, Color.White, if (whole) 0.7f else 0.45f)
            'w' -> Color(0xFFF8FBFC)
            'd' -> if (whole) mix(a.deep, Color.Black, 0.15f) else a.deep
            'k' -> mix(a.deep, Color.Black, 0.38f)
            'g' -> gold.mid
            else -> Color(0xFFE5534B)
        }
    }
    val rim = mix(a.deep, Color.Black, 0.25f).copy(alpha = 0.55f)
    val shade = mix(a.deep, Color.Black, 0.45f)
    withTransform({ translate(33.5f, 53.5f); scale(1.05f, 1.05f, Offset.Zero) }) {
        layer(0.34f) {
            withTransform({ translate(0f, 1.5f) }) {
                for (p in parts) {
                    if (p.stroke > 0f) line(p.d, shade, p.stroke, alpha = p.alpha)
                    else fill(p.d, ink(shade), alpha = p.alpha, evenOdd = p.evenOdd)
                }
            }
        }
        for (p in parts) {
            if (p.stroke > 0f) {
                line(p.d, lineOf(p.tone), p.stroke, alpha = p.alpha)
            } else {
                val edge = if (p.tone == 'k' || p.tone == 'r') null else rim
                fill(p.d, fillOf(p.tone), alpha = p.alpha, evenOdd = p.evenOdd, rim = edge, rimWidth = 0.55f)
            }
        }
    }
}

/**
 * An emblem on the Legacy folder's front, on that folder's 100-unit grid: the Legacy icons mock's
 * `withPictures`, centred at 50, 66.5 at 1.3. White over the colour when [whole].
 */
fun DrawScope.classicEmblem(id: String, colour: String, whole: Boolean = false) {
    withTransform({ scale(size.width / 100f, size.height / 100f, Offset.Zero) }) {
        val scene = RICH_EMBLEMS[id]
        if (scene != null) {
            // A scene is placed on the drawn folder's grid; carried to the same centre and size here.
            val k = 1.3f / 1.32f
            withTransform({ translate(50f - 54.5f * k, 66.5f - 69f * k); scale(k, k, Offset.Zero) }) {
                richScene(scene, colour, whole)
            }
        } else {
            EMBLEMS.firstOrNull { it.id == id }?.parts?.let { emblemGlyph(it, colour, 50f, 66.5f, 1.3f, whole) }
        }
    }
}

/** The size a picture standing in a folder of [folderPx] is worth decoding at. */
fun folderPicturePx(folderPx: Float): Int = (folderPx * 0.3f * 2.5f).roundToInt().coerceIn(48, 200)
