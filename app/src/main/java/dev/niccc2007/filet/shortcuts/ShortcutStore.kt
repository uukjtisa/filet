package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.content.pm.ShortcutManager
import dev.niccc2007.filet.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What a shortcut points at. Three kinds, because three things are worth a home-screen tap. */
enum class ShortcutKind { FILE, SCRIPT, ACTION }

/**
 * A shortcut Filet made, as Filet remembers it.
 *
 * The launcher owns the icon on the home screen and will not tell us much about it - notably
 * it cannot be asked "is this one still there" for a *pinned* shortcut on every OEM. So this
 * is Filet's own record: what was made, when, pointing where.
 *
 * @param target a file id for [ShortcutKind.FILE], a script id for [ShortcutKind.SCRIPT], or
 *   an [AppAction] name for [ShortcutKind.ACTION].
 */
data class ShortcutRecord(
    val id: String,
    val kind: ShortcutKind,
    val label: String,
    val target: String,
    val handler: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun kindLabel(): String = when (kind) {
        ShortcutKind.FILE -> "File"
        ShortcutKind.SCRIPT -> "Script"
        ShortcutKind.ACTION -> "Action"
    }
}

/**
 * Things worth putting on a home screen that are not files.
 *
 * Deliberately short. A home screen is scarce space, and a shortcut to a setting nobody
 * changes twice is clutter - each of these is something you *do*, repeatedly.
 */
enum class AppAction(val label: String, val description: String) {
    INDEX_NOW("Index now", "Start a crawl without opening Settings"),
    SHARE_NEARBY("Start sharing", "Turn on the LAN share and show the code"),
    SEARCH("Search", "Open Filet with the search box focused"),
    RECENT("Recent", "Open the recent files list"),
    ;

    companion object {
        /**
         * Null rather than a throw for a name this build does not have.
         *
         * A shortcut outlives the version that made it: a launcher icon pinned before an
         * action was renamed would otherwise crash the app on tap rather than say so.
         */
        fun parse(raw: String?): AppAction? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * The shortcuts Filet has made, listed in the app.
 *
 * Pinned shortcuts are otherwise write-only: you make one and it vanishes into the launcher.
 * Keeping a record means they can be renamed, re-pointed and cleaned up from inside the app,
 * and it is the only way to answer "what did I put on my home screen".
 */
class ShortcutStore(private val context: Context, private val prefs: Prefs) {

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<ShortcutRecord>> = _all.asStateFlow()

    fun remember(record: ShortcutRecord) {
        _all.value = _all.value.filterNot { it.id == record.id } + record
        save()
    }

    fun forget(id: String) {
        _all.value = _all.value.filterNot { it.id == id }
        save()
    }

    fun rename(id: String, label: String) {
        _all.value = _all.value.map { if (it.id == id) it.copy(label = label) else it }
        save()
        // Push the new name to the launcher too, when it still has this one.
        runCatching {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return@runCatching
            val live = sm.pinnedShortcuts.firstOrNull { it.id == id } ?: return@runCatching
            sm.updateShortcuts(
                listOf(
                    android.content.pm.ShortcutInfo.Builder(context, live.id)
                        .setShortLabel(label.take(24))
                        .setLongLabel(label.take(48))
                        .build()
                )
            )
        }
    }

    /**
     * Ids the launcher still reports as pinned.
     *
     * Advisory only. Several OEM launchers return an empty list here even while the icons are
     * plainly on the home screen, so a missing id is shown as "not on the home screen" rather
     * than used to delete the record - guessing wrong would silently lose the user's list.
     */
    fun livePinnedIds(): Set<String> = runCatching {
        context.getSystemService(ShortcutManager::class.java)
            ?.pinnedShortcuts?.mapTo(HashSet()) { it.id }
            ?: emptySet()
    }.getOrDefault(emptySet())

    /** Re-read from prefs. Another pane, a widget or the launcher may have changed them. */
    fun reload() { _all.value = load() }

    private fun load(): List<ShortcutRecord> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ShortcutRecord(
                    id = o.getString("id"),
                    kind = runCatching { ShortcutKind.valueOf(o.optString("kind")) }
                        .getOrDefault(ShortcutKind.FILE),
                    label = o.optString("label"),
                    target = o.optString("target"),
                    handler = o.optString("handler").ifEmpty { null },
                    createdAt = o.optLong("at"),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save() {
        val arr = JSONArray()
        for (r in _all.value) {
            arr.put(
                JSONObject()
                    .put("id", r.id).put("kind", r.kind.name).put("label", r.label)
                    .put("target", r.target).put("handler", r.handler).put("at", r.createdAt)
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object { const val KEY = "shortcuts.v1" }
}
