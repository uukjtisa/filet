package dev.niccc2007.filet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.niccc2007.filet.data.AccentChoice
import dev.niccc2007.filet.data.ThemeChoice

/**
 * The colours Material 3 has no slot for.
 *
 * A file manager needs a drop-target colour, a selected-row colour and a hover colour that
 * are all distinct from `primaryContainer`; folding them into the M3 scheme makes selection
 * and drop look identical, which is exactly the moment a drag goes wrong.
 *
 * Names match the CSS custom properties in the UI mock one-for-one, so the mock stays a
 * usable reference instead of drifting into decoration.
 */
data class FiletColors(
    val raised: Color,
    val sunken: Color,
    val high: Color,
    val lineSoft: Color,
    val fg2: Color,
    val fg3: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val sel: Color,
    val hover: Color,
    val drop: Color,
    val accent: Color,
)

val LocalFiletColors: ProvidableCompositionLocal<FiletColors> =
    compositionLocalOf { PaletteSpec.SLATE.dark.extras }

/** A full palette: the M3 scheme plus the extras above, for one light/dark side. */
class PaletteSide(val scheme: ColorScheme, val extras: FiletColors)

/**
 * Named palettes rather than a colour picker.
 *
 * A hue slider mostly produces unreadable lists, and this app is read all day. Four
 * hand-checked sets instead: the mock's slate and ember, plus moss and a near-monochrome ink.
 */
enum class PaletteSpec(val light: PaletteSide, val dark: PaletteSide) {
    SLATE(
        light = side(
            bg = 0xFFF5F4F0, surface = 0xFFFFFFFF, raised = 0xFFFAF9F6, sunken = 0xFFEBE9E3,
            high = 0xFFE4E1DA, line = 0xFFDDDAD2, lineSoft = 0xFFE9E6DF,
            fg = 0xFF22211E, fg2 = 0xFF615D55, fg3 = 0xFF8B867C,
            accent = 0xFF3E6E82, accentDim = 0xFFCBDDE4, onAccent = 0xFFFFFFFF,
            good = 0xFF4B7A52, warn = 0xFF8A6A2E, bad = 0xFF9B4A4A,
            sel = 0xFFE3EDF1, hover = 0xFFF0EEE8, drop = 0xFFCFE3EA, isLight = true,
        ),
        dark = side(
            bg = 0xFF161614, surface = 0xFF1C1C1A, raised = 0xFF232320, sunken = 0xFF121210,
            high = 0xFF2A2A26, line = 0xFF2E2E2A, lineSoft = 0xFF232320,
            fg = 0xFFE8E6E1, fg2 = 0xFF9A968D, fg3 = 0xFF6B6860,
            accent = 0xFF7FA8B8, accentDim = 0xFF31434B, onAccent = 0xFF0E1417,
            good = 0xFF8FB894, warn = 0xFFC9A36A, bad = 0xFFC97F7F,
            sel = 0xFF243036, hover = 0xFF23231F, drop = 0xFF2C4049, isLight = false,
        ),
    ),
    EMBER(
        light = side(
            bg = 0xFFF7F3EE, surface = 0xFFFFFDFA, raised = 0xFFFBF6F0, sunken = 0xFFEDE6DC,
            high = 0xFFE6DDD1, line = 0xFFDED3C5, lineSoft = 0xFFEBE3D8,
            fg = 0xFF241C14, fg2 = 0xFF695B4B, fg3 = 0xFF95866F,
            accent = 0xFFAC5C1E, accentDim = 0xFFF3DCC5, onAccent = 0xFFFFFFFF,
            good = 0xFF4F7742, warn = 0xFF8A6A2E, bad = 0xFF9E4A3A,
            sel = 0xFFF6E6D3, hover = 0xFFF2ECE3, drop = 0xFFEED6B9, isLight = true,
        ),
        dark = side(
            bg = 0xFF181310, surface = 0xFF1F1915, raised = 0xFF28211B, sunken = 0xFF130F0C,
            high = 0xFF302720, line = 0xFF342A22, lineSoft = 0xFF28211B,
            fg = 0xFFEFE7DE, fg2 = 0xFFA29383, fg3 = 0xFF71655A,
            accent = 0xFFEE9B5F, accentDim = 0xFF523925, onAccent = 0xFF1A1109,
            good = 0xFF9BB88A, warn = 0xFFD9B36A, bad = 0xFFD08A78,
            sel = 0xFF332518, hover = 0xFF261F19, drop = 0xFF4A3520, isLight = false,
        ),
    ),
    MOSS(
        light = side(
            bg = 0xFFF3F5F0, surface = 0xFFFFFFFF, raised = 0xFFF8FAF5, sunken = 0xFFE8EBE3,
            high = 0xFFE1E6DA, line = 0xFFD7DDD0, lineSoft = 0xFFE6EADF,
            fg = 0xFF1E221C, fg2 = 0xFF586053, fg3 = 0xFF848C7D,
            accent = 0xFF4C7A4E, accentDim = 0xFFD3E3CF, onAccent = 0xFFFFFFFF,
            good = 0xFF3F7A4A, warn = 0xFF8A6A2E, bad = 0xFF9B4A4A,
            sel = 0xFFE2EEDF, hover = 0xFFEDF1E9, drop = 0xFFCCE4C8, isLight = true,
        ),
        dark = side(
            bg = 0xFF141613, surface = 0xFF1A1D19, raised = 0xFF202420, sunken = 0xFF101210,
            high = 0xFF272B25, line = 0xFF2B302A, lineSoft = 0xFF202420,
            fg = 0xFFE4E8E0, fg2 = 0xFF939A8D, fg3 = 0xFF676D62,
            accent = 0xFF8FBE8C, accentDim = 0xFF31432F, onAccent = 0xFF0D160C,
            good = 0xFF8FB894, warn = 0xFFC9A36A, bad = 0xFFC97F7F,
            sel = 0xFF23301F, hover = 0xFF1F231E, drop = 0xFF2F4A2C, isLight = false,
        ),
    ),
    INK(
        light = side(
            bg = 0xFFF4F4F3, surface = 0xFFFFFFFF, raised = 0xFFFAFAF9, sunken = 0xFFE9E9E7,
            high = 0xFFE2E2E0, line = 0xFFD8D8D5, lineSoft = 0xFFE7E7E4,
            fg = 0xFF161615, fg2 = 0xFF555553, fg3 = 0xFF878784,
            accent = 0xFF2E2E2C, accentDim = 0xFFD9D9D6, onAccent = 0xFFFFFFFF,
            good = 0xFF3F6B44, warn = 0xFF7E6528, bad = 0xFF8F4242,
            sel = 0xFFE6E6E3, hover = 0xFFEFEFEC, drop = 0xFFD2D2CE, isLight = true,
        ),
        dark = side(
            bg = 0xFF131312, surface = 0xFF1A1A19, raised = 0xFF212120, sunken = 0xFF0E0E0D,
            high = 0xFF282826, line = 0xFF2C2C2A, lineSoft = 0xFF212120,
            fg = 0xFFEDEDEA, fg2 = 0xFF9C9C97, fg3 = 0xFF6C6C68,
            accent = 0xFFD6D6D1, accentDim = 0xFF3A3A37, onAccent = 0xFF131312,
            good = 0xFF8FB894, warn = 0xFFC9A36A, bad = 0xFFC97F7F,
            sel = 0xFF2A2A27, hover = 0xFF202020, drop = 0xFF3B3B37, isLight = false,
        ),
    );

