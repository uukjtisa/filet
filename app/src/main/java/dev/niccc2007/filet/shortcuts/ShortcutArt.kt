package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.R
import dev.niccc2007.filet.icons.BrandTone
import dev.niccc2007.filet.icons.EMBLEMS
import dev.niccc2007.filet.icons.FolderShortcutIcon
import dev.niccc2007.filet.icons.RICH_EMBLEMS
import dev.niccc2007.filet.icons.drawTypeIcon
import dev.niccc2007.filet.icons.emblemGlyph
import dev.niccc2007.filet.icons.line
import dev.niccc2007.filet.icons.path
import dev.niccc2007.filet.icons.richScene
import dev.niccc2007.filet.media.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * A shortcut's icon as the home screen will show it, from what was chosen on the sheet.
 *
 * Two kinds of result. A tab's drawing and Filet's own icon are resources: the launcher draws them
 * itself, in the build's colours, with the themed glyph a themed home screen asks for. Everything
 * else is drawn here into a clear square, with Filet's blues turned to the build's colours, and
 * handed over as an adaptive bitmap. Nothing is ever laid on a coloured tile: like Filet's own
 * icon, a shortcut stands on the wallpaper.
 *
 * The sheet previews exactly what this draws, so what is chosen is what lands.
 */
object ShortcutArt {

    /** A drawn icon's side: 108dp at about 2.7 pixels to a dp, small enough to cross the binder. */
    const val SIDE = 288

    /** A picture's side, as it always was: 108dp at 2x. */
    private const val PICTURE = 216

    /** What one source makes. */
    sealed interface Drawn {
        /** A resource: [launcher] for the home screen, [layer] its art alone for a preview, on 108 units. */
        data class Res(val launcher: Int, val layer: Int) : Drawn

        /** A drawing on the 108-unit square, nothing behind it. */
        class Bits(val bitmap: Bitmap) : Drawn
    }

    /**
     * Draw [icon] for [s]. Reads the disk for a picture or a folder's contents, so call it off the
     * main thread. Falls back to the subject's own icon where the chosen one cannot be drawn (a
     * picture that will not decode, a folder that is gone).
     */
    suspend fun draw(context: Context, s: PinSubject, icon: ShortcutIcon): Drawn = withContext(Dispatchers.IO) {
        drawOrNull(context, s, icon) ?: drawOrNull(context, s, defaultIcon(s)) ?: filet()
    }

    private val resendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Send a placed shortcut's icon to the launcher once more, drawn from its record, as an edit
     * does. Every placing ends with it: the launcher's confirmation, or the list finding the icon
     * on a launcher that never confirms.
     *
     * Some launchers keep a shortcut's icon by its id. A folder pinned before, taken off and pinned
     * again comes back under the id it had (`id:<file id>`), and such a launcher placed it wearing
     * the icon it was first given: the folder's old look, on the dark tile pins had then. Only an
     * edit, which sends the icon again, changed it.
     *
     * Drawn as the sheet draws it: the record's own icon, else its kind's, and a folder under "Same
     * as the folder" in the look it wears in the list now. A script is left as the sheet sent it:
     * finding it needs the script list, which is not loaded here. [after] runs once it is sent or
     * given up on.
     */
    fun resend(context: Context, record: ShortcutRecord, after: () -> Unit = {}) {
        val app = context.applicationContext
        resendScope.launch {
            try {
                runCatching { resendNow(app, record) }
            } finally {
                after()
            }
        }
    }

    private suspend fun resendNow(context: Context, record: ShortcutRecord) {
        val graph = FiletApp.graphOf(context)
        val file = if (record.kind != ShortcutKind.FILE) null else findForEdit(
            record,
            byId = { id -> runCatching { graph.index.pathFor(id) }.getOrNull() },
            stat = { p -> runCatching { graph.vfs.stat(p) }.getOrNull() },
        )?.let { PinSubject.File(it, picture = !it.isDir && Thumbnails.canPreview(it.extension)) }
        val subject = editSubject(record, file) { null } ?: return
        val icon = ShortcutIcon.parse(record.icon) ?: defaultIcon(subject)
        Shortcuts.update(context, record, launcherIcon(context, draw(context, subject, icon)))
    }

