package dev.niccc2007.filet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How wide the pane the caller is drawing inside actually is, in dp.
 *
 * ## Why this exists at all
 *
 * The file list has always laid itself out against its own width rather than the screen's -
 * see `PaneMetrics` - but everything that is NOT the file list did not. Settings, Home,
 * Playlists and the rest were written as full-screen pages, so in a split they were drawn at
 * half the width with full-screen measurements: *"when in dual pane and i open the settings
 * its so cramped and bad looking same for the home and other tabs."*
 *
 * And it was. A segmented control laid out four options side by side regardless of room, so
 * the last one spilled past its own border a letter per line; a heading given weight next to
 * two unweighted links was squeezed until the word STORAGE broke in half.
 *
 * ## Why a local, and not a parameter
 *
 * Because the screens that need the answer are a dozen levels below the thing that knows it,
 * and most of what is in between has no business caring. Threading a width through
 * `SettingsPage` to reach `SegmentRow` means every page in the app grows a parameter so that
 * one row can read it.
 *
 * [staticCompositionLocalOf] rather than `compositionLocalOf`: a pane's width changes when
 * the divider is dragged, which re-lays-out everything under it anyway, so there is nothing
 * to be gained from tracking reads individually and a static local is the cheaper read.
 *
 * The default is deliberately large. Anything drawn outside a pane - a dialog, a sheet, the
 * drawer - is full-width by construction, and a default that claimed to be narrow would put
 * every one of them into the cramped layout.
 */
val LocalPaneWidth: ProvidableCompositionLocal<Int> = staticCompositionLocalOf { FULL_WIDTH }

/**
 * Is there so little room that the full-width layout stops working?
 *
 * 300dp, which is a hair under half of the narrowest phone this runs on. Below it a settings
 * row has about 170dp for its text once the padding and a switch are taken out, and that is
 * where two-column arrangements start costing more than they give.
 */
@Composable
fun narrowPane(under: Int = NARROW): Boolean = LocalPaneWidth.current < under

/** See [narrowPane]. */
const val NARROW = 300

/** The default: not in a pane, so not constrained by one. */
const val FULL_WIDTH = 10_000