    companion object {
        fun of(choice: AccentChoice): PaletteSpec = when (choice) {
            AccentChoice.SLATE -> SLATE
            AccentChoice.WARM -> EMBER
            AccentChoice.MOSS -> MOSS
            AccentChoice.INK -> INK
        }
    }
}

private fun side(
    bg: Long, surface: Long, raised: Long, sunken: Long, high: Long, line: Long, lineSoft: Long,
    fg: Long, fg2: Long, fg3: Long, accent: Long, accentDim: Long, onAccent: Long,
    good: Long, warn: Long, bad: Long, sel: Long, hover: Long, drop: Long, isLight: Boolean,
): PaletteSide {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return PaletteSide(
        scheme = base.copy(
            primary = Color(accent),
            onPrimary = Color(onAccent),
            primaryContainer = Color(accentDim),
            onPrimaryContainer = Color(fg),
            secondary = Color(accent),
            onSecondary = Color(onAccent),
            secondaryContainer = Color(sel),
            onSecondaryContainer = Color(fg),
            background = Color(bg),
            onBackground = Color(fg),
            surface = Color(surface),
            onSurface = Color(fg),
            surfaceVariant = Color(high),
            onSurfaceVariant = Color(fg2),
            surfaceContainer = Color(raised),
            surfaceContainerHigh = Color(high),
            surfaceContainerLow = Color(sunken),
            surfaceContainerLowest = Color(sunken),
            surfaceContainerHighest = Color(high),
            inverseSurface = Color(fg),
            inverseOnSurface = Color(bg),
            outline = Color(line),
            outlineVariant = Color(lineSoft),
            error = Color(bad),
            onError = Color(if (isLight) 0xFFFFFFFF else 0xFF1A0E0E),
        ),
        extras = FiletColors(
            raised = Color(raised), sunken = Color(sunken), high = Color(high),
            lineSoft = Color(lineSoft), fg2 = Color(fg2), fg3 = Color(fg3),
            good = Color(good), warn = Color(warn), bad = Color(bad),
            sel = Color(sel), hover = Color(hover), drop = Color(drop), accent = Color(accent),
        ),
    )
}

/**
 * The app theme.
 *
 * Deliberately NOT dynamic colour: Material You would override the palette the user picked,
 * and TEMPLATE.md makes dynamic an opt-in rather than the default look.
 */
@Composable
fun FiletTheme(
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    accent: AccentChoice = AccentChoice.SLATE,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val spec = PaletteSpec.of(accent)
    val palette = if (dark) spec.dark else spec.light
    CompositionLocalProvider(LocalFiletColors provides palette.extras) {
        MaterialTheme(
            colorScheme = palette.scheme,
            typography = FiletTypography,
            content = content,
        )
    }
}

/** Shorthand so call sites read `Filet.colors.drop` instead of a composition-local dance. */
object Filet {
    val colors: FiletColors
        @Composable get() = LocalFiletColors.current
}
