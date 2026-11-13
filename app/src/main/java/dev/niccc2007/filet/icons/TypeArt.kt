package dev.niccc2007.filet.icons

import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import dev.niccc2007.filet.vfs.types.FileTypes
import java.util.Locale

// A file with no picture of its own: a page, its corner folded, the type's glyph in the type's
// colour, and a tag naming the extension. The mock's `typeSVG`.

private const val PAGE = "M34,16 H66 L80,30 V86 a6,6 0 0 1 -6,6 H34 a6,6 0 0 1 -6,-6 V22 a6,6 0 0 1 6,-6z"
private const val PAGE_SHADE = "M66,16 L80,30 V86 a6,6 0 0 1 -6,6 H66z"
private const val CORNER = "M66,16 V26 a4,4 0 0 0 4,4 H80z"
private const val TOP_LIP = "M34.5,17.6 H65"
private const val TAG = "M21,64 H70 a4,4 0 0 1 4,4 V76 a4,4 0 0 1 -4,4 H21 a3,3 0 0 1 -3,-3 V67 a3,3 0 0 1 3,-3z"
private const val TAG_FOLD = "M18.6,78.6 L28,84 V80z"
private const val TAG_LIP = "M21.5,65.4 H70"

/** The longest name the tag holds. Longer ones go without a tag rather than being cut. */
private const val TAG_MAX = 5

/** What the tag on [extension]'s icon says: the extension, or its usual short form, in capitals. */
fun typeTag(extension: String): String? {
    val ext = FileTypes.norm(extension)
    if (ext.isEmpty()) return null
    val shown = TAG_SHORT[ext] ?: ext
    return shown.takeIf { it.length <= TAG_MAX }?.uppercase(Locale.US)
}

/** The family a file is drawn as. */
internal fun familyOf(extension: String): TypeFamily =
    TYPE_FAMILIES[FileTypes.icon(extension)] ?: TYPE_FAMILIES.getValue("other")

private val tagPaint by lazy {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.025f
    }
}

/** Draw [extension]'s file-type icon filling this scope. */
fun DrawScope.drawTypeIcon(extension: String) {
    val family = familyOf(extension)
    val a = accent(family.accent)
    val tag = typeTag(extension)
    onGrid {
        ground(55f, 93.5f, 24f, 3f)
        fill(PAGE, grad("gPaper"))
        fill(PAGE_SHADE, ink(Color.Black), alpha = 0.05f)
        fill(CORNER, grad("gSteel"))
        line(TOP_LIP, Color.White, 1.6f, alpha = 0.8f)
        family.glyph?.let { emblemGlyph(TYPE_GLYPHS.getValue(it), family.accent, 53f, 43f, 1.3f) }
        if (tag != null) {
            fill(TAG, a.ramp)
            fill(TAG_FOLD, ink(a.deep))
            line(TAG_LIP, Color.White, 1.2f, alpha = 0.35f)
            val size = when {
                tag.length <= 3 -> 12.5f
                tag.length == 4 -> 10.8f
                else -> 9f
            }
            // The grid is scaled already, so the text is sized in grid units like everything else.
            synchronized(tagPaint) {
                tagPaint.textSize = size
                drawContext.canvas.nativeCanvas.drawText(tag, 46f, 72f + size * 0.36f, tagPaint)
            }
        }
    }
}
