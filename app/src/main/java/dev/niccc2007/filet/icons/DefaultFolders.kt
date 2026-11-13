package dev.niccc2007.filet.icons

import dev.niccc2007.filet.vfs.VPath
import java.util.Locale

/**
 * The folders that wear an emblem without being asked: Download, Music, DCIM and the rest.
 *
 * Only directly at the top of a drive. Android's own folders live there, and the same name deeper
 * down is somebody's own folder that happens to be called Music; dressing it as the phone's would
 * be a guess. So DCIM/Camera and Pictures/Screenshots get nothing, and neither do app folders
 * kept inside Android/media.
 *
 * The table itself is the approved art, generated from the icon mock: see [DEFAULT_FOLDER_ART].
 */
object DefaultFolders {

    /** The look of [path] when it is a standard folder at the top of a drive, or null. */
    fun lookOf(path: VPath): FolderLook? {
        if (path.scheme != "local") return null
        val name = topLevelName(path.path) ?: return null
        val art = byName[name.lowercase(Locale.US)] ?: return null
        return FolderLook(art.emblem, art.colour)
    }

    /**
     * The name of [osPath] when it sits directly in a drive's top folder, or null.
     *
     * The phone's own storage is `/storage/emulated/<user>`; a card or a USB drive is
     * `/storage/<its id>`. Anything deeper, or anything outside `/storage`, is not at the top.
     */
    internal fun topLevelName(osPath: String): String? {
        val s = osPath.trim('/').split('/')
        if (s.isEmpty() || s[0] != "storage") return null
        return when {
            s.size == 4 && s[1] == "emulated" && s[2].all { it.isDigit() } -> s[3]
            s.size == 3 && s[1] != "emulated" && s[1] != "self" -> s[2]
            else -> null
        }.takeUnless { it.isNullOrEmpty() }
    }

    private val byName: Map<String, DefaultFolderArt> by lazy {
        DEFAULT_FOLDER_ART.associateBy { it.place.lowercase(Locale.US) }
    }
}
