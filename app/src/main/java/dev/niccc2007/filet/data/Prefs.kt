package dev.niccc2007.filet.data

import android.content.Context
import android.content.SharedPreferences
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

    private val _indexEnabled = MutableStateFlow(sp.getBoolean(K_INDEX, true))
    val indexEnabled: StateFlow<Boolean> = _indexEnabled.asStateFlow()

    private val _tabSize = MutableStateFlow(TabSize.valueOfOr(sp.getString(K_TAB_SIZE, null), TabSize.NORMAL))
    val tabSize: StateFlow<TabSize> = _tabSize.asStateFlow()

    /**
     * How the actions for a selection are shown.
     *
     * Default MENU. The bar had to scroll to hold eleven actions, and an action off the edge of
     * a row nobody knows scrolls does not exist. BAR is kept because one tap beats two when you
     * already know where the button is.
     */
    private val _selectionStyle = MutableStateFlow(
        dev.niccc2007.filet.browser.SelectionStyle.valueOfOr(
            sp.getString(K_SELECTION_STYLE, null),
            dev.niccc2007.filet.browser.SelectionStyle.MENU,
        )
    )
    val selectionStyle: StateFlow<dev.niccc2007.filet.browser.SelectionStyle> = _selectionStyle.asStateFlow()

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

    fun setSelectionStyle(v: dev.niccc2007.filet.browser.SelectionStyle) {
        _selectionStyle.value = v
        sp.edit().putString(K_SELECTION_STYLE, v.name).apply()
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
        const val K_INDEX = "index.enabled"
        const val K_TAB_SIZE = "browse.tabSize"
        const val K_HIDE_STORAGE = "home.hideStorage"
        const val K_HIDDEN_CARDS = "home.hiddenCards"
        const val K_HIDE_TERMUX = "home.hideTermux"
        const val K_SELECTION_STYLE = "browse.selectionStyle"
        const val K_NOTES_FRACTION = "update.notesFraction"
        const val K_UPD_UNTIL = "update.silencedUntil"
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