    /** The launcher's icon for what [draw] made. */
    fun launcherIcon(context: Context, drawn: Drawn): Icon = when (drawn) {
        is Drawn.Res -> Icon.createWithResource(context, drawn.launcher)
        is Drawn.Bits -> Icon.createWithAdaptiveBitmap(drawn.bitmap)
    }

    private fun filet() = Drawn.Res(R.mipmap.ic_launcher, R.drawable.ic_launcher_foreground)

    private suspend fun drawOrNull(context: Context, s: PinSubject, icon: ShortcutIcon): Drawn? {
        val file = (s as? PinSubject.File)?.node
        return when (icon) {
            is ShortcutIcon.Picture -> {
                val node = file?.takeIf { !it.isDir } ?: return null
                val graph = FiletApp.graphOf(context)
                val bmp = runCatching { Thumbnails.get(context, graph.vfs, node.path, node.size, node.mtime, px = PICTURE) }.getOrNull()
                    ?: return null
                Drawn.Bits(onSquare(bmp, icon.framed))
            }
            ShortcutIcon.Type -> {
                val node = file?.takeIf { !it.isDir } ?: return null
                Drawn.Bits(bitmapOf(context) { typeIcon(node.extension) })
            }
            is ShortcutIcon.Folder -> {
                val node = file?.takeIf { it.isDir } ?: return null
                Drawn.Bits(FolderShortcutIcon.bitmap(context, node, icon.own))
            }
            is ShortcutIcon.Emblem -> Drawn.Bits(bitmapOf(context) { onUnits { drawEmblemAlone(icon.emblem, icon.colour) } })
            is ShortcutIcon.Drawing -> Drawn.Res(launcherIcon(icon.art), homeArt(icon.art) ?: return null)
            ShortcutIcon.Filet -> filet()
            ShortcutIcon.Simple -> {
                val mono = ownArt(s)?.let { TabIconRes.MONO[it] }?.let { context.getDrawable(it) }
                Drawn.Bits(bitmapOf(context) { onUnits { simpleDisc(s, mono) } })
            }
        }
    }

    /**
     * Whether [src] lets the wallpaper through at its edges: an app's icon, a picture with a clear
     * background. Such a picture starts framed, whole in the middle, so it keeps its shape.
     */
    fun seeThrough(src: Bitmap): Boolean {
        if (!src.hasAlpha()) return false
        val xs = intArrayOf(0, src.width / 2, src.width - 1)
        val ys = intArrayOf(0, src.height / 2, src.height - 1)
        for (x in xs) for (y in ys) {
            if (x != 0 && x != src.width - 1 && y != 0 && y != src.height - 1) continue
            if (android.graphics.Color.alpha(src.getPixel(x, y)) < 255) return true
        }
        return false
    }

    /** [src] on the launcher's square: cut to fill it, or [framed] whole in its safe middle. */
    fun onSquare(src: Bitmap, framed: Boolean): Bitmap {
        val out = Bitmap.createBitmap(PICTURE, PICTURE, Bitmap.Config.ARGB_8888)
        val r = ShortcutFit.rect(src.width, src.height, framed, PICTURE.toFloat())
        android.graphics.Canvas(out).drawBitmap(
            src, null, android.graphics.RectF(r[0], r[1], r[2], r[3]), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    /** A clear [SIDE] square with [block] drawn on it in the build's colours. */
    private fun bitmapOf(context: Context, block: DrawScope.() -> Unit): Bitmap {
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        BrandTone.drawing(BrandTone.of(context)) {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out.asImageBitmap()), Size(SIDE.toFloat(), SIDE.toFloat()), block)
        }
        return out
    }
}

