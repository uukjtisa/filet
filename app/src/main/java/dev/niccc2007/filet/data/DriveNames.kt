package dev.niccc2007.filet.data

import dev.niccc2007.filet.vfs.VPath
import org.json.JSONObject

/**
 * Names somebody gave their drives, over the names the system gives them.
 *
 * Keyed by the volume's root, written out - the same string the index already uses as a
 * volume's id (`VolumeKey.uuid`). For a card that is `/storage/<its filesystem UUID>`, which
 * Android mounts at the same place every time the card goes back in, so a name outlives the
 * card being taken out. A share's root carries its saved connection's id, so its name follows
 * the connection rather than whichever address answered.
 *
 * Pure: the map in, a name out. Prefs stores it as one JSON object under `drives.names`.
 */
object DriveNames {

    /** The id a drive's name is stored under. */
    fun idOf(root: VPath): String = root.toString()

    /** The name to show: the one given, or [system] when there is none or it is blank. */
    fun resolve(names: Map<String, String>, id: String, system: String): String =
        names[id]?.trim()?.takeIf { it.isNotEmpty() } ?: system

    /**
     * [names] with [id] renamed to [name]. A blank name removes the entry, which is how a
     * drive gets its system name back - there is no separate reset.
     */
    fun renamed(names: Map<String, String>, id: String, name: String): Map<String, String> {
        val clean = name.trim()
        return if (clean.isEmpty()) names - id else names + (id to clean)
    }

    /** Read the stored object. Anything unreadable is no names at all, never a crash. */
    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                for (k in o.keys()) {
                    val v = o.optString(k).trim()
                    if (v.isNotEmpty()) put(k, v)
                }
            }
        }.getOrElse { emptyMap() }
    }

    fun encode(names: Map<String, String>): String =
        JSONObject().apply { for ((k, v) in names) put(k, v) }.toString()
}
