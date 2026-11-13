package dev.niccc2007.filet.icons

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.niccc2007.filet.R
import dev.niccc2007.filet.shortcuts.TabIconRes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Filet's blues, turned to the colours of the build's own icon, for what goes on a home screen.
 *
 * Inside the app a folder is Filet blue. On a home screen it stands beside Filet's icon, and the
 * debug build's icon is amber, so a blue folder there looks like another app's. Nothing here picks
 * a colour: the release icon's shade and light are where the art starts, the build's own icon's
 * shade and light (`brand_shade`, `brand_light`, written by tools/make-tab-icons.mjs from each
 * build's ic_launcher_foreground) are where it lands, and every blue between moves the way those
 * two did. Other colours (gold pages, a red emblem, a photo) are left alone.
 *
 * The same sum is in tools/make-tab-icons.mjs, which writes the tab icons' colours with it;
 * `BrandToneTest` holds the two to the same answers.
 */
class BrandTone(fromShade: Long, fromLight: Long, toShade: Long, toLight: Long) {

    private val a0 = Hsl.of(fromShade)
    private val a1 = Hsl.of(fromLight)
    private val turn = floatArrayOf(turn(a0.h, Hsl.of(toShade).h), turn(a1.h, Hsl.of(toLight).h))
    private val sat = floatArrayOf(ratio(Hsl.of(toShade).s, a0.s), ratio(Hsl.of(toLight).s, a1.s))
    private val light = floatArrayOf(Hsl.of(toShade).l - a0.l, Hsl.of(toLight).l - a1.l)

    /** Nothing moves: the release build, where the icon is the art's own blue. */
    val isIdentity: Boolean = fromShade == toShade && fromLight == toLight

    /** [argb] turned, or as it was when it is not one of Filet's blues. */
    fun of(argb: Long): Long {
        if (isIdentity || !isBrand(argb)) return argb
        val x = Hsl.of(argb)
        val t = ((x.l - a0.l) / (a1.l - a0.l)).coerceIn(0f, 1f)
        val lerp = { v: FloatArray -> v[0] + (v[1] - v[0]) * t }
        return Hsl(
            h = ((x.h + lerp(turn)) % 360f + 360f) % 360f,
            s = (x.s * lerp(sat)).coerceIn(0f, 1f),
            l = (x.l + lerp(light)).coerceIn(0f, 1f),
            a = x.a,
        ).argb()
    }

    fun of(c: Color): Color {
        if (isIdentity) return c
        val argb = (c.alpha * 255f).roundToInt().toLong() shl 24 or
            ((c.red * 255f).roundToInt().toLong() shl 16) or
            ((c.green * 255f).roundToInt().toLong() shl 8) or
            (c.blue * 255f).roundToInt().toLong()
        if (!isBrand(argb)) return c
        return Color(of(argb).toInt())
    }

    internal data class Hsl(val h: Float, val s: Float, val l: Float, val a: Int) {
        fun argb(): Long {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val (r, g, b) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f).roundToInt().toLong()
            return (a.toLong() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }

        companion object {
            fun of(argb: Long): Hsl {
                val a = ((argb shr 24) and 0xFF).toInt()
                val r = ((argb shr 16) and 0xFF) / 255f
                val g = ((argb shr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                val l = (hi + lo) / 2f
                val d = hi - lo
                val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
                val h = when {
                    d == 0f -> 0f
                    hi == r -> 60f * (((g - b) / d) % 6f)
                    hi == g -> 60f * ((b - r) / d + 2f)
                    else -> 60f * ((r - g) / d + 4f)
                }
                return Hsl((h + 360f) % 360f, s, l, a)
            }
        }
    }

    companion object {
        /** Filet's blue family: the slate and steel ramps, pale paper and deep inside. Not the sky accent, not near-greys. */
        fun isBrand(argb: Long): Boolean {
            val x = Hsl.of(argb)
            return x.h in 185f..212f && x.s in 0.12f..0.42f
        }

        private fun turn(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f
        private fun ratio(to: Float, from: Float) = if (from == 0f) 1f else to / from

        @Volatile private var built: BrandTone? = null

        /** This build's turn, from its own icon's colours. */
        fun of(context: Context): BrandTone = built ?: BrandTone(
            TabIconRes.BRAND_FROM_SHADE,
            TabIconRes.BRAND_FROM_LIGHT,
            context.getColor(R.color.brand_shade).toLong() and 0xFFFFFFFFL,
            context.getColor(R.color.brand_light).toLong() and 0xFFFFFFFFL,
        ).also { built = it }

        private val current = ThreadLocal<BrandTone?>()

        /** The turn the drawing on this thread is in, or null for the app's own blues. */
        internal fun drawingIn(): BrandTone? = current.get()

        /** Draw [block] with Filet's blues turned by [tone]: a home-screen icon in the build's colours. */
        fun <T> drawing(tone: BrandTone?, block: () -> T): T {
            val was = current.get()
            current.set(tone?.takeUnless { it.isIdentity })
            try {
                return block()
            } finally {
                current.set(was)
            }
        }
    }
}