/** [block] drawn on the launcher's 108-unit grid, whatever this scope's size. */
internal inline fun DrawScope.onUnits(block: DrawScope.() -> Unit) {
    val k = size.minDimension / 108f
    withTransform({ scale(k, k, Offset.Zero) }) { block() }
}

/** A file-type icon in the 64-unit middle of the launcher's square, where the mock places one. */
internal fun DrawScope.typeIcon(extension: String) {
    val m = size.minDimension * 22f / 108f
    inset(m, m, m, m) { drawTypeIcon(extension) }
}

/**
 * An emblem on its own, on the 108-unit grid: a scene emblem fitted to 50 units at the middle, a
 * plain one at the size a folder's front wears it, scaled up. The mock's `emblemAlone`. No emblem
 * draws nothing.
 */
internal fun DrawScope.drawEmblemAlone(id: String?, colour: String) {
    if (id == null) return
    val rich = RICH_EMBLEMS[id]
    if (rich != null) {
        val b = richBounds(id)
        val k = 50f / max(b.width, b.height)
        withTransform({
            translate(54f, 54f)
            scale(k, k, Offset.Zero)
            translate(-b.center.x, -b.center.y)
        }) { richScene(rich, colour) }
        return
    }
    val parts = EMBLEMS.firstOrNull { it.id == id }?.parts ?: return
    emblemGlyph(parts, colour, 54f, 54f, 2.3f)
}

private val richBox = ConcurrentHashMap<String, Rect>()

/**
 * Where a scene emblem's drawing lies on the 108 grid, its shadow included: [richScene] places
 * its parts at 33.5, 53.5 scaled 1.05, and drops the shadow 1.5 lower.
 */
private fun richBounds(id: String): Rect = richBox.getOrPut(id) {
    val parts = RICH_EMBLEMS.getValue(id)
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (p in parts) {
        val pb = path(p.d, p.evenOdd).getBounds()
        l = minOf(l, pb.left); t = minOf(t, pb.top); r = maxOf(r, pb.right); b = maxOf(b, pb.bottom + 1.5f)
    }
    Rect(33.5f + 1.05f * l, 53.5f + 1.05f * t, 33.5f + 1.05f * r, 53.5f + 1.05f * b)
}

/** The plainer icon: Filet's light blue disc and the app's line glyph on it. The mock's `simpleDisc`. */
private fun DrawScope.simpleDisc(s: PinSubject, mono: android.graphics.drawable.Drawable?) {
    val disc = Color(0xFF7FA8B8)
    drawCircle(BrandTone.drawingIn()?.of(disc) ?: disc, radius = 31f, center = Offset(54f, 54f))
    val ink = Color(0xFF13181A)
    if (mono != null) {
        // The themed glyph sits 44 units wide at 32; the mock draws it 36 wide at 36.
        val k = 1.5f / 1.833f
        withTransform({ scale(k, k, Offset(54f, 54f)) }) {
            mono.setBounds(0, 0, 108, 108)
            mono.setTint(android.graphics.Color.argb(255, 0x13, 0x18, 0x1A))
            mono.draw(drawContext.canvas.nativeCanvas)
        }
        return
    }
    val node = (s as? PinSubject.File)?.node ?: return
    val lines = when {
        node.isDir -> SIMPLE_FOLDER
        s.noun == "photo" -> SIMPLE_IMAGE
        s.noun == "video" -> SIMPLE_VIDEO
        else -> SIMPLE_FILE
    }
    withTransform({
        translate(54f, 54f)
        scale(1.5f, 1.5f, Offset.Zero)
        translate(-12f, -12f)
    }) { for (d in lines) line(d, ink, 1.8f) }
}

// The app's line glyphs for a file, on 24 units, as the mock draws them.
private val SIMPLE_FOLDER = listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
private val SIMPLE_FILE = listOf("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
private val SIMPLE_IMAGE = listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "m3 16 5-5 4 4 3-3 6 6", "M15.5 8.5h.01")
private val SIMPLE_VIDEO = listOf("M4 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "m17 10 5-3v10l-5-3")
