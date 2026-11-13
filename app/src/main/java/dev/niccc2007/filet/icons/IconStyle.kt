package dev.niccc2007.filet.icons

import androidx.compose.runtime.compositionLocalOf

/**
 * Which drawing an icon inside Filet wears: the drawn art, or the plainer icons it replaced.
 *
 * Inside the app only. Home, Filet's own launcher icon, shortcuts on the home screen and other
 * apps' icons always wear the drawn art, and nothing here reaches them: Home never provides a
 * style, so whatever it draws stays [DRAWN].
 */
enum class IconStyle { DRAWN, PLAIN }

/**
 * The places that can wear the plainer icons, each switched on its own.
 *
 * All [IconStyle.DRAWN] until a setting says otherwise. A place that wants its style provides
 * [LocalIconStyle] around what it draws; the drawing code reads that one value and never asks
 * which place it is in.
 *
 * @param tabs the tab strip's chips and the bottom bar's picker.
 * @param files folders and files in the lists, tiles and pickers.
 * @param gallery the photo, video and other category galleries.
 * @param shortcuts the rows of the Shortcuts tab.
 */
data class IconStyles(
    val tabs: IconStyle = IconStyle.DRAWN,
    val files: IconStyle = IconStyle.DRAWN,
    val gallery: IconStyle = IconStyle.DRAWN,
    val shortcuts: IconStyle = IconStyle.DRAWN,
) {
    companion object {
        /** Every place on the drawn art: the default, and what Home always gets. */
        val DRAWN = IconStyles()
    }
}

/** The style for whatever is being drawn now. Unprovided means [IconStyle.DRAWN]. */
val LocalIconStyle = compositionLocalOf { IconStyle.DRAWN }

/** Every place's style, for the code that draws more than one place (the tab strip, the bar). */
val LocalIconStyles = compositionLocalOf { IconStyles.DRAWN }
