package dev.niccc2007.filet.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Slate — Filet's default palette (TEMPLATE.md §3).
 *
 * Warm-NEUTRAL grey, not a pure neutral: #161614 rather than #1A1A1A, so the app reads
 * cosy rather than clinical. One cool accent against the warm ground.
 *
 * Light is defined first and dark overrides it, per TEMPLATE.md §3 — no colour may have
 * its only definition inside a dark block.
 */
object Slate {
    // light
    val Bg = Color(0xFFF5F4F0)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEBE9E3)
    val Line = Color(0xFFDDDAD2)
    val Fg = Color(0xFF22211E)
    val Fg2 = Color(0xFF615D55)
    val Fg3 = Color(0xFF8B867C)
    val Accent = Color(0xFF3E6E82)
    val AccentDim = Color(0xFFCBDDE4)
    val OnAccent = Color(0xFFFFFFFF)
    val Bad = Color(0xFF9B4A4A)

    // dark
    val BgD = Color(0xFF161614)
    val SurfaceD = Color(0xFF1C1C1A)
    val SurfaceVariantD = Color(0xFF2A2A26)
    val LineD = Color(0xFF2E2E2A)
    val FgD = Color(0xFFE8E6E1)
    val Fg2D = Color(0xFF9A968D)
    val Fg3D = Color(0xFF6B6860)
    val AccentD = Color(0xFF7FA8B8)
    val AccentDimD = Color(0xFF31434B)
    val OnAccentD = Color(0xFF0E1417)
    val BadD = Color(0xFFC97F7F)
}

internal val LightScheme = lightColorScheme(
    primary = Slate.Accent,
    onPrimary = Slate.OnAccent,
    primaryContainer = Slate.AccentDim,
    onPrimaryContainer = Slate.Accent,
    background = Slate.Bg,
    onBackground = Slate.Fg,
    surface = Slate.Surface,
    onSurface = Slate.Fg,
    surfaceVariant = Slate.SurfaceVariant,
    onSurfaceVariant = Slate.Fg2,
    outline = Slate.Line,
    outlineVariant = Slate.Line,
    error = Slate.Bad,
)

internal val DarkScheme = darkColorScheme(
    primary = Slate.AccentD,
    onPrimary = Slate.OnAccentD,
    primaryContainer = Slate.AccentDimD,
    onPrimaryContainer = Slate.AccentD,
    background = Slate.BgD,
    onBackground = Slate.FgD,
    surface = Slate.SurfaceD,
    onSurface = Slate.FgD,
    surfaceVariant = Slate.SurfaceVariantD,
    onSurfaceVariant = Slate.Fg2D,
    outline = Slate.LineD,
    outlineVariant = Slate.LineD,
    error = Slate.BadD,
)
