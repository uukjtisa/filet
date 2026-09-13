package dev.niccc2007.filet.nearby

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.ThemeChoice
import dev.niccc2007.filet.ui.theme.PaletteSide
import dev.niccc2007.filet.ui.theme.PaletteSpec

/**
 * The app's palette, as CSS.
 *
 * The served page is **part of Filet**, not a separate product with its own taste. Switch the
 * app to Ember and the laptop's browser turns amber too; switch to light and the page is
 * light. Anything else and the phone and the laptop look like two different apps that happen
 * to share files.
 *
 * Emitted as custom properties rather than a stylesheet per palette, so the page is written
 * once against names and the values arrive at request time.
 */
object WebTheme {

    fun css(context: Context): String {
        val prefs = Prefs(context)
        val spec = PaletteSpec.of(prefs.accent.value)
        val dark = when (prefs.theme.value) {
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
            ThemeChoice.SYSTEM -> isSystemDark(context)
        }
        val side = if (dark) spec.dark else spec.light
        return buildString {
            append(":root{")
            append(vars(side, dark))
            append("}")
        }
    }

    private fun vars(side: PaletteSide, dark: Boolean): String {
        val s = side.scheme
        val e = side.extras
        return buildString {
            v("bg", s.background)
            v("surface", s.surface)
            v("raised", e.raised)
            v("sunken", e.sunken)
            v("high", e.high)
            v("line", s.outline)
            v("line-soft", e.lineSoft)
            v("fg", s.onSurface)
            v("fg2", e.fg2)
            v("fg3", e.fg3)
            v("accent", e.accent)
            v("on-accent", s.onPrimary)
            v("accent-dim", s.primaryContainer)
            v("sel", e.sel)
            v("good", e.good)
            v("warn", e.warn)
            v("bad", e.bad)
            append("color-scheme:")
            append(if (dark) "dark" else "light")
            append(";")
        }
    }

    private fun StringBuilder.v(name: String, color: Color) {
        append("--").append(name).append(':').append(hex(color)).append(';')
    }

    private fun hex(color: Color): String {
        val argb = color.value.toLong() ushr 32
        return "#%06X".format(argb and 0xFFFFFF)
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
