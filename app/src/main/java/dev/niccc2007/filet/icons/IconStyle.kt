package dev.niccc2007.filet.icons

import androidx.compose.runtime.compositionLocalOf
import org.json.JSONObject

/**
 * Which drawing an icon inside Filet wears: the drawn art, or the Legacy line icons it replaced.
 *
 * Inside the app only. Home, Filet's own launcher icon, shortcuts on the home screen and other
 * apps' icons always wear the drawn art, and nothing here reaches them: Home never provides a
 * style, so whatever it draws stays [DRAWN].
 */
enum class IconStyle { DRAWN, LEGACY }

/**
 * How a place draws its folders. Settings > Appearance > Legacy icons > Folders:
 * Drawn · Drawn, whole colour · Legacy · Legacy outline.
 *
 * [WITH_PICTURES] is the Legacy folder (up to three pictures from inside, fanned); [OUTLINE] is
 * the bare Legacy outline, which shows no emblem, no pictures and no cover. [DRAWN_WHOLE] paints
 * the whole folder in its colour where [DRAWN] paints the emblem. A folder's own paint, set in
 * Change icon, wins over either; see [FolderLook.paint].
 */
enum class FolderDrawing { DRAWN, DRAWN_WHOLE, WITH_PICTURES, OUTLINE }

/** One place's looks: its folders and its files, chosen apart. */
data class PlaceLook(
    val folders: FolderDrawing = FolderDrawing.DRAWN,
    val files: IconStyle = IconStyle.DRAWN,
) {
    companion object {
        val DRAWN = PlaceLook()
    }
}

/**
 * The places that can wear Legacy icons, each chosen on its own. Kept as `look.legacy.v1`.
 *
 * All drawn until the setting says otherwise. A place provides [LocalPlaceLook] (folders and
 * files) or [LocalIconStyle] (tabs, the Shortcuts tab) around what it draws; the drawing code
 * reads that one value and never asks which place it is in.
 *
 * @param tabs the tab strip's chips and the bottom bar's picker list.
 * @param files folders and files in the lists and pickers.
 * @param gallery the six category galleries and the grid views.
 * @param shortcuts the rows of the Shortcuts tab.
 */
data class IconStyles(
    val tabs: IconStyle = IconStyle.DRAWN,
    val files: PlaceLook = PlaceLook.DRAWN,
    val gallery: PlaceLook = PlaceLook.DRAWN,
    val shortcuts: IconStyle = IconStyle.DRAWN,
) {
    /** The stored form. Only what differs from drawn is written, so the default is `{}`. */
    fun toJson(): String = JSONObject().apply {
        if (tabs != IconStyle.DRAWN) put("tabs", tabs.name)
        placeJson(files)?.let { put("files", it) }
        placeJson(gallery)?.let { put("gallery", it) }
        if (shortcuts != IconStyle.DRAWN) put("shortcuts", shortcuts.name)
    }.toString()

    companion object {
        /** Every place on the drawn art: the default, and what Home always gets. */
        val DRAWN = IconStyles()

        /** Reads [toJson]'s form. Anything missing, unknown or unreadable reads as drawn. */
        fun fromJson(raw: String?): IconStyles {
            val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DRAWN
            return IconStyles(
                tabs = styleOf(o.optString("tabs")),
                files = placeOf(o.optJSONObject("files")),
                gallery = placeOf(o.optJSONObject("gallery")),
                shortcuts = styleOf(o.optString("shortcuts")),
            )
        }

        private fun styleOf(name: String?): IconStyle =
            IconStyle.entries.firstOrNull { it.name == name } ?: IconStyle.DRAWN

        private fun placeOf(o: JSONObject?): PlaceLook = if (o == null) PlaceLook.DRAWN else PlaceLook(
            folders = FolderDrawing.entries.firstOrNull { it.name == o.optString("folders") } ?: FolderDrawing.DRAWN,
            files = styleOf(o.optString("files")),
        )

        private fun placeJson(p: PlaceLook): JSONObject? = if (p == PlaceLook.DRAWN) null else JSONObject().apply {
            if (p.folders != FolderDrawing.DRAWN) put("folders", p.folders.name)
            if (p.files != IconStyle.DRAWN) put("files", p.files.name)
        }
    }
}

/** The style for whatever single-style place is being drawn now. Unprovided means [IconStyle.DRAWN]. */
val LocalIconStyle = compositionLocalOf { IconStyle.DRAWN }

/** The folders-and-files look for whatever is being drawn now. Unprovided means drawn. */
val LocalPlaceLook = compositionLocalOf { PlaceLook.DRAWN }

/** Every place's style, for the code that draws more than one place (the tab strip, the bar). */
val LocalIconStyles = compositionLocalOf { IconStyles.DRAWN }
