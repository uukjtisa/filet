package dev.niccc2007.filet.data

import android.content.Context
import android.content.SharedPreferences
import dev.niccc2007.filet.browser.EntryDisplay
import dev.niccc2007.filet.browser.DateStyle
import dev.niccc2007.filet.browser.DateOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every persisted user choice, in one place.
 *
 * Deliberately a thin wrapper over [SharedPreferences] rather than DataStore: these are a
 * few dozen scalars read synchronously at startup, and a suspend read on the first frame
 * would mean the browser opens with the wrong sort order and then flickers into the right one.
 *
 * Each setting is exposed as a [StateFlow] so Compose recomposes without an observer dance.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("filet", Context.MODE_PRIVATE)

    // ── browsing ──
    private val _sort = MutableStateFlow(
        SortSpec(
            key = SortKey.valueOfOr(sp.getString(K_SORT, null), SortKey.NAME),
            descending = sp.getBoolean(K_SORT_DESC, dev.niccc2007.filet.browser.SortOrder.DEFAULT_DESCENDING),
            foldersFirst = sp.getBoolean(K_FOLDERS_FIRST, true),
        )
    )
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    private val _showHidden = MutableStateFlow(sp.getBoolean(K_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    /**
     * What a row in the file list shows, and how it writes a date.
     *
     * Read as one value rather than as seven flags, because the settings screen previews it and a
     * preview has to be given the whole thing to render. See [EntryDisplay].
     */
    private val _entry = MutableStateFlow(
        EntryDisplay(
            subtitle = sp.getBoolean(K_E_SUB, true),
            measure = sp.getBoolean(K_E_MEASURE, true),
            dateColumn = sp.getBoolean(K_E_DATECOL, false),
            style = enumOr(sp.getString(K_E_STYLE, null), DateStyle.NUMERIC),
            order = enumOr(sp.getString(K_E_ORDER, null), DateOrder.YMD),
            separator = sp.getString(K_E_SEP, "-") ?: "-",
            relative = sp.getBoolean(K_E_REL, true),
        )
    )
    val entry: StateFlow<EntryDisplay> = _entry.asStateFlow()

    /**
     * What dragging between panes does.
     *
     * Defaults to asking. The two outcomes are not equally recoverable - a copy that should have
     * been a move leaves a duplicate to delete, while a move that should have been a copy has
     * already taken the file off the source, which across a network may be a device nobody is
     * holding.
     */
    private val _drag = MutableStateFlow(
        enumOr(sp.getString(K_DRAG, null), dev.niccc2007.filet.browser.DragBehaviour.ASK)
    )
    val drag: StateFlow<dev.niccc2007.filet.browser.DragBehaviour> = _drag.asStateFlow()

    fun setDrag(b: dev.niccc2007.filet.browser.DragBehaviour) {
        _drag.value = b
        sp.edit().putString(K_DRAG, b.name).apply()
    }

    fun setEntry(d: EntryDisplay) {
        _entry.value = d
        sp.edit()
            .putBoolean(K_E_SUB, d.subtitle)
            .putBoolean(K_E_MEASURE, d.measure)
            .putBoolean(K_E_DATECOL, d.dateColumn)
            .putString(K_E_STYLE, d.style.name)
            .putString(K_E_ORDER, d.order.name)
            .putString(K_E_SEP, d.separator)
            .putBoolean(K_E_REL, d.relative)
            .apply()
    }

    /**
     * Whether the tabs you had open come back the next time Filet starts.
     *
     * Defaults to ON, because that is what the app is expected to do and because
     * restoring work is the behaviour that loses nothing. Off starts with the Home tab alone.
     */
    private val _restoreTabs = MutableStateFlow(sp.getBoolean(K_RESTORE_TABS, true))
    val restoreTabs: StateFlow<Boolean> = _restoreTabs.asStateFlow()

    /** The bottom bar, as stored ids. Always read through `BottomBarConfig.normalise`. */
    private val _bottomBar = MutableStateFlow(sp.getString(K_BOTTOM_BAR, null))
    val bottomBar: StateFlow<String?> = _bottomBar.asStateFlow()

    /**
     * Extensions added by hand in the default-opener settings.
     *
     * Persisted as soon as one is typed. Adding an extension used to go straight into a
     * chooser, so an extension nothing claimed could not be added at all - the chooser refused
     * and there was nothing left behind. Adding and choosing are separate now.
     */
    private val _customExtensions =
        MutableStateFlow(sp.getStringSet(K_CUSTOM_EXT, emptySet())!!.toSet())
    val customExtensions: StateFlow<Set<String>> = _customExtensions.asStateFlow()

    /**
     * Hide the storage cards on Home.
     *
     * On a phone with one volume they are a fifth of the first screen restating something you
     * already know. Off by default, because on a phone WITH an SD card they are the fastest
     * way to reach it.
     */
    private val _hideStorage = MutableStateFlow(sp.getBoolean(K_HIDE_STORAGE, false))
    val hideStorage: StateFlow<Boolean> = _hideStorage.asStateFlow()

    /**
     * One control for the whole view, exactly as the mock settled it: six steps from a
     * compact list to a large grid. Two separate knobs (mode + density) let a user pick
     * combinations that do not exist, which is how the mock's slider replaced them.
     */
    private val _viewStep = MutableStateFlow(sp.getInt(K_VIEW_STEP, 2).coerceIn(1, 6))
    val viewStep: StateFlow<Int> = _viewStep.asStateFlow()

    private val _theme = MutableStateFlow(ThemeChoice.valueOfOr(sp.getString(K_THEME, null), ThemeChoice.SYSTEM))
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _accent = MutableStateFlow(AccentChoice.valueOfOr(sp.getString(K_ACCENT, null), AccentChoice.SLATE))
    val accent: StateFlow<AccentChoice> = _accent.asStateFlow()

    private val _splitRatio = MutableStateFlow(sp.getFloat(K_SPLIT, 0.5f))
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    /**
     * A folder Home opens instead of the overview, or null for the overview.
     *
     * Stored as a string rather than resolved here: the path outlives the folder, and deciding
     * what to do about that belongs to [dev.niccc2007.filet.browser.HomeTarget], which has
     * tests on the falling-back.
     */
    private val _homeFolder = MutableStateFlow(sp.getString(K_HOME_FOLDER, null))
    val homeFolder: StateFlow<String?> = _homeFolder.asStateFlow()

    fun setHomeFolder(path: String?) {
        sp.edit().putString(K_HOME_FOLDER, path).apply()
        _homeFolder.value = path
    }

    /**
     * Whether the one-time "you can change this back" note has ever been shown.
     *
     * Once in the whole app's life, which is why it lives in preferences and not in any
     * screen's state.
     */
    private val _homeHintShown = MutableStateFlow(sp.getBoolean(K_HOME_HINT, false))
    val homeHintShown: StateFlow<Boolean> = _homeHintShown.asStateFlow()

    fun markHomeHintShown() {
        sp.edit().putBoolean(K_HOME_HINT, true).apply()
        _homeHintShown.value = true
    }

    private val _indexEnabled = MutableStateFlow(sp.getBoolean(K_INDEX, true))
    val indexEnabled: StateFlow<Boolean> = _indexEnabled.asStateFlow()

    private val _tabSize = MutableStateFlow(TabSize.valueOfOr(sp.getString(K_TAB_SIZE, null), TabSize.NORMAL))
    val tabSize: StateFlow<TabSize> = _tabSize.asStateFlow()

    /**
     * How the actions for a selection are shown.
     *
     * Which selection actions sit on the bar, comma separated.
     *
     * Replaces a setting that chose between a bar and a menu. That question had no good answer:
     * the bar could not hold fifteen actions so it scrolled and hid some, and the menu put the
     * four anybody uses two taps away. One bar plus More is the answer to it, and the setting
     * that survives is the one that actually differs per person - which four.
     */
    private val _selectionBar = MutableStateFlow(sp.getString(K_SELECTION_BAR, null))
    val selectionBar: StateFlow<String?> = _selectionBar.asStateFlow()

    /**
     * Storage cards the user has hidden, by VPath.
     *
     * Per card, not the whole section - a correction. The first version hid the entire
     * storage block; what the design wanted was to hide individual cards, particularly the ones that
     * led nowhere.
     *
     * A phone's `/storage` holds directories that pass for volumes and are not usable ones -
     * `media` on this Huawei is the example - and Filet cannot tell them apart from a genuinely
     * mounted card it lacks permission for. Guessing would eventually hide somebody's SD card,
     * so the user decides and the decision sticks.
     */
    private val _hiddenCards = MutableStateFlow(sp.getStringSet(K_HIDDEN_CARDS, emptySet())!!.toSet())
    val hiddenCards: StateFlow<Set<String>> = _hiddenCards.asStateFlow()

    /**
     * Hide the Termux card on Home.
. Hidden means hidden from HOME, not switched off - the connect action stays in
     * Settings, because an option that vanishes completely is one nobody can find again.
     */
    private val _hideTermux = MutableStateFlow(sp.getBoolean(K_HIDE_TERMUX, false))
    val hideTermux: StateFlow<Boolean> = _hideTermux.asStateFlow()

    // ── updates ──

    /**
     * How much of the screen the release notes get, as a fraction.
     *
     * Stored rather than reset each time because it is a reading preference, and somebody who
     * pulled the pane open to read a long changelog wants it open for the next one too.
     * 0 means never set; the clamping lives in `usableNotesFraction`.
     */
    private val _notesFraction = MutableStateFlow(sp.getFloat(K_NOTES_FRACTION, 0f))
    val notesFraction: StateFlow<Float> = _notesFraction.asStateFlow()

    /** Everything the update nag has been told. See `UpdatePrompt.kt`. */
    private val _updateSilencedUntil = MutableStateFlow(sp.getLong(K_UPD_UNTIL, 0L))
    val updateSilencedUntil: StateFlow<Long> = _updateSilencedUntil.asStateFlow()

    private val _updateSilencedVersion = MutableStateFlow(sp.getString(K_UPD_VERSION, "").orEmpty())
    val updateSilencedVersion: StateFlow<String> = _updateSilencedVersion.asStateFlow()

    private val _updateSkippedVersion = MutableStateFlow(sp.getString(K_UPD_SKIPPED, "").orEmpty())
    val updateSkippedVersion: StateFlow<String> = _updateSkippedVersion.asStateFlow()

    private val _updateNotificationsOff = MutableStateFlow(sp.getBoolean(K_UPD_OFF, false))
    val updateNotificationsOff: StateFlow<Boolean> = _updateNotificationsOff.asStateFlow()

    private val _updateLastCheckedAt = MutableStateFlow(sp.getLong(K_UPD_CHECKED, 0L))
    val updateLastCheckedAt: StateFlow<Long> = _updateLastCheckedAt.asStateFlow()

    fun setSort(s: SortSpec) {
        _sort.value = s
        sp.edit().putString(K_SORT, s.key.name).putBoolean(K_SORT_DESC, s.descending)
            .putBoolean(K_FOLDERS_FIRST, s.foldersFirst).apply()
    }

    fun setShowHidden(v: Boolean) { _showHidden.value = v; sp.edit().putBoolean(K_HIDDEN, v).apply() }

    fun setRestoreTabs(v: Boolean) { _restoreTabs.value = v; sp.edit().putBoolean(K_RESTORE_TABS, v).apply() }

    fun setBottomBar(v: String) { _bottomBar.value = v; sp.edit().putString(K_BOTTOM_BAR, v).apply() }

    fun addCustomExtension(ext: String) {
        if (ext.isBlank()) return
        val next = _customExtensions.value + ext
        _customExtensions.value = next
        sp.edit().putStringSet(K_CUSTOM_EXT, next).apply()
    }

    fun removeCustomExtension(ext: String) {
        val next = _customExtensions.value - ext
        _customExtensions.value = next
        sp.edit().putStringSet(K_CUSTOM_EXT, next).apply()
    }
    fun setHideStorage(v: Boolean) { _hideStorage.value = v; sp.edit().putBoolean(K_HIDE_STORAGE, v).apply() }

    fun setHideTermux(v: Boolean) { _hideTermux.value = v; sp.edit().putBoolean(K_HIDE_TERMUX, v).apply() }

    fun hideCard(path: String) = setHiddenCards(_hiddenCards.value + path)

    fun showAllCards() = setHiddenCards(emptySet())

    private fun setHiddenCards(v: Set<String>) {
        _hiddenCards.value = v
        // A COPY into the editor. SharedPreferences keeps the very set it is handed and
        // documents that mutating it afterwards is undefined; handing it the same instance the
        // StateFlow holds is the classic way that becomes a bug nobody can reproduce.
        sp.edit().putStringSet(K_HIDDEN_CARDS, HashSet(v)).apply()
    }

    fun setSelectionBar(v: String) {
        _selectionBar.value = v
        sp.edit().putString(K_SELECTION_BAR, v).apply()
    }

    fun setNotesFraction(v: Float) { _notesFraction.value = v; sp.edit().putFloat(K_NOTES_FRACTION, v).apply() }

    /**
     * Write the whole reminder state at once.
     *
     * One call rather than five setters: the fields only make sense together - a deadline
     * without the version it belongs to silences the wrong release - and five separate writes
     * is five chances to persist half of an answer.
     */
    fun setUpdateReminder(
        silencedUntil: Long,
        silencedVersion: String,
        skippedVersion: String,
        notificationsOff: Boolean,
        lastCheckedAt: Long,
    ) {
        _updateSilencedUntil.value = silencedUntil
        _updateSilencedVersion.value = silencedVersion
        _updateSkippedVersion.value = skippedVersion
        _updateNotificationsOff.value = notificationsOff
        _updateLastCheckedAt.value = lastCheckedAt
        sp.edit()
            .putLong(K_UPD_UNTIL, silencedUntil)
            .putString(K_UPD_VERSION, silencedVersion)
            .putString(K_UPD_SKIPPED, skippedVersion)
            .putBoolean(K_UPD_OFF, notificationsOff)
            .putLong(K_UPD_CHECKED, lastCheckedAt)
            .apply()
    }

    fun setViewStep(v: Int) {
        val c = v.coerceIn(1, 6)
        _viewStep.value = c
        sp.edit().putInt(K_VIEW_STEP, c).apply()
    }
    fun setTheme(v: ThemeChoice) { _theme.value = v; sp.edit().putString(K_THEME, v.name).apply() }
    fun setAccent(v: AccentChoice) { _accent.value = v; sp.edit().putString(K_ACCENT, v.name).apply() }
    fun setIndexEnabled(v: Boolean) { _indexEnabled.value = v; sp.edit().putBoolean(K_INDEX, v).apply() }
    fun setTabSize(v: TabSize) { _tabSize.value = v; sp.edit().putString(K_TAB_SIZE, v.name).apply() }

    /** Written on drag release only — persisting every frame of a divider drag would thrash disk. */
    fun setSplitRatio(v: Float) {
        val c = v.coerceIn(0.2f, 0.8f)
        _splitRatio.value = c
        sp.edit().putFloat(K_SPLIT, c).apply()
    }

    // ── free-form string slots, used by features that own their own encoding ──
    fun getString(key: String, def: String? = null): String? = sp.getString(key, def)
    fun putString(key: String, value: String?) { sp.edit().putString(key, value).apply() }
    fun getBool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    fun getInt(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    fun getLong(key: String, def: Long) = sp.getLong(key, def)
    fun putLong(key: String, value: Long) { sp.edit().putLong(key, value).apply() }

    private companion object {
        const val K_SORT = "sort.key"
        const val K_SORT_DESC = "sort.desc"
        const val K_FOLDERS_FIRST = "sort.foldersFirst"
        const val K_HIDDEN = "browse.hidden"
        const val K_RESTORE_TABS = "browse.restoreTabs"
        const val K_BOTTOM_BAR = "browse.bottomBar"
        const val K_CUSTOM_EXT = "openers.customExtensions"
        const val K_VIEW_STEP = "browse.viewStep"
        const val K_THEME = "ui.theme"
        const val K_ACCENT = "ui.accent"
        const val K_SPLIT = "browse.split"
        const val K_HOME_FOLDER = "home.folder"
        const val K_HOME_HINT = "home.hint.shown"
        const val K_INDEX = "index.enabled"
        const val K_TAB_SIZE = "browse.tabSize"
        const val K_HIDE_STORAGE = "home.hideStorage"
        const val K_HIDDEN_CARDS = "home.hiddenCards"
        const val K_HIDE_TERMUX = "home.hideTermux"
        // A new key rather than a reused one. The old value was MENU or BAR, which is not a
        // list of action ids, and a stored MENU read as a list normalises to the default -
        // correct, but only by accident, and it leaves a value that means nothing behind.
        const val K_SELECTION_BAR = "browse.selectionBar"
        const val K_NOTES_FRACTION = "update.notesFraction"
        const val K_UPD_UNTIL = "update.silencedUntil"
        const val K_E_SUB = "entry.subtitle"
        const val K_E_MEASURE = "entry.measure"
        const val K_E_DATECOL = "entry.dateColumn"
        const val K_E_STYLE = "entry.dateStyle"
        const val K_E_ORDER = "entry.dateOrder"
        const val K_E_SEP = "entry.dateSeparator"
        const val K_E_REL = "entry.relativeDates"

        const val K_UPD_VERSION = "update.silencedVersion"
        const val K_UPD_SKIPPED = "update.skippedVersion"
        const val K_UPD_OFF = "update.notificationsOff"
        const val K_UPD_CHECKED = "update.lastCheckedAt"
    }
}

enum class SortKey {
    NAME, SIZE, MODIFIED, TYPE;
    companion object { fun valueOfOr(s: String?, d: SortKey) = entries.firstOrNull { it.name == s } ?: d }
}

/**
 * The six view steps, with every dimension they drive.
 *
 * The whole view scales, not just the icon (mock round 3): name, metadata, chip and row
 * height move together, or step 6 is a huge icon beside 11px text.
 *
 * @param tile null for the list steps; a tile width in dp for the grid steps.
 */
enum class ViewStep(
    val label: String,
    val icon: Int,
    val nameSize: Int,
    val metaSize: Int,
    val chipSize: Int,
    val rowHeight: Int,
    val tile: Int? = null,
    val twoLine: Boolean = false,
) {
    COMPACT_LIST("Compact list", 14, 12, 10, 9, 28),
    LIST("List", 16, 13, 11, 10, 36),
    COMFORTABLE("Comfortable", 21, 14, 11, 11, 50, twoLine = true),
    SMALL_GRID("Small grid", 30, 12, 10, 10, 0, tile = 92),
    MEDIUM_GRID("Medium grid", 42, 13, 11, 11, 0, tile = 130),
    LARGE_GRID("Large grid", 56, 15, 12, 12, 0, tile = 174);

    val isGrid: Boolean get() = tile != null

    companion object {
        /** Steps are 1-based in the UI because the slider reads 1..6, not 0..5. */
        fun of(step: Int): ViewStep = entries[(step - 1).coerceIn(0, entries.lastIndex)]
    }
}

/**
 * How big a tab chip is.
 *
 * The complaint was that the tabs are too small to hit. That is a touch-target problem, not a
 * font problem, so every dimension of the chip moves together - a 14sp label inside 5dp of
 * padding is no easier to tap than an 11sp one.
 *
 * Three steps, not a slider: the useful range here is narrow (a tab strip that eats a third of
 * the screen is not a feature) and three named sizes are decidable at a glance.
 *
 * @param maxTitle how wide the title may run before it ellipsises. Larger tabs earn more room
 *   for the name as well as more room for the finger.
 */
enum class TabSize(
    val label: String,
    val fontSize: Float,
    val icon: Int,
    val maxTitle: Int,
    val padH: Int,
    val padV: Int,
    val close: Int,
    val plus: Int,
) {
    COMPACT("Compact", 10.5f, 12, 78, 6, 3, 13, 26),
    NORMAL("Normal", 11.5f, 13, 110, 8, 5, 15, 30),
    LARGE("Large", 13.5f, 16, 150, 11, 9, 19, 38),
    HUGE("Huge", 15.5f, 19, 190, 14, 13, 22, 46);

    companion object { fun valueOfOr(s: String?, d: TabSize) = entries.firstOrNull { it.name == s } ?: d }
}

enum class ThemeChoice {
    SYSTEM, LIGHT, DARK;
    companion object { fun valueOfOr(s: String?, d: ThemeChoice) = entries.firstOrNull { it.name == s } ?: d }
}

/** Named palettes rather than a colour picker: a file manager is read all day, and an
 *  arbitrary hue picker mostly produces unreadable lists. */
enum class AccentChoice {
    SLATE, WARM, MOSS, INK;
    companion object { fun valueOfOr(s: String?, d: AccentChoice) = entries.firstOrNull { it.name == s } ?: d }
}

data class SortSpec(
    val key: SortKey = SortKey.NAME,
    /**
     * Most relevant first: A to Z, newest, largest. See `SortOrder`.
     *
     * Defaults to the same value the stored preference does. They were allowed to disagree -
     * the stored default moved and this one did not - and the only thing that noticed was the
     * media queue, which builds a `SortSpec()` of its own and started playing tracks in
     * reverse.
     */
    val descending: Boolean = true,
    val foldersFirst: Boolean = true,
)

/** A stored enum name, or the default when it is missing or no longer exists. */
private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: def

/**
 * The hosting endpoint, remembered between launches.
 *
 * `http://<ip>:<port>/a/<code>` is what a desktop mounts, and a mapped network drive stores that
 * string - so all three parts have to outlive the process or the mapping on the PC stops
 * resolving and has to be made again. They were in-memory fields: settable, and gone on restart.
 *
 * The port is written even when it is the default, so a later change to the default cannot
 * silently move an endpoint somebody has already mapped.
 */
private const val K_DRAG = "drag.behaviour"
private const val DAV_SHARES = "dav.shares"
private const val DAV_CODE = "dav.code"
private const val DAV_PORT = "dav.port"
private const val DAV_ROOT = "dav.root"

class PrefsDavStore(private val prefs: Prefs) : dev.niccc2007.filet.webdav.DavSettingsStore {

    /**
     * The whole share list, encoded by `DavShares`.
     *
     * `code` and `root` below are the pre-list keys. They are still READ, because the server folds
     * them into the first share on upgrade, and still written, so that downgrading does not lose
     * the endpoint outright. They stop being the source of truth the moment a list exists.
     */
    override var shares: String?
        get() = prefs.getString(DAV_SHARES, null)
        set(value) = prefs.putString(DAV_SHARES, value)

    override var code: String?
        get() = prefs.getString(DAV_CODE, null)
        set(value) = prefs.putString(DAV_CODE, value)

    override var port: Int
        get() = prefs.getInt(DAV_PORT, dev.niccc2007.filet.webdav.WebDavServer.DEFAULT_PORT)
        set(value) = prefs.putInt(DAV_PORT, value)

    override var root: dev.niccc2007.filet.vfs.VPath?
        get() = prefs.getString(DAV_ROOT, null)
            ?.let { runCatching { dev.niccc2007.filet.vfs.VPath.parse(it) }.getOrNull() }
        set(value) = prefs.putString(DAV_ROOT, value?.toString())
}
