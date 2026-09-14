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
            descending = sp.getBoolean(K_SORT_DESC, false),
            foldersFirst = sp.getBoolean(K_FOLDERS_FIRST, true),
        )
    )
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    private val _showHidden = MutableStateFlow(sp.getBoolean(K_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

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

    fun setSort(s: SortSpec) {
        _sort.value = s
        sp.edit().putString(K_SORT, s.key.name).putBoolean(K_SORT_DESC, s.descending)
            .putBoolean(K_FOLDERS_FIRST, s.foldersFirst).apply()
    }

    fun setShowHidden(v: Boolean) { _showHidden.value = v; sp.edit().putBoolean(K_HIDDEN, v).apply() }
    fun setHideStorage(v: Boolean) { _hideStorage.value = v; sp.edit().putBoolean(K_HIDE_STORAGE, v).apply() }

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
        const val K_VIEW_STEP = "browse.viewStep"
        const val K_THEME = "ui.theme"
        const val K_ACCENT = "ui.accent"
        const val K_SPLIT = "browse.split"
        const val K_INDEX = "index.enabled"
        const val K_TAB_SIZE = "browse.tabSize"
        const val K_HIDE_STORAGE = "home.hideStorage"
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
    val descending: Boolean = false,
    val foldersFirst: Boolean = true,
)
