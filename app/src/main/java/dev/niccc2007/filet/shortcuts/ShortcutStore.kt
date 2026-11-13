package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.content.pm.ShortcutManager
import dev.niccc2007.filet.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What a shortcut points at. */
enum class ShortcutKind { FILE, SCRIPT, ACTION, TAB }

/**
 * A shortcut Filet made, as Filet remembers it.
 *
 * The launcher owns the icon on the home screen and will not tell us much about it - notably
 * it cannot be asked "is this one still there" for a *pinned* shortcut on every OEM. So this
 * is Filet's own record: what was made, when, pointing where.
 *
 * @param target a file id for [ShortcutKind.FILE], a script id for [ShortcutKind.SCRIPT], an
 *   [AppAction] name for [ShortcutKind.ACTION], or a `TabTarget` id for [ShortcutKind.TAB].
 * @param unconfirmed placed on a launcher that never says what it placed, so Filet cannot know
 *   whether it was added or turned down. See [PinReconcile].
 * @param dir for a [ShortcutKind.FILE], whether it is a folder. Null on records made before this
 *   was kept: the name alone cannot say, so those rows draw a neutral glyph rather than a guess.
 */
data class ShortcutRecord(
    val id: String,
    val kind: ShortcutKind,
    val label: String,
    val target: String,
    val handler: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val unconfirmed: Boolean = false,
    val dir: Boolean? = null,
) {
    fun kindLabel(): String = when (kind) {
        ShortcutKind.FILE -> "File"
        ShortcutKind.SCRIPT -> "Script"
        ShortcutKind.ACTION -> "Action"
        ShortcutKind.TAB -> "Tab"
    }
}

/**
 * Things worth putting on a home screen that are not files and not tabs.
 *
 * Deliberately short. A home screen is scarce space, and a shortcut to a setting nobody
 * changes twice is clutter - each of these is something you *do*, repeatedly.
 *
 * @param listed false for the two that are tabs: Bookmarks and Opened are offered as tabs now,
 *   and stay here only so the icons pinned before that still open.
 * @param home the name a home-screen shortcut is offered with, as for a tab: Filet first where the
 *   plain name could be any app's.
 */
enum class AppAction(
    val label: String,
    val description: String,
    val listed: Boolean = true,
    val home: String = label,
) {
    SEARCH("Search", "Open Filet with the search box focused", home = "Filet Search"),
    INDEX_NOW("Index now", "Start a crawl without opening Settings"),
    SHARE_NEARBY("Start sharing", "Turn on the LAN share and show the code"),
    STOP_SHARING("Stop sharing", "Turn the LAN share off without opening the tab"),
    BOOKMARKS("Bookmarks", "Open the starred list", listed = false),
    RECENT("Opened", "Files you opened in Filet", listed = false),
    ;

    /** The action's id in the tab icon mock, which names its icons. The two old tab actions wear their tab's. */
    val art: String
        get() = when (this) {
            SEARCH -> "search"
            INDEX_NOW -> "index"
            SHARE_NEARBY -> "startshare"
            STOP_SHARING -> "stopshare"
            BOOKMARKS -> "bookmarks"
            RECENT -> "opened"
        }

    companion object {
        /** The ones the Shortcuts tab offers, in declared order. */
        val LISTED: List<AppAction> get() = entries.filter { it.listed }

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
     * Ids the launcher reports as pinned, or null when it could not be asked.
     *
     * Null and empty are different answers. The call throws while the phone is still locked after
     * a restart, and reading that as "nothing is on the home screen" would wipe the whole list on
     * a launcher known to report. Some OEM launchers report nothing even while the icons are
     * plainly there, so an empty answer is only trusted once the launcher has been seen
     * reporting: see [PinReconcile].
     */
    private fun pinnedOrNull(): Set<String>? = runCatching {
        context.getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.mapTo(HashSet()) { it.id }
    }.getOrNull()

    private val pending = java.util.concurrent.ConcurrentHashMap<String, PinReconcile.Pending>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Whether Filet is the app in front. The launcher's Add or Cancel dialog sits on top of Filet
     * while it is open, and a request is not judged while it is: somebody reading the dialog for
     * longer than the grace time has not turned it down.
     */
    @Volatile private var inFront = false

    /**
     * The launcher has been asked to place [record]. It is listed once the launcher confirms it,
     * and never if the person presses Cancel or Back. See [PinReconcile].
     */
    fun ask(record: ShortcutRecord) {
        pending[record.id] = PinReconcile.Pending(record, System.currentTimeMillis())
        reconcileSoon()
    }

    /** The launcher refused the request outright, so no answer is coming. */
    fun withdraw(id: String) { pending.remove(id) }

    /**
     * Filet is in front again, which is what closing the launcher's dialog does. Every open request
     * gets its full grace time from now, for the launcher to report what it did.
     */
    fun resumed() {
        inFront = true
        val now = System.currentTimeMillis()
        pending.replaceAll { _, p -> p.copy(at = now) }
        reconcileSoon()
    }

    fun paused() { inFront = false }

    /** The launcher's own word that [record] is on the home screen. From [PinResultReceiver]. */
    fun confirm(record: ShortcutRecord) {
        pending.remove(record.id)
        if (prefs.getString(KEY_REPORTS) == null) prefs.putString(KEY_REPORTS, "1")
        main.post {
            _all.value = _all.value.filterNot { it.id == record.id } + record.copy(unconfirmed = false)
            save()
        }
    }

    /**
     * Bring the list in line with the home screen: confirm what was placed, drop what was turned
     * down or taken off. Run when Filet comes back to the front, which is what closing the
     * launcher's dialog does, and on opening the Shortcuts tab.
     */
    fun reconcile() {
        if (!inFront) return
        val pinned = pinnedOrNull() ?: return
        if (pinned.isNotEmpty() && prefs.getString(KEY_REPORTS) == null) prefs.putString(KEY_REPORTS, "1")
        val out = PinReconcile.decide(
            records = _all.value,
            pending = pending.values.toList(),
            pinned = pinned,
            reports = prefs.getString(KEY_REPORTS) != null,
            now = System.currentTimeMillis(),
        )
        val waiting = out.pending.mapTo(HashSet()) { it.record.id }
        pending.keys.retainAll(waiting)
        if (out.records != _all.value) {
            _all.value = out.records
            save()
        }
    }

    /** [reconcile] now, and once more after the grace time while a request is still unanswered. */
    fun reconcileSoon() {
        main.post { reconcile() }
        main.postDelayed({ if (pending.isNotEmpty()) reconcile() }, PinReconcile.GRACE_MS + 250)
    }

    // ── Folds on the Shortcuts tab ──
    //
    // Which lists are folded is a choice about this screen, so it is kept with the records rather
    // than in Settings. Absent means never touched, and gives the defaults: the catalogues of
    // actions and scripts start folded, the home-screen list and the tabs open.

    private val _folded = MutableStateFlow(loadFolds())
    val folded: StateFlow<Set<String>> = _folded.asStateFlow()

    fun toggleFold(section: String) {
        val now = _folded.value.let { if (section in it) it - section else it + section }
        _folded.value = now
        prefs.putString(KEY_FOLDS, now.sorted().joinToString(","))
    }

    /** Open [section], for a link that points into it. */
    fun unfold(section: String) {
        if (section in _folded.value) toggleFold(section)
    }

    private fun loadFolds(): Set<String> =
        prefs.getString(KEY_FOLDS)?.split(',')?.filterTo(HashSet()) { it.isNotBlank() } ?: DEFAULT_FOLDED

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
                    unconfirmed = o.optBoolean("unconfirmed", false),
                    dir = if (o.has("dir")) o.optBoolean("dir") else null,
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
                    .put("unconfirmed", r.unconfirmed)
                    .apply { r.dir?.let { put("dir", it) } }
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    companion object {
        private const val KEY = "shortcuts.v1"

        /** Set once this phone's launcher has reported a pin: from then on, unreported means gone. */
        private const val KEY_REPORTS = "shortcuts.reports.v1"

        /** The sections folded on the Shortcuts tab, comma-separated. */
        private const val KEY_FOLDS = "shortcuts.folds.v1"

        const val FOLD_PLACED = "home"
        const val FOLD_TABS = "tabs"
        const val FOLD_ACTIONS = "actions"
        const val FOLD_SCRIPTS = "scripts"
        val DEFAULT_FOLDED: Set<String> = setOf(FOLD_ACTIONS, FOLD_SCRIPTS)
    }
}
