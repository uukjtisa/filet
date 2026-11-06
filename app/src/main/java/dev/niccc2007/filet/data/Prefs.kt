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
/**
 * The few preference keys read from OUTSIDE [Prefs].
 *
 * `Prefs` keeps its keys in a private companion, which is right - they are its business. These
 * two are not, because they are read before the object graph exists: `Application.onCreate`
 * decides whether to migrate the store there, and building `Prefs` to ask would construct the
 * whole graph on the main thread to look at one boolean.
 *
 * Declared once and referenced by the companion, so the two readers cannot come to disagree
 * about the spelling - which is the only way this could go wrong, and would go wrong silently
 * by migrating on every single start.
 */
object PrefKeys {
    /** The SharedPreferences file every one of these lives in. */
    const val FILE = "filet"
    const val STORE_MIGRATED = "store_migrated"

    /**
     * Which of [FiletStore.Location] the derived files go to.
     *
     * Read by `FiletStore` itself at every call site, for the reason given on
     * `FiletStore.current` - so it has to be reachable from outside `Prefs` too.
     */
    const val CACHE_LOCATION = "cache.location"
}

class Prefs(context: Context) : dev.niccc2007.filet.playback.GeneralSound {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("filet", Context.MODE_PRIVATE)

    /**
     * How to read each flow below again, registered as the flow is made.
     *
     * Every flow is built once, from the value stored when Filet started. A value written into the
     * store from anywhere but its setter - an import, a hand edit of `filet.json` - changed the
     * store and nothing on screen, and the import said "restart Filet" for that reason. [reload]
     * re-reads them all, so both apply where they stand.
     *
     * Declared before the first flow: properties initialise in order, and a flow made before the
     * list exists would register into nothing.
     */
    private val reloaders = ArrayList<() -> Unit>()

    private fun <T> stored(read: () -> T): MutableStateFlow<T> {
        val flow = MutableStateFlow(read())
        reloaders += { flow.value = read() }
        return flow
    }

    /** Re-read every setting from the store. See [reloaders]. */
    fun reload() {
        for (r in reloaders) runCatching { r() }
    }

    /**
     * Hear every write to the store, until the handle is closed. On the main thread.
     *
     * The listener is held here as well as by the store: `SharedPreferences` keeps its listeners
     * weakly, so one held only there is collected and stops hearing anything, with no error.
     */
    fun onChange(listener: (String?) -> Unit): AutoCloseable {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> listener(key) }
        held += l
        sp.registerOnSharedPreferenceChangeListener(l)
        return AutoCloseable {
            sp.unregisterOnSharedPreferenceChangeListener(l)
            held -= l
        }
    }

    private val held = java.util.concurrent.CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    // ── browsing ──
    private val _sort = stored {
        SortSpec(
            key = SortKey.valueOfOr(sp.getString(K_SORT, null), SortKey.NAME),
            descending = sp.getBoolean(K_SORT_DESC, dev.niccc2007.filet.browser.SortOrder.DEFAULT_DESCENDING),
            foldersFirst = sp.getBoolean(K_FOLDERS_FIRST, true),
            // Absent by default, which is the uniform A-to-Z order everything started with.
            folderKey = sp.getString(K_SORT_FOLDER, null)
                ?.let { stored -> SortKey.entries.firstOrNull { it.name == stored } },
            folderDescending = if (!sp.contains(K_SORT_FOLDER_DESC)) {
                null
            } else {
                sp.getBoolean(K_SORT_FOLDER_DESC, true)
            },
        )
    }
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    private val _showHidden = stored { sp.getBoolean(K_HIDDEN, false) }
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    /**
     * What a row in the file list shows, and how it writes a date.
     *
     * Read as one value rather than as seven flags, because the settings screen previews it and a
     * preview has to be given the whole thing to render. See [EntryDisplay].
     */
    private val _entry = stored {
        EntryDisplay(
            parts = storedParts(),
            sideFile = storedSide(K_E_SIDE_FILE, dev.niccc2007.filet.browser.SideColumn.FILE_DEFAULT, isDir = false),
            sideFolder = storedSide(K_E_SIDE_FOLDER, dev.niccc2007.filet.browser.SideColumn.FOLDER_DEFAULT, isDir = true),
            style = enumOr(sp.getString(K_E_STYLE, null), DateStyle.NUMERIC),
            order = enumOr(sp.getString(K_E_ORDER, null), DateOrder.YMD),
            separator = sp.getString(K_E_SEP, "-") ?: "-",
            relative = sp.getBoolean(K_E_REL, true),
        )
    }
    val entry: StateFlow<EntryDisplay> = _entry.asStateFlow()



    /**
     * The stored detail parts, or what the old boolean meant.
     *
     * The migration is the whole reason this is a function. `entry.subtitle` was a boolean, and
     * somebody who had turned it OFF must not get a line back just because the setting grew into
     * a list - a reset to defaults is indistinguishable from the feature being broken for the
     * one person who had deliberately configured it.
     *
     * So: the new key wins where it exists; otherwise a stored `false` becomes an empty list and
     * anything else becomes the default. Note `RowDetails.normalise` keeps null and empty apart
     * for exactly this reason, which is what lets "off" survive.
     */
    private fun storedParts(): List<dev.niccc2007.filet.browser.DetailPart> {
        val stored = sp.getString(K_E_PARTS, null)
        if (stored != null) return dev.niccc2007.filet.browser.RowDetails.normalise(stored)
        val had = sp.getBoolean(K_E_SUB, true)
        return if (had) {
            dev.niccc2007.filet.browser.RowDetails.DEFAULT
        } else {
            emptyList()
        }
    }

    /**
     * What sits in the column on the right, or what the two old booleans meant.
     *
     * The new key wins where it exists. Otherwise the old pair is read through
     * [dev.niccc2007.filet.browser.SideColumn.migrate], which holds the table and the argument for
     * it, and is tested.
     */
    private fun storedSide(
        key: String,
        fallback: dev.niccc2007.filet.browser.DetailPart?,
        isDir: Boolean,
    ): dev.niccc2007.filet.browser.DetailPart? {
        val stored = sp.getString(key, null)
        if (stored != null) return dev.niccc2007.filet.browser.SideColumn.normalise(stored, fallback, isDir)
        return dev.niccc2007.filet.browser.SideColumn.migrate(
            hadMeasure = sp.getBoolean(K_E_MEASURE, true),
            hadDateColumn = sp.getBoolean(K_E_DATECOL, false),
            fallback = fallback,
        )
    }

    fun setEntry(d: EntryDisplay) {
        _entry.value = d
        sp.edit()
            .putString(K_E_PARTS, dev.niccc2007.filet.browser.RowDetails.encode(d.parts))
            .putString(K_E_SIDE_FILE, dev.niccc2007.filet.browser.SideColumn.encode(d.sideFile))
            .putString(K_E_SIDE_FOLDER, dev.niccc2007.filet.browser.SideColumn.encode(d.sideFolder))
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
    private val _restoreTabs = stored { sp.getBoolean(K_RESTORE_TABS, true) }
    val restoreTabs: StateFlow<Boolean> = _restoreTabs.asStateFlow()

    /** The bottom bar, as stored ids. Always read through `BottomBarConfig.normalise`. */
    private val _bottomBar = stored { sp.getString(K_BOTTOM_BAR, null) }
    val bottomBar: StateFlow<String?> = _bottomBar.asStateFlow()

    /**
     * Extensions added by hand in the default-opener settings.
     *
     * Persisted as soon as one is typed. Adding an extension used to go straight into a
     * chooser, so an extension nothing claimed could not be added at all - the chooser refused
     * and there was nothing left behind. Adding and choosing are separate now.
     */
    private val _customExtensions =
        stored { sp.getStringSet(K_CUSTOM_EXT, emptySet())!!.toSet() }
    val customExtensions: StateFlow<Set<String>> = _customExtensions.asStateFlow()

    /**
     * Hide the storage cards on Home.
     *
     * On a phone with one volume they are a fifth of the first screen restating something you
     * already know. Off by default, because on a phone WITH an SD card they are the fastest
     * way to reach it.
     */
    private val _hideStorage = stored { sp.getBoolean(K_HIDE_STORAGE, false) }
    val hideStorage: StateFlow<Boolean> = _hideStorage.asStateFlow()

    /**
     * One control for the whole view, exactly as the mock settled it: six steps from a
     * compact list to a large grid. Two separate knobs (mode + density) let a user pick
     * combinations that do not exist, which is how the mock's slider replaced them.
     */
    private val _viewStep = stored { sp.getInt(K_VIEW_STEP, 2).coerceIn(1, 6) }
    val viewStep: StateFlow<Int> = _viewStep.asStateFlow()

    private val _theme = stored { ThemeChoice.valueOfOr(sp.getString(K_THEME, null), ThemeChoice.SYSTEM) }
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _accent = stored { AccentChoice.valueOfOr(sp.getString(K_ACCENT, null), AccentChoice.SLATE) }
    val accent: StateFlow<AccentChoice> = _accent.asStateFlow()

    private val _splitRatio = stored { sp.getFloat(K_SPLIT, 0.5f) }
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    /**
     * A folder Home opens instead of the overview, or null for the overview.
     *
     * Stored as a string rather than resolved here: the path outlives the folder, and deciding
     * what to do about that belongs to [dev.niccc2007.filet.browser.HomeTarget], which has
     * tests on the falling-back.
     */
    private val _homeFolder = stored { sp.getString(K_HOME_FOLDER, null) }
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
    private val _homeHintShown = stored { sp.getBoolean(K_HOME_HINT, false) }
    val homeHintShown: StateFlow<Boolean> = _homeHintShown.asStateFlow()

    fun markHomeHintShown() {
        sp.edit().putBoolean(K_HOME_HINT, true).apply()
        _homeHintShown.value = true
    }

    private val _indexEnabled = stored { sp.getBoolean(K_INDEX, true) }
    val indexEnabled: StateFlow<Boolean> = _indexEnabled.asStateFlow()

    /**
     * Whether the index keeps itself up to date, or waits to be told.
     *
     * On is Smart: a bounded pass when Filet opens and the index is stale, a pass over a folder
     * when it is walked into, and the on-charge sweep. Off is Manual: none of those, and the
     * only crawl that ever runs is the one started from Index now. Search keeps working either
     * way - the live walk answers whatever the index has not reached - so this trades how
     * instant a cold search is against every background cost, and it is the person's trade.
     *
     * Two states and not three. A third that wrote no index rows at all would also lose what
     * only the index can answer - `pkg:`, `inzip:`, `from:`, duplicates, the frecency that
     * orders results - and the loss would show up as search quietly getting worse with nothing
     * on screen to explain it. Turning the index off entirely is still the switch above this one.
     */
    /**
     * Whether Shutdown Filet asks before stopping.
     *
     * Turned off from the sheet's own tick. It only ever skips the sheet when nothing would be
     * cut off partway - see `BrowserViewModel.askStop` - because the sheet naming a copy in
     * flight is the one part of it that cannot be agreed to in advance.
     */
    private val _stopConfirm = stored { sp.getBoolean(K_STOP_CONFIRM, true) }
    val stopConfirm: StateFlow<Boolean> = _stopConfirm.asStateFlow()

    /**
     * Whether search results may come from inside hidden folders. SEARCH.md §14.5.
     *
     * Its own switch, in the search bar, separate from [showHidden]: whether to see dot-folders
     * while browsing is not the same question as whether a thumbnail cache belongs in an answer.
     * Off by default, because the first answer to a search on the phone this was built against
     * was a thumbnail inside `.hwthumbnails`.
     */
    private val _searchHidden = stored { sp.getBoolean(K_SEARCH_HIDDEN, false) }
    val searchHidden: StateFlow<Boolean> = _searchHidden.asStateFlow()

    private val _indexAuto = stored { sp.getBoolean(K_INDEX_AUTO, true) }
    val indexAuto: StateFlow<Boolean> = _indexAuto.asStateFlow()

    /**
     * Minutes the media listener keeps going after Filet is minimised - Off, 2, 5 or 10.
     * SEARCH.md §14.6. Read at the moment of minimising, so a change applies to the next one.
     */
    private val _listenMinutes = stored {
        dev.niccc2007.filet.index.ListenWindow.normalise(sp.getInt(K_LISTEN_MINUTES, dev.niccc2007.filet.index.ListenWindow.DEFAULT))
    }
    val listenMinutes: StateFlow<Int> = _listenMinutes.asStateFlow()

    /**
     * Write a detailed log of each run.
     *
     * On by default, and that is a decision rather than an oversight. The two faults this was
     * added for - a slow cold start, and the drive information on Home going blank - are both
     * intermittent, so a log that has to be switched on BEFORE the thing happens is a log that is
     * off every time it would have mattered. Sessions are capped by count, so the cost is bounded.
     *
     * Off still records warnings, failures and the shape of the session; it drops the per-step
     * detail. There is no level below that, because a run with no log at all is the state this
     * setting exists to end.
     */
    // The key is owned by the logger, not by this class: it is read at process start, by the
    // Application, before a Prefs instance exists. Two spellings of one key is how a setting
    // quietly stops being the setting that is obeyed.
    private val _verboseLogs =
        stored { sp.getBoolean(dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE, true) }
    
    val verboseLogs: StateFlow<Boolean> = _verboseLogs.asStateFlow()

    private val _tabSize = stored { TabSize.valueOfOr(sp.getString(K_TAB_SIZE, null), TabSize.NORMAL) }
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
    private val _selectionBar = stored { sp.getString(K_SELECTION_BAR, null) }
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
    private val _hiddenCards = stored { sp.getStringSet(K_HIDDEN_CARDS, emptySet())!!.toSet() }
    val hiddenCards: StateFlow<Set<String>> = _hiddenCards.asStateFlow()


    // ── updates ──

    /**
     * How much of the screen the release notes get, as a fraction.
     *
     * Stored rather than reset each time because it is a reading preference, and somebody who
     * pulled the pane open to read a long changelog wants it open for the next one too.
     * 0 means never set; the clamping lives in `usableNotesFraction`.
     */
    private val _notesFraction = stored { sp.getFloat(K_NOTES_FRACTION, 0f) }
    val notesFraction: StateFlow<Float> = _notesFraction.asStateFlow()

    /** Everything the update nag has been told. See `UpdatePrompt.kt`. */
    private val _updateSilencedUntil = stored { sp.getLong(K_UPD_UNTIL, 0L) }
    val updateSilencedUntil: StateFlow<Long> = _updateSilencedUntil.asStateFlow()

    private val _updateSilencedVersion = stored { sp.getString(K_UPD_VERSION, "").orEmpty() }
    val updateSilencedVersion: StateFlow<String> = _updateSilencedVersion.asStateFlow()

    private val _updateSkippedVersion = stored { sp.getString(K_UPD_SKIPPED, "").orEmpty() }
    val updateSkippedVersion: StateFlow<String> = _updateSkippedVersion.asStateFlow()

    private val _updateNotificationsOff = stored { sp.getBoolean(K_UPD_OFF, false) }
    val updateNotificationsOff: StateFlow<Boolean> = _updateNotificationsOff.asStateFlow()

    private val _updateLastCheckedAt = stored { sp.getLong(K_UPD_CHECKED, 0L) }
    val updateLastCheckedAt: StateFlow<Long> = _updateLastCheckedAt.asStateFlow()



    /**
     * Uninterrupted playback: Filet takes no audio focus and yields to none.
     *
     * Off by default, and it has to be, because it is deliberately bad behaviour in both
     * directions - starting a track will not pause whatever else is playing, and nothing else
     * will pause Filet. That is exactly what somebody listening over another app's audio
     * wants and exactly what everybody else would report as a bug.
     *
     * One per player. A single switch used to serve both, and only the music player read it, so
     * the video player's own toggle changed the music's behaviour and nothing of its own. Music
     * kept the old key, so its value cannot change under anybody; the video's is made from it
     * once. See [seedVideoUninterrupted].
     */
    private val _musicUninterrupted = stored { sp.getBoolean(K_MUSIC_UNINTERRUPTED, false) }
    val musicUninterrupted: StateFlow<Boolean> = _musicUninterrupted.asStateFlow()

    fun setMusicUninterrupted(on: Boolean) {
        _musicUninterrupted.value = on
        sp.edit().putBoolean(K_MUSIC_UNINTERRUPTED, on).apply()
    }

    private val _videoUninterrupted = stored {
        seedVideoUninterrupted(sp)
        sp.getBoolean(K_VIDEO_UNINTERRUPTED, false)
    }
    val videoUninterrupted: StateFlow<Boolean> = _videoUninterrupted.asStateFlow()

    fun setVideoUninterrupted(on: Boolean) {
        _videoUninterrupted.value = on
        sp.edit().putBoolean(K_VIDEO_UNINTERRUPTED, on).apply()
    }

    /**
     * Whether a video reached by a run's arrows or its swipe starts playing at once, rather than
     * waiting under its preview for a tap the way an opened one does. Off by default: moving
     * through a gallery stays quiet unless asked. See `VideoStart`.
     */
    private val _videoNextPlays = stored { sp.getBoolean(K_VIDEO_NEXT_PLAYS, false) }
    val videoNextPlays: StateFlow<Boolean> = _videoNextPlays.asStateFlow()

    fun setVideoNextPlays(on: Boolean) {
        _videoNextPlays.value = on
        sp.edit().putBoolean(K_VIDEO_NEXT_PLAYS, on).apply()
    }

    /**
     * How far one double tap on either side of a video moves it, in seconds, and the two buttons
     * beside Play with it: one length for the one gesture, whichever way it is asked for. 2, 5
     * or 10; ten is what other players use, so it is the default, and a stored number that is
     * not one of the three reads as ten.
     */
    private val _videoSkipSeconds = stored { videoSkipOf(sp.getInt(K_VIDEO_SKIP, VIDEO_SKIP_DEFAULT)) }
    val videoSkipSeconds: StateFlow<Int> = _videoSkipSeconds.asStateFlow()

    /**
     * Whether a viewer opened from a list of photos and videos swipes through only the kind it
     * stands on. Off by default: both together, in the list's order, as before. Asked only of a
     * run that has both; music is never in a run either way. See `ViewerRun.onlyKind`.
     */
    private val _viewerOnlyKind = stored { sp.getBoolean(K_VIEWER_ONLY_KIND, false) }
    val viewerOnlyKind: StateFlow<Boolean> = _viewerOnlyKind.asStateFlow()

    fun setViewerOnlyKind(on: Boolean) {
        _viewerOnlyKind.value = on
        sp.edit().putBoolean(K_VIEWER_ONLY_KIND, on).apply()
    }

    /**
     * Whether a playing video goes on in a picture-in-picture window when Filet is left, rather
     * than stopping. Off by default: stopping is what leaving has always done. See `VideoPip`.
     */
    private val _videoPipOnHome = stored { sp.getBoolean(K_VIDEO_PIP_HOME, false) }
    val videoPipOnHome: StateFlow<Boolean> = _videoPipOnHome.asStateFlow()

    fun setVideoPipOnHome(on: Boolean) {
        _videoPipOnHome.value = on
        sp.edit().putBoolean(K_VIDEO_PIP_HOME, on).apply()
    }

    fun setVideoSkipSeconds(seconds: Int) {
        val s = videoSkipOf(seconds)
        _videoSkipSeconds.value = s
        sp.edit().putInt(K_VIDEO_SKIP, s).apply()
    }

    /**
     * Whether the one-time move into the visible store has run. See `FiletStore.migrate`.
     *
     * An optimisation and not the correctness argument: the move is idempotent, so a flag that
     * is lost - or a downgrade that never set it - costs a directory listing on the next start
     * and nothing else.
     */
    private val _cacheLocation = stored {
        FiletStore.Location.of(sp.getString(PrefKeys.CACHE_LOCATION, null))
    }

    /**
     * Where the derived files go. Defaults to the visible folder - see [FiletStore].
     *
     * Kept as a flow for the settings screen, but `FiletStore` reads the stored string directly
     * rather than this: it is called from decoders and workers that have a Context and no
     * business holding a Prefs.
     */
    val cacheLocation: StateFlow<FiletStore.Location> = _cacheLocation.asStateFlow()

    fun setCacheLocation(where: FiletStore.Location) {
        _cacheLocation.value = where
        sp.edit().putString(PrefKeys.CACHE_LOCATION, where.name).apply()
    }

    /**
     * Whether the Details sheet shows what Filet remembers about a file.
     *
     * OFF until it has been driven on a device, per R1. The feature writes into a store that
     * nothing else can recreate - see `FiletStore.Area.MEMORY` - so the flag is worth more here
     * than for an ordinary screen: a reading bug noticed late is a cosmetic fix, and a WRITING
     * bug noticed late is a corrupted history with no second copy to compare against.
     */
    private val _memoryDetails = stored { sp.getBoolean(K_MEMORY_DETAILS, false) }
    val memoryDetails: StateFlow<Boolean> = _memoryDetails.asStateFlow()

    fun setMemoryDetails(on: Boolean) {
        _memoryDetails.value = on
        sp.edit().putBoolean(K_MEMORY_DETAILS, on).apply()
    }

    /**
     * Which list the New files tab and the Home card show: arrivals by date added, or the whole
     * phone by date modified. One setting for both, because the card is the tab's first rows.
     */
    private val _newFilesOrder = stored {
        dev.niccc2007.filet.home.HistorySort.of(sp.getString(K_NEW_FILES_ORDER, null))
    }
    val newFilesOrder: StateFlow<dev.niccc2007.filet.home.HistorySort> = _newFilesOrder.asStateFlow()

    fun setNewFilesOrder(by: dev.niccc2007.filet.home.HistorySort) {
        _newFilesOrder.value = by
        sp.edit().putString(K_NEW_FILES_ORDER, by.key).apply()
    }

    /**
     * List or gallery, per category - see `CategoryLayout`. Stored as one string so the whole
     * choice moves with a settings file as one key.
     */
    private val _categoryLayouts = stored {
        dev.niccc2007.filet.home.CategoryLayout.parse(sp.getString(K_CATEGORY_LAYOUT, null))
    }
    val categoryLayouts: StateFlow<Map<String, dev.niccc2007.filet.home.CategoryLayout>> =
        _categoryLayouts.asStateFlow()

    fun setCategoryLayout(categoryId: String, layout: dev.niccc2007.filet.home.CategoryLayout) {
        val next = _categoryLayouts.value + (categoryId to layout)
        _categoryLayouts.value = next
        sp.edit().putString(K_CATEGORY_LAYOUT, dev.niccc2007.filet.home.CategoryLayout.encode(next)).apply()
    }

    var storeMigrated: Boolean
        get() = sp.getBoolean(K_STORE_MIGRATED, false)
        set(value) { sp.edit().putBoolean(K_STORE_MIGRATED, value).apply() }

    /**
     * Whether the disc wears a glow of its own.
     *
     * ## What this preference used to be, and why it is not that
     *
     * It was called "Living background" and it switched the drifting colour behind the player
     * off entirely - on every surface at once, strip and notification included. Two things were
     * wrong with that and they are both corrections of the same mistake.
     *
     * The coloured background is not an effect, it is what the player looks like. Offering to
     * turn it off offered to turn the player into a grey rectangle, and nobody wants that -
     * a decision the strength slider already serves properly, since "how much" is the real
     * question and "whether" never was. So the background has no switch any more; it is on, and
     * `glowStrength` says how far.
     *
     * And while it was one switch over both, it also governed the glow ringing the HALO face's
     * disc - which is the thing that actually gets asked about, and the thing the name sounded
     * like it meant. One switch over two unrelated lights meant asking for one and losing the
     * other.
     *
     * So this is the disc's glow and nothing else: the mist thrown outward off the record, the
     * same light the minimised strip spills, plus the rim on its border. Offered only on a face
     * that HAS a disc - see `PlayerFace.hasDiscGlow` - because a switch over something the
     * selected look does not draw reads as the feature being broken.
     */
    private val _discGlow = stored { sp.getBoolean(K_DISC_GLOW, true) }
    val discGlow: StateFlow<Boolean> = _discGlow.asStateFlow()

    fun setDiscGlow(on: Boolean) {
        _discGlow.value = on
        sp.edit().putBoolean(K_DISC_GLOW, on).apply()
    }

    /**
     * How vibrant the player's coloured background is, 0 to 1.
     *
     * ## Why this is not `glowStrength`
     *
     * One number was doing two jobs, which is how "glow strength" came to be offered on looks
     * that have nothing it visibly changes. There are two separate lights in this player and
     * they belong to different things:
     *
     * - **The coloured background** - the wash the whole player sits on, which every look but
     *   Classic has. That is this.
     * - **The mist around the disc** - the light thrown off the record, which only Halo draws.
     *   That is `glowStrength`, and it is now gated on `PlayerFace.hasDiscGlow`.
     *
     * Asking for a more vivid background and being given a slider that brightened a halo on a
     * face with no record in it is the confusion this splits.
     *
     * Stored as a position rather than a multiplier so the slider is 0 to 1 and the mapping
     * stays in one place - see `vibranceScale`, where the default position is the one that
     * reproduces what the player looked like before this existed.
     */
    private val _vibrancy = stored {
        sp.getFloat(K_VIBRANCY, dev.niccc2007.filet.playback.VIBRANCE_DEFAULT)
    }
    val vibrancy: StateFlow<Float> = _vibrancy.asStateFlow()

    fun setVibrancy(position: Float) {
        val v = position.coerceIn(0f, 1f)
        _vibrancy.value = v
        sp.edit().putFloat(K_VIBRANCY, v).apply()
    }

    /**
     * How strong the background glow is painted. See `GlowLevel`.
     *
     * The whole of the answer now that there is no on/off over it: one strength cannot suit
     * every cover - the lights are the artwork's own colour, and a deep one lands far heavier
     * than a dusty one at the same alpha.
     *
     * Cycled from the minimised strip as well as set in Settings, because the strip is where
     * it is noticed and a control six inches from the thing it changes beats three taps.
     */
    private val _glowLevel = stored { sp.getString(K_GLOW_LEVEL, null) }
    val glowLevel: StateFlow<String?> = _glowLevel.asStateFlow()

    /**
     * How strong the living glow is, continuously, 0 to 1.
     *
     * The authority. [glowLevel] remains as the three stops the strip's pill cycles
     * through, and picking one writes this - so the pill and the slider are two views of one
     * number. See `glowScale`.
     */
    private val _glowStrength = stored {
        sp.getFloat(
            K_GLOW_STRENGTH,
            dev.niccc2007.filet.playback.GlowLevel.of(sp.getString(K_GLOW_LEVEL, null)).position,
        )
    }
    val glowStrength: StateFlow<Float> = _glowStrength.asStateFlow()

    fun setGlowStrength(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _glowStrength.value = c
        sp.edit().putFloat(K_GLOW_STRENGTH, c).apply()
    }

    fun setGlowLevel(v: String) {
        setGlowStrength(dev.niccc2007.filet.playback.GlowLevel.of(v).position)
        _glowLevel.value = v
        sp.edit().putString(K_GLOW_LEVEL, v).apply()
    }

    /**
     * Whether the strip's glow is allowed past the strip, up into the file list.
     *
     * It began as a fault - the lights are drawn behind the strip and nothing was bounding
     * them - and the look was liked enough to keep. Off by default all the same: it tints the
     * names of files somebody is reading, which is a cost nobody else asked to pay. It gets
     * fainter and stronger with the rest of the glow rather than having a strength of its own.
     */
    private val _glowSpill = stored { sp.getBoolean(K_GLOW_SPILL, false) }
    val glowSpill: StateFlow<Boolean> = _glowSpill.asStateFlow()

    fun setGlowSpill(on: Boolean) {
        _glowSpill.value = on
        sp.edit().putBoolean(K_GLOW_SPILL, on).apply()
    }

    /**
     * Swipe sideways to change tab in the pane under your finger. See `PaneSwipe`.
     *
     * On by default, and it only does anything in dual pane - which is both what it is for
     * and what keeps it away from the file list's drag.
     */
    private val _paneSwipe = stored { sp.getBoolean(K_PANE_SWIPE, true) }
    val paneSwipe: StateFlow<Boolean> = _paneSwipe.asStateFlow()

    fun setPaneSwipe(on: Boolean) {
        _paneSwipe.value = on
        sp.edit().putBoolean(K_PANE_SWIPE, on).apply()
    }

    /** How hard that swipe has to be. See `PaneSwipe.Sensitivity`. */
    private val _paneSwipeAt = stored { sp.getString(K_PANE_SWIPE_AT, null) }
    val paneSwipeAt: StateFlow<String?> = _paneSwipeAt.asStateFlow()

    fun setPaneSwipeAt(v: String) {
        _paneSwipeAt.value = v
        sp.edit().putString(K_PANE_SWIPE_AT, v).apply()
    }

    /**
     * Which groups of the navigation drawer are open.
     *
     * Persisted, because the group somebody leaves open is the one holding what they use,
     * and that is a thing only they know. Storage and Recent start open - between them they
     * answer "where am I going" for most visits - and the long list of places starts folded.
     */
    private val _drawerStorage = stored { sp.getBoolean(K_DRAWER_STORAGE, true) }
    val drawerStorage: StateFlow<Boolean> = _drawerStorage.asStateFlow()

    fun setDrawerStorage(on: Boolean) {
        _drawerStorage.value = on
        sp.edit().putBoolean(K_DRAWER_STORAGE, on).apply()
    }

    private val _drawerRecent = stored { sp.getBoolean(K_DRAWER_RECENT, true) }
    val drawerRecent: StateFlow<Boolean> = _drawerRecent.asStateFlow()

    fun setDrawerRecent(on: Boolean) {
        _drawerRecent.value = on
        sp.edit().putBoolean(K_DRAWER_RECENT, on).apply()
    }

    private val _drawerPlaces = stored { sp.getBoolean(K_DRAWER_PLACES, false) }
    val drawerPlaces: StateFlow<Boolean> = _drawerPlaces.asStateFlow()

    fun setDrawerPlaces(on: Boolean) {
        _drawerPlaces.value = on
        sp.edit().putBoolean(K_DRAWER_PLACES, on).apply()
    }

    /**
     * Whether a settings hint has ever been opened. Until it has, Settings carries one line
     * saying that holding a setting shows what it does; see `HoldHint`.
     */
    private val _settingsHintsSeen = stored { sp.getBoolean(K_SETTINGS_HINTS_SEEN, false) }
    val settingsHintsSeen: StateFlow<Boolean> = _settingsHintsSeen.asStateFlow()

    fun setSettingsHintsSeen(on: Boolean) {
        if (_settingsHintsSeen.value == on) return
        _settingsHintsSeen.value = on
        sp.edit().putBoolean(K_SETTINGS_HINTS_SEEN, on).apply()
    }

    /**
     * Home's cards and the side bar's folds: the order somebody arranged and the ones they took
     * off. Stored raw - which cards exist is the surface's business, and [SectionLayout.of]
     * reconciles the two each time it is read.
     */
    private val _homeOrder = stored { SectionLayout.decode(sp.getString(K_HOME_ORDER, null)) }
    val homeOrder: StateFlow<List<String>> = _homeOrder.asStateFlow()
    private val _homeHidden = stored { sp.getStringSet(K_HOME_HIDDEN, emptySet())!!.toSet() }
    val homeHidden: StateFlow<Set<String>> = _homeHidden.asStateFlow()
    private val _drawerOrder = stored { SectionLayout.decode(sp.getString(K_DRAWER_ORDER, null)) }
    val drawerOrder: StateFlow<List<String>> = _drawerOrder.asStateFlow()
    private val _drawerHidden = stored { sp.getStringSet(K_DRAWER_HIDDEN, emptySet())!!.toSet() }
    val drawerHidden: StateFlow<Set<String>> = _drawerHidden.asStateFlow()

    fun setHomeLayout(l: SectionLayout) {
        _homeOrder.value = l.order
        _homeHidden.value = l.hidden
        sp.edit().putString(K_HOME_ORDER, SectionLayout.encode(l.order)).putStringSet(K_HOME_HIDDEN, l.hidden).apply()
    }

    fun setDrawerLayout(l: SectionLayout) {
        _drawerOrder.value = l.order
        _drawerHidden.value = l.hidden
        sp.edit().putString(K_DRAWER_ORDER, SectionLayout.encode(l.order)).putStringSet(K_DRAWER_HIDDEN, l.hidden).apply()
    }

    /** When Home mentions Filet's cache - see [FiletStore.shouldRemind]. 0 is never. */
    private val _cacheRemindPast = stored { sp.getLong(K_CACHE_REMIND, FiletStore.REMIND_DEFAULT) }
    val cacheRemindPast: StateFlow<Long> = _cacheRemindPast.asStateFlow()

    fun setCacheRemindPast(bytes: Long) {
        _cacheRemindPast.value = bytes
        sp.edit().putLong(K_CACHE_REMIND, bytes).apply()
    }

    /** The cache's size when its reminder was last dismissed, or 0. */
    private val _cacheRemindDismissed = stored { sp.getLong(K_CACHE_DISMISSED, 0L) }
    val cacheRemindDismissed: StateFlow<Long> = _cacheRemindDismissed.asStateFlow()

    fun setCacheRemindDismissed(bytes: Long) {
        if (_cacheRemindDismissed.value == bytes) return
        _cacheRemindDismissed.value = bytes
        sp.edit().putLong(K_CACHE_DISMISSED, bytes).apply()
    }

    /** Names given to drives, by drive id. See [DriveNames]; empty for a drive never renamed. */
    private val _driveNames = stored { DriveNames.parse(sp.getString(K_DRIVE_NAMES, null)) }
    val driveNames: StateFlow<Map<String, String>> = _driveNames.asStateFlow()

    /** Rename the drive [id]. A blank [name] gives it back its system name. */
    fun setDriveName(id: String, name: String) {
        val next = DriveNames.renamed(_driveNames.value, id, name)
        if (next == _driveNames.value) return
        _driveNames.value = next
        sp.edit().putString(K_DRIVE_NAMES, DriveNames.encode(next)).apply()
    }

    /** Quick access in the side bar - see `SideFolds`. Open by default: it is why the bar exists. */
    private val _drawerQuick = stored { sp.getBoolean(K_DRAWER_QUICK, true) }
    val drawerQuick: StateFlow<Boolean> = _drawerQuick.asStateFlow()

    fun setDrawerQuick(on: Boolean) {
        _drawerQuick.value = on
        sp.edit().putBoolean(K_DRAWER_QUICK, on).apply()
    }

    /** The side bar's Tools fold. Folded by default, like the places it was split from. */
    private val _drawerTools = stored { sp.getBoolean(K_DRAWER_TOOLS, false) }
    val drawerTools: StateFlow<Boolean> = _drawerTools.asStateFlow()

    fun setDrawerTools(on: Boolean) {
        _drawerTools.value = on
        sp.edit().putBoolean(K_DRAWER_TOOLS, on).apply()
    }

    /** Which list the side bar's Recent fold shows: false is Opened, true is New files. */
    private val _drawerRecentNew = stored { sp.getBoolean(K_DRAWER_RECENT_NEW, false) }
    val drawerRecentNew: StateFlow<Boolean> = _drawerRecentNew.asStateFlow()

    fun setDrawerRecentNew(on: Boolean) {
        _drawerRecentNew.value = on
        sp.edit().putBoolean(K_DRAWER_RECENT_NEW, on).apply()
    }

    /**
     * Which sections of the Home overview are folded shut.
     *
     * A set of ids rather than a flag each, so a new section does not need a new preference,
     * a new key and a new accessor to be foldable - it needs an id.
     *
     * Everything is open unless it is in here, or named in `HomeFolds.SHUT` for a phone that
     * has never folded anything.
     */
    private val _homeFolds = stored {
        sp.getString(K_HOME_FOLDS, null)?.split('|')?.filter { it.isNotBlank() }?.toSet()
            ?: dev.niccc2007.filet.home.HomeFolds.SHUT
    }
    val homeFolds: StateFlow<Set<String>> = _homeFolds.asStateFlow()

    fun toggleHomeFold(id: String) {
        val next = _homeFolds.value.let { if (id in it) it - id else it + id }
        _homeFolds.value = next
        // Joined rather than a StringSet: a set preference has no order and this one is
        // read back as a whole, so a delimiter nobody can type into a section id is simpler
        // and survives an export as one string.
        sp.edit().putString(K_HOME_FOLDS, next.joinToString("|")).apply()
    }

    /**
     * Reach mode: the list and the pane's own chrome sit low enough for one thumb.
     *
     * Off by default, because it trades the top of the viewport for reachability and that is
     * only a good trade for somebody who wants it.
     *
     * Deliberately NOT a reversal of the list. Reversing puts a tree's children above their
     * parents and runs the guide lines upside down, and it inverts what the sort order means
     * while leaving the sort menu saying the opposite. A gap above the first row keeps order,
     * sort and the tree's geometry exactly as they are and still puts the rows under a thumb.
     */
    /**
     * How much work the player's animation is allowed to do: `auto`, `full` or `light`.
     *
     * `auto` is the answer and the default. The override exists because a classifier reading
     * RAM and core count will occasionally be wrong about a specific phone, and somebody who
     * can see their own phone stuttering should not have to wait for the heuristic to be
     * improved - not because turning the detail down is the fix for it being too expensive.
     * `Tiers` holds the resolved value; see `DeviceTier.kt`.
     */
    private val _deviceTier = stored { sp.getString(K_DEVICE_TIER, "auto") ?: "auto" }
    val deviceTier: StateFlow<String> = _deviceTier.asStateFlow()

    fun setDeviceTier(id: String) {
        val v = if (id in PLAYER_TIERS) id else "auto"
        _deviceTier.value = v
        sp.edit().putString(K_DEVICE_TIER, v).apply()
        dev.niccc2007.filet.playback.Tiers.override.value = v.takeIf { it != "auto" }
    }

    /**
     * How hard the beat detector listens: which beats fire at all.
     *
     * Restored after being collapsed away, and the reason is worth keeping. This scales the
     * onset strength BEFORE the follower, so it decides WHICH beats register - the follower
     * normalises against its own floor and ceiling, so a straight gain mostly cancels out
     * except at the clamp, where a higher value pushes medium hits past full and a lower one
     * leaves only the hardest. `pulseDepth` scales AFTER the follower and decides how FAR the
     * light moves on whatever did fire. Turning depth up makes hard hits bigger and leaves soft
     * ones dark, so it cannot stand in for this - which is exactly what the collapse to one
     * slider got wrong.
     *
     * Same key as before, so anybody who had tuned it gets their value back rather than a reset.
     */
    private val _pulseSense = stored {
        // Clamped on the way IN as well, because the broken clamp above could have stored a
        // value below the floor. A stored number is not trustworthy just because this code
        // writes it correctly now.
        sp.getFloat(K_PULSE_SENSE, dev.niccc2007.filet.playback.PULSE_SENSE).coerceIn(
            dev.niccc2007.filet.playback.PULSE_SENSE_MIN,
            dev.niccc2007.filet.playback.PULSE_SENSE_MAX,
        )
    }
    val pulseSense: StateFlow<Float> = _pulseSense.asStateFlow()

    fun setPulseSense(v: Float) {
        // Bug identified here: this clamped to 0..1 while the gain range is
        // PULSE_SENSE_MIN..PULSE_SENSE_MAX, which is 0.4..3. `senseFor` reaches a gain of 1 at
        // about 45% of the slider, so every position past that clamped back to 1 and the thumb
        // refused to travel - it read as a slider that only worked for half its length. A
        // control's clamp has to be the control's own range, never the unit interval because
        // sliders happen to be 0..1.
        val c = v.coerceIn(
            dev.niccc2007.filet.playback.PULSE_SENSE_MIN,
            dev.niccc2007.filet.playback.PULSE_SENSE_MAX,
        )
        _pulseSense.value = c
        sp.edit().putFloat(K_PULSE_SENSE, c).apply()
    }

    /**
     * What a pane does when split view makes it narrower than its layout expects.
     *
     * Defaults to shrinking, because the alternative is what shipped: rows clipped, the path bar
     * truncated and toolbar buttons dropped. See `SplitFit` for the trade, and for why a
     * dedicated narrow design is the better answer eventually rather than now.
     */
    /**
     * Which folders All Music scans, and the lengths that qualify.
     *
     * Three preferences for one list, kept together because they are read together and are
     * meaningless apart. The reasoning for each - and why "every audio file on the device" was
     * the wrong list - is in `AllMusicFilter`.
     *
     * Folders are stored as written: a bare name is relative to the primary volume, an absolute
     * path is used as it is, so a folder on an SD card can be added without a second setting.
     */
    private val _allMusicFolders = stored {
        sp.getStringSet(K_ALLMUSIC_DIRS, null)?.toList()
            ?: dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_FOLDERS
    }
    val allMusicFolders: StateFlow<List<String>> = _allMusicFolders.asStateFlow()

    fun setAllMusicFolders(v: List<String>) {
        // Trailing slashes only, so an absolute path keeps its leading one - that is the
        // whole difference between a folder on an SD card and the same name on internal
        // storage. See `AllMusicFilter.prefixes`.
        val clean = v.map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() && it != "/" }
            .distinct()
        _allMusicFolders.value = clean
        sp.edit().putStringSet(K_ALLMUSIC_DIRS, clean.toSet()).apply()
    }

    fun addAllMusicFolder(path: String) = setAllMusicFolders(_allMusicFolders.value + path)

    fun removeAllMusicFolder(path: String) = setAllMusicFolders(_allMusicFolders.value - path)

    private val _allMusicMinMs = stored {
        sp.getLong(K_ALLMUSIC_MIN, dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_MIN_MS)
    }
    val allMusicMinMs: StateFlow<Long> = _allMusicMinMs.asStateFlow()

    fun setAllMusicMinMs(v: Long) {
        val c = v.coerceIn(
            dev.niccc2007.filet.playback.AllMusicFilter.FLOOR_MS,
            dev.niccc2007.filet.playback.AllMusicFilter.CEILING_MS,
        )
        _allMusicMinMs.value = c
        sp.edit().putLong(K_ALLMUSIC_MIN, c).apply()
    }

    private val _allMusicMaxMs = stored {
        sp.getLong(K_ALLMUSIC_MAX, dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_MAX_MS)
    }
    val allMusicMaxMs: StateFlow<Long> = _allMusicMaxMs.asStateFlow()

    fun setAllMusicMaxMs(v: Long) {
        val c = v.coerceIn(
            dev.niccc2007.filet.playback.AllMusicFilter.FLOOR_MS,
            dev.niccc2007.filet.playback.AllMusicFilter.CEILING_MS,
        )
        _allMusicMaxMs.value = c
        sp.edit().putLong(K_ALLMUSIC_MAX, c).apply()
    }

    /** Back to the folders and lengths most phones want. */
    fun resetAllMusicFilter() {
        setAllMusicFolders(dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_FOLDERS)
        setAllMusicMinMs(dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_MIN_MS)
        setAllMusicMaxMs(dev.niccc2007.filet.playback.AllMusicFilter.DEFAULT_MAX_MS)
    }

    /**
     * Folders whose files never appear in New files.
     *
     * The replacement for tracked folders, and the reason it is needed. Tracked folders were
     * useful BECAUSE they were narrow; New files is now the whole device, so the noisy folders
     * are the problem instead of the quiet ones - a build directory, a messaging app's media
     * store, a game's cache. Blocking is the same idea inverted, and it is the cheaper one to
     * live with: nothing has to be added for the feed to be useful, only removed when something
     * floods it.
     *
     * Absolute paths. Blocking a folder blocks everything under it.
     */
    private val _blockedFolders =
        stored { sp.getStringSet(K_BLOCKED_FOLDERS, emptySet())!!.toSet() }
    val blockedFolders: StateFlow<Set<String>> = _blockedFolders.asStateFlow()

    fun blockFolder(path: String) = setBlockedFolders(_blockedFolders.value + path)

    fun unblockFolder(path: String) = setBlockedFolders(_blockedFolders.value - path)

    private fun setBlockedFolders(v: Set<String>) {
        _blockedFolders.value = v
        sp.edit().putStringSet(K_BLOCKED_FOLDERS, v).apply()
    }

    /**
     * Name prefixes New files leaves out, with everything under a folder that matches one.
     *
     * The companion to [blockedFolders] and the more useful half of the pair: the things that
     * flood this list are named by machines, and machines name in prefixes - `.thumbnails`,
     * `.trashed-1699`, `log_2026-10-26`. Blocking those one folder at a time is a losing game,
     * because the next one has a different number on the end.
     *
     * Starts as `FeedExclusions.DEFAULT_PREFIXES` until anything is stored, which says why
     * those and only those. Absent and empty are different answers: a list cleared by hand is
     * stored empty and stays empty, and only a key never written gets the default.
     */
    private val _feedPrefixes =
        stored {
            sp.getStringSet(K_FEED_PREFIXES, null)?.toSet()
                ?: dev.niccc2007.filet.home.FeedExclusions.DEFAULT_PREFIXES
        }
    val feedPrefixes: StateFlow<Set<String>> = _feedPrefixes.asStateFlow()

    fun setFeedPrefixes(v: Set<String>) {
        _feedPrefixes.value = v
        sp.edit().putStringSet(K_FEED_PREFIXES, v).apply()
    }

    private val _splitFit =
        stored { sp.getString(K_SPLIT_FIT, null) ?: "shrink" }
    val splitFit: StateFlow<String> = _splitFit.asStateFlow()

    fun setSplitFit(id: String) {
        _splitFit.value = id
        sp.edit().putString(K_SPLIT_FIT, id).apply()
    }

    private val _reachMode = stored { sp.getBoolean(K_REACH_MODE, false) }
    val reachMode: StateFlow<Boolean> = _reachMode.asStateFlow()

    fun setReachMode(on: Boolean) {
        _reachMode.value = on
        sp.edit().putBoolean(K_REACH_MODE, on).apply()
    }

    /**
     * Folder listings open in place instead of replacing the screen. See `TreeRows`.
     *
     * OFF by default, on request and on merit: a tree is a way of working that suits people
     * who already know the shape of what they are looking at, and it is the wrong first
     * impression of a file manager for everyone else. It changes how a row is drawn and
     * nothing else - the path bar, the drag, the selection and the menus are all unaware of
     * it.
     */
    private val _treeView = stored { sp.getBoolean(K_TREE_VIEW, false) }
    val treeView: StateFlow<Boolean> = _treeView.asStateFlow()

    fun setTreeView(on: Boolean) {
        _treeView.value = on
        sp.edit().putBoolean(K_TREE_VIEW, on).apply()
    }

    /**
     * Turning the record makes the sound follow the hand. See `ScratchEngine`.
     *
     * OFF by default, deliberately: it decodes a window of the track the moment a finger
     * lands on the disc, and that is a cost somebody should agree to before they meet it
     * rather than discover. With it off the disc still works and still moves through the
     * track - silently, by seeking, which is what it did before this existed.
     */
    /**
     * How much of the track one full turn of Aurora's disc is worth, in milliseconds.
     *
     * Its own setting, and NOT a sub-setting of whether the sound follows the hand: the disc
     * moves through the track either way, so how far it moves per degree is a question that
     * exists whether or not anything is audible. It sat under the sound switch, which made
     * it look like part of an effect rather than the one number that says what turning the
     * record does.
     */
    private val _discTurnMs = stored {
        sp.getFloat(K_DISC_TURN, dev.niccc2007.filet.playback.Scratch.MS_PER_TURN)
    }
    val discTurnMs: StateFlow<Float> = _discTurnMs.asStateFlow()

    fun setDiscTurnMs(v: Float) {
        val c = v.coerceIn(
            dev.niccc2007.filet.playback.Scratch.TURN_MIN_MS,
            dev.niccc2007.filet.playback.Scratch.TURN_MAX_MS,
        )
        _discTurnMs.value = c
        sp.edit().putFloat(K_DISC_TURN, c).apply()
    }

    private val _scratchAudio = stored { sp.getBoolean(K_SCRATCH, false) }
    val scratchAudio: StateFlow<Boolean> = _scratchAudio.asStateFlow()

    fun setScratchAudio(on: Boolean) {
        _scratchAudio.value = on
        sp.edit().putBoolean(K_SCRATCH, on).apply()
    }

    /**
     * The ring around Aurora's disc pulses on the music's own beats. See `BeatMap`.
     *
     * Off by default and for the same reason as the scratch: finding the beats means reading
     * the track once, which is a second or two of work nobody asked for. With it off the ring
     * still moves - a travelling highlight on its own clock, which is what it has always been
     * and is honest about being.
     */
    private val _beatPulse = stored { sp.getBoolean(K_BEAT_PULSE, false) }
    val beatPulse: StateFlow<Boolean> = _beatPulse.asStateFlow()

    fun setBeatPulse(on: Boolean) {
        _beatPulse.value = on
        sp.edit().putBoolean(K_BEAT_PULSE, on).apply()
    }



    /**
     * How far a beat may swing the glow, from its quietest to its hardest.
     *
     * One number for both ends, because they are one question. The swing is measured about
     * a resting level (see `glowSwing`), so turning this up takes the dark half DOWN and the
     * bright half UP together - two sliders for that would let somebody set a maximum below
     * their minimum, and there is nothing a split pair buys that is worth that.
     */
    private val _pulseDepth = stored { sp.getFloat(K_PULSE_DEPTH, 0.7f) }
    val pulseDepth: StateFlow<Float> = _pulseDepth.asStateFlow()

    fun setPulseDepth(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _pulseDepth.value = c
        sp.edit().putFloat(K_PULSE_DEPTH, c).apply()
    }

    /**
     * How far off the artwork the beat wave sits, 0 to 1.
     *
     * Its own setting because the artwork is the subject of this face and how much air to
     * leave round it is a matter of taste, not a thing to be guessed once.
     */
    private val _waveGap = stored { sp.getFloat(K_WAVE_GAP, 0.35f) }
    val waveGap: StateFlow<Float> = _waveGap.asStateFlow()

    fun setWaveGap(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _waveGap.value = c
        sp.edit().putFloat(K_WAVE_GAP, c).apply()
    }





    /**
     * There is no timing setting any more, and the key is dropped on sight.
     *
     * It held how far ahead of the player the beat map was read - a calibration somebody did
     * by eye, which is wrong on every device nobody has calibrated. Each term it stood in for
     * is derived now: the analysis window's own offset from `BeatMap.biasMs`, and the output,
     * which the player's position already carries (see `BeatLatency`).
     *
     * Removed rather than left to rot because a stored value nothing reads is a trap for the
     * next person who greps for it and assumes it is live.
     */
    init {
        if (sp.contains(K_PULSE_LEAD)) sp.edit().remove(K_PULSE_LEAD).apply()
        // The stored override has to reach `Tiers` on START, not only when the row is touched.
        // Without this a phone set to Full or Light goes back to Automatic on every cold launch
        // and the setting reads as doing nothing - which is the shape of bug this repo keeps
        // finding: the control works, the state never arrives.
        dev.niccc2007.filet.playback.Tiers.override.value =
            _deviceTier.value.takeIf { it != "auto" }
    }

    /**
     * How many lines a file's name may wrap onto in a list.
     *
     * ## What was asked for, and why it is a number rather than a switch
     *
     * Issue #11, in its own words: names should show in full, by allowing several lines, and
     * NOT by scrolling the text. Which is a sharper request than it first looks - a marquee
     * shows the whole name eventually, and the objection is to "eventually". A list is read by
     * scanning it, and a name that has to be waited for cannot be scanned.
     *
     * A number, not a boolean, because the cost is real and belongs to the reader: at four
     * lines a folder of long names is a third as many rows per screen. One keeps the old
     * behaviour exactly, which is why it is still available.
     *
     * The view step's own two-line default is a FLOOR, not a cap - see `ViewStep.twoLine`.
     * Compact deliberately stays at one line whatever this says, because its row is sized for
     * one and wrapping inside it would overlap the row below.
     */
    private val _nameLines = stored { sp.getInt(K_NAME_LINES, 2).coerceIn(1, 4) }
    val nameLines: StateFlow<Int> = _nameLines.asStateFlow()

    fun setNameLines(n: Int) {
        val c = n.coerceIn(1, 4)
        _nameLines.value = c
        sp.edit().putInt(K_NAME_LINES, c).apply()
    }

    /**
     * Whether `/`, `/data` and `/system` are offered as volumes.
     *
     * Only ever consulted on a phone where root has actually been granted - see
     * `RootProvider.roots`, which returns nothing without that regardless of this. So this is
     * the second gate rather than the first: somebody with root who does not want three more
     * drive cards on Home.
     */
    private val _rootVolumes = stored { sp.getBoolean(K_ROOT_VOLUMES, true) }
    val rootVolumes: StateFlow<Boolean> = _rootVolumes.asStateFlow()

    fun setRootVolumes(on: Boolean) {
        _rootVolumes.value = on
        sp.edit().putBoolean(K_ROOT_VOLUMES, on).apply()
    }

    /**
     * Whether a track opens where it was left rather than at the start.
     *
     * On by default. A ninety-minute film and a two-hour mix are things people come back to,
     * and coming back to the start of one is the behaviour of a preview pane. Still a switch,
     * because the opposite preference is real: somebody who uses this to audition files wants
     * the start of the file every time, and for them a remembered position is a bug.
     *
     * See `ResumeStore` for what is remembered and `Playback.load` for when it is read - last,
     * after anything the caller asked for, so a deliberate tap always wins.
     */
    private val _resumePlayback = stored { sp.getBoolean(K_RESUME, true) }
    val resumePlayback: StateFlow<Boolean> = _resumePlayback.asStateFlow()

    fun setResumePlayback(on: Boolean) {
        _resumePlayback.value = on
        sp.edit().putBoolean(K_RESUME, on).apply()
    }

    /** Which face the full music player draws. See `PlayerFace`. */
    /**
     * Whether a slowed track keeps its pitch or drops with the speed.
     *
     * Off means the framework time-stretches to hold the pitch, which is what warbles below
     * about 0.75x. On is plain resampling: smooth, and deeper. See `Playback.applySpeed`.
     */
    private val _pitchMode = stored { sp.getString(K_PITCH_MODE, null) }
    val pitchMode: StateFlow<String?> = _pitchMode.asStateFlow()

    fun setPitchMode(v: String) {
        _pitchMode.value = v
        sp.edit().putString(K_PITCH_MODE, v).apply()
    }

    private val _playerFace = stored { sp.getString(K_PLAYER_FACE, null) }
    val playerFace: StateFlow<String?> = _playerFace.asStateFlow()

    fun setPlayerFace(v: String) {
        _playerFace.value = v
        sp.edit().putString(K_PLAYER_FACE, v).apply()
    }

    /**
     * Home's New files and Recently opened cards: how many rows each shows, and how tightly.
     * Density runs 0 (Comfortable) to 1 (Compact); see `FeedCard` for what each end draws.
     * Eight rows and Comfortable are what both cards drew before either could be set.
     */
    private val _homeArrivedCount = stored { sp.getInt(K_HOME_ARRIVED_COUNT, 8) }
    val homeArrivedCount: StateFlow<Int> = _homeArrivedCount.asStateFlow()
    private val _homeArrivedDensity = stored { sp.getFloat(K_HOME_ARRIVED_DENSITY, HOME_FEED_DENSITY).coerceIn(0f, 1f) }
    val homeArrivedDensity: StateFlow<Float> = _homeArrivedDensity.asStateFlow()
    private val _homeOpenedCount = stored { sp.getInt(K_HOME_OPENED_COUNT, 8) }
    val homeOpenedCount: StateFlow<Int> = _homeOpenedCount.asStateFlow()
    private val _homeOpenedDensity = stored { sp.getFloat(K_HOME_OPENED_DENSITY, HOME_FEED_DENSITY).coerceIn(0f, 1f) }
    val homeOpenedDensity: StateFlow<Float> = _homeOpenedDensity.asStateFlow()

    fun setHomeArrivedCount(v: Int) { _homeArrivedCount.value = v; sp.edit().putInt(K_HOME_ARRIVED_COUNT, v).apply() }
    fun setHomeOpenedCount(v: Int) { _homeOpenedCount.value = v; sp.edit().putInt(K_HOME_OPENED_COUNT, v).apply() }

    fun setHomeArrivedDensity(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _homeArrivedDensity.value = c
        sp.edit().putFloat(K_HOME_ARRIVED_DENSITY, c).apply()
    }

    fun setHomeOpenedDensity(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _homeOpenedDensity.value = c
        sp.edit().putFloat(K_HOME_OPENED_DENSITY, c).apply()
    }

    /** The two feed cards share one density: moving either slider moves both. On from the start. */
    private val _homeFeedLinked = stored { sp.getBoolean(K_HOME_FEED_LINKED, true) }
    val homeFeedLinked: StateFlow<Boolean> = _homeFeedLinked.asStateFlow()

    fun setHomeFeedLinked(v: Boolean) { _homeFeedLinked.value = v; sp.edit().putBoolean(K_HOME_FEED_LINKED, v).apply() }

    /** The Playlists home shows its lists as a grid of covers rather than as rows. */
    private val _playlistsGrid = stored { sp.getBoolean(K_PLAYLISTS_GRID, false) }
    val playlistsGrid: StateFlow<Boolean> = _playlistsGrid.asStateFlow()

    fun setPlaylistsGrid(v: Boolean) { _playlistsGrid.value = v; sp.edit().putBoolean(K_PLAYLISTS_GRID, v).apply() }

    /**
     * The side bar's width in dp, kept separately for the drawer and for the rail. Stored as
     * dragged and clamped where it is drawn - `SideWidth` has why both.
     */
    private val _drawerWidth = stored { sp.getInt(K_DRAWER_WIDTH, dev.niccc2007.filet.browser.SideWidth.DEFAULT_DP) }
    val drawerWidth: StateFlow<Int> = _drawerWidth.asStateFlow()

    fun setDrawerWidth(v: Int) { _drawerWidth.value = v; sp.edit().putInt(K_DRAWER_WIDTH, v).apply() }

    private val _railWidth = stored { sp.getInt(K_RAIL_WIDTH, dev.niccc2007.filet.browser.SideWidth.DEFAULT_DP) }
    val railWidth: StateFlow<Int> = _railWidth.asStateFlow()

    fun setRailWidth(v: Int) { _railWidth.value = v; sp.edit().putInt(K_RAIL_WIDTH, v).apply() }

    /** The picture viewer's ground: black, or a neutral grey. See `ViewerGround`. */
    private val _viewerGrey = stored { sp.getBoolean(K_VIEWER_GREY, false) }
    val viewerGrey: StateFlow<Boolean> = _viewerGrey.asStateFlow()

    fun setViewerGrey(v: Boolean) { _viewerGrey.value = v; sp.edit().putBoolean(K_VIEWER_GREY, v).apply() }

    // ── reading: how every document is read. See `ReadingSettings`. ──
    private val _reading = stored {
        dev.niccc2007.filet.docs.ReadingSettings(
            size = dev.niccc2007.filet.docs.Reading.clampSize(sp.getInt(K_READ_SIZE, dev.niccc2007.filet.docs.Reading.DEFAULT_SIZE)),
            column = sp.getBoolean(K_READ_COLUMN, true),
            tables = dev.niccc2007.filet.docs.TableMode.of(sp.getString(K_READ_TABLES, null)),
            webPictures = sp.getBoolean(K_READ_WEB_PICTURES, true),
        )
    }
    val reading: StateFlow<dev.niccc2007.filet.docs.ReadingSettings> = _reading.asStateFlow()

    fun setReadSize(v: Int) {
        val size = dev.niccc2007.filet.docs.Reading.clampSize(v)
        _reading.value = _reading.value.copy(size = size)
        sp.edit().putInt(K_READ_SIZE, size).apply()
    }

    fun setReadColumn(v: Boolean) { _reading.value = _reading.value.copy(column = v); sp.edit().putBoolean(K_READ_COLUMN, v).apply() }

    fun setReadTables(v: dev.niccc2007.filet.docs.TableMode) {
        _reading.value = _reading.value.copy(tables = v)
        sp.edit().putString(K_READ_TABLES, v.name).apply()
    }

    fun setReadWebPictures(v: Boolean) {
        _reading.value = _reading.value.copy(webPictures = v)
        sp.edit().putBoolean(K_READ_WEB_PICTURES, v).apply()
    }

    // ── sound: each player's own. See `SoundMaths`, `Sound` and `SoundState`. ──
    //
    // Music and video were one sound for the process; they are two now, and the one an install
    // had before the split lands on both, so nobody's settings go anywhere. [splitSound] moves it
    // once at start. Until it has (an older settings file imported a moment ago, say), each read
    // falls back to the shared key while the player has none of its own. The pairs are in
    // `SettingsTransfer.SUPERSEDED`, once, for both directions.
    //
    // The app volume is stored as set and READ BACK no higher than 100%. A boost is for the
    // listening it was turned up for: kept across a restart, it is twice the volume the next
    // time anybody opens a file, with headphones already in. A remembered file keeps its own
    // volume; that is `SoundState`'s, not this.
    init { splitSound() }

    private fun splitSound() {
        val stored = sp.all
        if (LEGACY_SOUND.none { it in stored }) return
        val e = sp.edit()
        for ((k, v) in soundSplit(stored)) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
            }
        }
        for (k in LEGACY_SOUND) e.remove(k)
        e.apply()
    }

    /** The key to read: the player's own, or the shared one from before the split while it has none. */
    private fun soundKey(key: String, legacy: String): String =
        if (!sp.contains(key) && sp.contains(legacy)) legacy else key

    // The effects are in the keys the space and the filter always had, as levels now - the reverb
    // in the one and every other effect by name in the other; a name an older build stored there
    // is read as that effect, on exactly when the equaliser's switch it was heard under was. See
    // `SoundMaths.reverbOf`.
    private fun readSound(k: SoundKeys): dev.niccc2007.filet.playback.SoundSettings {
        val eqOn = sp.getBoolean(soundKey(k.eqOn, K_SOUND_EQ_ON), false)
        val base = dev.niccc2007.filet.playback.SoundSettings(
            eqOn = eqOn,
            bands = dev.niccc2007.filet.playback.SoundMaths.bandsOf(sp.getString(soundKey(k.bands, K_SOUND_BANDS), null)),
            bass = sp.getInt(soundKey(k.bass, K_SOUND_BASS), 0).coerceIn(0, 100),
            volume = dev.niccc2007.filet.playback.SoundMaths.clampVolume(sp.getInt(soundKey(k.volume, K_SOUND_VOLUME), 100)).coerceAtMost(100),
            reverb = dev.niccc2007.filet.playback.SoundMaths.reverbOf(sp.getString(soundKey(k.space, K_SOUND_SPACE), null), eqOn),
        )
        return dev.niccc2007.filet.playback.SoundMaths.withFilters(base, sp.getString(soundKey(k.filter, K_SOUND_FILTER), null), eqOn)
    }

    private val _sound = dev.niccc2007.filet.playback.SoundTarget.entries.associateWith { t ->
        stored { readSound(soundKeys(t)) }
    }
    private val soundFlows = _sound.mapValues { it.value.asStateFlow() }

    override fun soundOf(target: dev.niccc2007.filet.playback.SoundTarget): StateFlow<dev.niccc2007.filet.playback.SoundSettings> =
        soundFlows.getValue(target)

    /**
     * [target]'s general sound, all of it in one write, so its player gets one new plan - five
     * writes in a row would make and drop effects four times on the way to the one asked for.
     */
    override fun setSound(target: dev.niccc2007.filet.playback.SoundTarget, settings: dev.niccc2007.filet.playback.SoundSettings) {
        val k = soundKeys(target)
        val bands = dev.niccc2007.filet.playback.SoundMaths.bandsText(settings.bands)
        val s = settings.copy(
            bands = dev.niccc2007.filet.playback.SoundMaths.bandsOf(bands),
            bass = settings.bass.coerceIn(0, 100),
            volume = dev.niccc2007.filet.playback.SoundMaths.clampVolume(settings.volume),
        )
        _sound.getValue(target).value = s
        sp.edit()
            .putBoolean(k.eqOn, s.eqOn)
            .putString(k.bands, bands)
            .putInt(k.bass, s.bass)
            .putInt(k.volume, s.volume)
            .putString(k.space, dev.niccc2007.filet.playback.SoundMaths.levelText(s.reverb))
            .putString(k.filter, dev.niccc2007.filet.playback.SoundMaths.filtersText(s))
            .apply()
    }

    /** An edit to [target]'s general sound, from Settings. A player's sheet goes through `SoundState`. */
    fun editSound(
        target: dev.niccc2007.filet.playback.SoundTarget,
        change: (dev.niccc2007.filet.playback.SoundSettings) -> dev.niccc2007.filet.playback.SoundSettings,
    ) = setSound(target, change(soundOf(target).value))

    /** How long the Activity history keeps a finished job: 1, 7 or 30 days, or 0 for ever. */
    private val _jobsKeepDays = stored { sp.getInt(K_JOBS_KEEP_DAYS, 7) }
    val jobsKeepDays: StateFlow<Int> = _jobsKeepDays.asStateFlow()

    fun setJobsKeepDays(days: Int) { _jobsKeepDays.value = days; sp.edit().putInt(K_JOBS_KEEP_DAYS, days).apply() }

    fun setSort(s: SortSpec) {
        _sort.value = s
        val edit = sp.edit()
            .putString(K_SORT, s.key.name)
            .putBoolean(K_SORT_DESC, s.descending)
            .putBoolean(K_FOLDERS_FIRST, s.foldersFirst)
        // REMOVED rather than written as a sentinel: null is a real state here - "folders
        // follow the files" - and a sentinel string would have to be excluded from the enum
        // lookup for ever afterwards.
        if (s.folderKey == null) edit.remove(K_SORT_FOLDER) else edit.putString(K_SORT_FOLDER, s.folderKey.name)
        if (s.folderDescending == null) {
            edit.remove(K_SORT_FOLDER_DESC)
        } else {
            edit.putBoolean(K_SORT_FOLDER_DESC, s.folderDescending)
        }
        edit.apply()
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
    fun setIndexAuto(v: Boolean) { _indexAuto.value = v; sp.edit().putBoolean(K_INDEX_AUTO, v).apply() }
    fun setListenMinutes(v: Int) {
        val m = dev.niccc2007.filet.index.ListenWindow.normalise(v)
        _listenMinutes.value = m
        sp.edit().putInt(K_LISTEN_MINUTES, m).apply()
    }
    fun setSearchHidden(v: Boolean) { _searchHidden.value = v; sp.edit().putBoolean(K_SEARCH_HIDDEN, v).apply() }
    fun setStopConfirm(v: Boolean) { _stopConfirm.value = v; sp.edit().putBoolean(K_STOP_CONFIRM, v).apply() }

    fun setVerboseLogs(v: Boolean) {
        _verboseLogs.value = v
        sp.edit().putBoolean(dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE, v).apply()
        // Takes effect on this run, not the next one. A setting that needs a restart to mean
        // anything is one somebody flips and then reports as broken.
        dev.niccc2007.filet.log.FiletLog.verbose = v
        dev.niccc2007.filet.log.FiletLog.i("prefs", "verbose logging " + if (v) "on" else "off")
    }
    fun setTabSize(v: TabSize) { _tabSize.value = v; sp.edit().putString(K_TAB_SIZE, v.name).apply() }

    /** Written on drag release only — persisting every frame of a divider drag would thrash disk. */
    fun setSplitRatio(v: Float) {
        val c = v.coerceIn(0.2f, 0.8f)
        _splitRatio.value = c
        sp.edit().putFloat(K_SPLIT, c).apply()
    }

    // ── free-form string slots, used by features that own their own encoding ──
    /**
     * The stored value of each of [keys] that has one, for an export.
     *
     * Reads `sp.all` once rather than one typed getter per key, because a typed getter needs
     * to know the type and the whole point of a snapshot is that it does not.
     */
    fun snapshot(keys: Collection<String>): Map<String, Any?> {
        val all = sp.all
        return keys.mapNotNull { k -> all[k]?.let { k to it } }.toMap()
    }

    /**
     * Write the values out of a settings file, and report how many landed.
     *
     * ## Why a key can have to be REMOVED to import a file
     *
     * A settings file written by an older build carries the old key for a setting that has since
     * grown - `entry.subtitle` rather than `entry.parts`, `entry.measure` rather than
     * `entry.sideFile`. Both of those are read through a migration that only looks at the old key
     * when the NEW one is absent, which is the right rule at startup and the wrong one here: on a
     * phone where the new key already has a value, writing the old key alone changes nothing at
     * all and the import reports a number of settings while silently applying none of them.
     *
     * So a legacy key arriving without its successor clears the successor, which puts the stored
     * state back into the shape the migration was written for and lets it run again. See
     * [SettingsTransfer.SUPERSEDED], which is where the pairs are declared, once, and
     * [SettingsTransfer.toClear], which decides it and is tested.
     *
     * One `edit()` for the lot, so a partial failure cannot leave half a theme applied. A value
     * of a type that does not belong in preferences is skipped rather than coerced - see
     * `SettingsTransfer.encode` for why coercion is the dangerous option.
     */
    fun restore(values: Map<String, Any>, reset: Set<String> = emptySet()): Int {
        val e = sp.edit()
        // Before any write rather than per key: [SettingsTransfer.toClear] already leaves out
        // anything this same file sets, so there is nothing here for iteration order to decide.
        for (k in SettingsTransfer.toClear(values.keys)) e.remove(k)
        // Back to the default is the key being absent, which every getter already reads as such.
        for (k in reset) e.remove(k)
        var n = 0
        for ((k, v) in values) {
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                else -> continue
            }
            n++
        }
        e.apply()
        return n + reset.size
    }

    fun getString(key: String, def: String? = null): String? = sp.getString(key, def)
    fun putString(key: String, value: String?) { sp.edit().putString(key, value).apply() }
    fun getBool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    fun getInt(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    fun getLong(key: String, def: Long) = sp.getLong(key, def)
    fun putLong(key: String, value: Long) { sp.edit().putLong(key, value).apply() }

    // Internal, not private: a settings row names the key it writes, so its hint can show it.
    internal companion object {
        const val K_SORT = "sort.key"
        const val K_SORT_DESC = "sort.desc"
        const val K_FOLDERS_FIRST = "sort.foldersFirst"
        const val K_SORT_FOLDER = "sort.folderKey"
        const val K_SORT_FOLDER_DESC = "sort.folderDesc"
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
        const val K_INDEX_AUTO = "index.auto"
        const val K_LISTEN_MINUTES = "index.listenMinutes"
        const val K_SEARCH_HIDDEN = "search.hidden"
        const val K_STOP_CONFIRM = "stop.confirm"
        const val K_TAB_SIZE = "browse.tabSize"
        const val K_HIDE_STORAGE = "home.hideStorage"
        const val K_HIDDEN_CARDS = "home.hiddenCards"
        // A new key rather than a reused one. The old value was MENU or BAR, which is not a
        // list of action ids, and a stored MENU read as a list normalises to the default -
        // correct, but only by accident, and it leaves a value that means nothing behind.
        const val K_SELECTION_BAR = "browse.selectionBar"
        const val K_NOTES_FRACTION = "update.notesFraction"
        const val K_UPD_UNTIL = "update.silencedUntil"
        const val K_E_SUB = "entry.subtitle"
        const val K_E_PARTS = "entry.parts"
        const val K_E_MEASURE = "entry.measure"
        const val K_E_DATECOL = "entry.dateColumn"
        const val K_E_SIDE_FILE = "entry.sideFile"
        const val K_E_SIDE_FOLDER = "entry.sideFolder"
        const val K_E_STYLE = "entry.dateStyle"
        const val K_E_ORDER = "entry.dateOrder"
        const val K_E_SEP = "entry.dateSeparator"
        const val K_E_REL = "entry.relativeDates"

        const val K_UPD_VERSION = "update.silencedVersion"
        const val K_UPD_SKIPPED = "update.skippedVersion"
        const val K_UPD_OFF = "update.notificationsOff"
        const val K_MUSIC_UNINTERRUPTED = "play.uninterrupted"
        const val K_VIDEO_UNINTERRUPTED = "play.videoUninterrupted"

        /**
         * Give the video player its own uninterruptible setting, from the one the two players
         * shared, the first time it is read.
         *
         * Written into the store rather than read as a fallback. A fallback would leave the video
         * following the music's value for as long as nobody set the video's, so for most people
         * the two would still be one switch. Read again on a reload, which is what an import
         * from a build with the shared switch needs: both players get the value it carried.
         */
        fun seedVideoUninterrupted(sp: SharedPreferences) {
            if (sp.contains(K_VIDEO_UNINTERRUPTED)) return
            sp.edit().putBoolean(K_VIDEO_UNINTERRUPTED, sp.getBoolean(K_MUSIC_UNINTERRUPTED, false)).apply()
        }
        const val K_VIDEO_NEXT_PLAYS = "play.videoNext"
        const val K_VIDEO_SKIP = "play.videoSkip"
        const val K_VIEWER_ONLY_KIND = "viewer.onlyKind"
        const val K_VIDEO_PIP_HOME = "play.videoPip"

        /** The double-tap skip's lengths, in seconds. See [videoSkipSeconds]. */
        val VIDEO_SKIP_CHOICES = listOf(2, 5, 10)
        const val VIDEO_SKIP_DEFAULT = 10

        /** [raw] if it is one of [VIDEO_SKIP_CHOICES], else the default. */
        fun videoSkipOf(raw: Int): Int = if (raw in VIDEO_SKIP_CHOICES) raw else VIDEO_SKIP_DEFAULT
        const val K_STORE_MIGRATED = PrefKeys.STORE_MIGRATED
        const val K_MEMORY_DETAILS = "details.memory"
        const val K_NEW_FILES_ORDER = "home.newFilesOrder"
        const val K_CATEGORY_LAYOUT = "home.categoryLayout"
        const val K_PITCH_MODE = "play.pitch"
        const val K_PLAYER_FACE = "play.face"
        const val K_RESUME = "play.resume"
        const val K_ROOT_VOLUMES = "files.rootVolumes"
        const val K_NAME_LINES = "files.nameLines"
        const val K_DISC_GLOW = "play.discGlow"
        const val K_GLOW_LEVEL = "play.glowLevel"
        const val K_GLOW_STRENGTH = "play.glowStrength"
        const val K_VIBRANCY = "play.vibrancy"
        const val K_DISC_TURN = "play.discTurnMs"
        const val K_GLOW_SPILL = "play.glowSpill"
        const val K_PANE_SWIPE = "panes.swipe"
        const val K_PANE_SWIPE_AT = "panes.swipeAt"
        const val K_DRAWER_STORAGE = "drawer.storage"
        const val K_DRAWER_RECENT = "drawer.recent"
        const val K_DRAWER_PLACES = "drawer.places"
        const val K_SETTINGS_HINTS_SEEN = "settings.hintsSeen"
        const val K_DRIVE_NAMES = "drives.names"
        const val K_CACHE_REMIND = "cache.remindPast"
        const val K_CACHE_DISMISSED = "cache.remindDismissedAt"
        const val K_HOME_ORDER = "home.order"
        const val K_HOME_HIDDEN = "home.hiddenSections"
        const val K_DRAWER_ORDER = "drawer.order"
        const val K_DRAWER_HIDDEN = "drawer.hidden"
        const val K_DRAWER_QUICK = "drawer.quick"
        const val K_DRAWER_TOOLS = "drawer.tools"
        const val K_DRAWER_RECENT_NEW = "drawer.recentNew"
        const val K_HOME_FOLDS = "home.folds"
        const val K_TREE_VIEW = "view.tree"
        const val K_REACH_MODE = "view.reachMode"
        const val K_SPLIT_FIT = "view.splitFit"
        const val K_BLOCKED_FOLDERS = "home.blockedFolders"
        const val K_FEED_PREFIXES = "home.feedPrefixes"
        const val K_ALLMUSIC_DIRS = "play.allMusicFolders"
        const val K_ALLMUSIC_MIN = "play.allMusicMinMs"
        const val K_ALLMUSIC_MAX = "play.allMusicMaxMs"
        const val K_DEVICE_TIER = "play.deviceTier"
        const val K_PULSE_SENSE = "play.pulseSense"
        const val K_SCRATCH = "play.scratch"
        const val K_BEAT_PULSE = "play.pulse"
        const val K_PULSE_DEPTH = "play.pulseDepth"
        const val K_PULSE_LEAD = "play.pulseLead"
        const val K_WAVE_GAP = "play.waveGap"

        const val K_UPD_CHECKED = "update.lastCheckedAt"
        /**
         * Where both feed sliders start: a little towards Compact. Eight rows at full Comfortable
         * make the two feed cards most of a phone screen between them; this keeps both in view
         * under the categories without the rows reading as cramped.
         */
        const val HOME_FEED_DENSITY = 0.295f
        const val K_HOME_ARRIVED_COUNT = "home.arrived.count"
        const val K_HOME_ARRIVED_DENSITY = "home.arrived.density"
        const val K_HOME_OPENED_COUNT = "home.opened.count"
        const val K_HOME_OPENED_DENSITY = "home.opened.density"
        const val K_JOBS_KEEP_DAYS = "jobs.keepDays"
        const val K_HOME_FEED_LINKED = "home.feedLinked"
        const val K_PLAYLISTS_GRID = "playlists.grid"
        const val K_DRAWER_WIDTH = "drawer.width"
        const val K_RAIL_WIDTH = "rail.width"
        const val K_VIEWER_GREY = "viewer.grey"
        const val K_READ_SIZE = "read.size"
        const val K_READ_COLUMN = "read.column"
        const val K_READ_TABLES = "read.tables"
        const val K_READ_WEB_PICTURES = "read.webPictures"
        const val K_SOUND_MUSIC_EQ_ON = "sound.music.eqOn"
        const val K_SOUND_MUSIC_BANDS = "sound.music.bands"
        const val K_SOUND_MUSIC_BASS = "sound.music.bass"
        const val K_SOUND_MUSIC_VOLUME = "sound.music.volume"
        const val K_SOUND_MUSIC_SPACE = "sound.music.space"
        const val K_SOUND_MUSIC_FILTER = "sound.music.filter"
        const val K_SOUND_VIDEO_EQ_ON = "sound.video.eqOn"
        const val K_SOUND_VIDEO_BANDS = "sound.video.bands"
        const val K_SOUND_VIDEO_BASS = "sound.video.bass"
        const val K_SOUND_VIDEO_VOLUME = "sound.video.volume"
        const val K_SOUND_VIDEO_SPACE = "sound.video.space"
        const val K_SOUND_VIDEO_FILTER = "sound.video.filter"

        // The one sound both players shared before they were split. Read only to carry it over.
        const val K_SOUND_EQ_ON = "sound.eqOn"
        const val K_SOUND_BANDS = "sound.bands"
        const val K_SOUND_BASS = "sound.bass"
        const val K_SOUND_VOLUME = "sound.volume"
        const val K_SOUND_SPACE = "sound.space"
        const val K_SOUND_FILTER = "sound.filter"

        val LEGACY_SOUND = listOf(K_SOUND_EQ_ON, K_SOUND_BANDS, K_SOUND_BASS, K_SOUND_VOLUME, K_SOUND_SPACE, K_SOUND_FILTER)

        /** One player's six sound keys. */
        class SoundKeys(
            val eqOn: String,
            val bands: String,
            val bass: String,
            val volume: String,
            val space: String,
            val filter: String,
        )

        private val MUSIC_SOUND = SoundKeys(
            K_SOUND_MUSIC_EQ_ON, K_SOUND_MUSIC_BANDS, K_SOUND_MUSIC_BASS,
            K_SOUND_MUSIC_VOLUME, K_SOUND_MUSIC_SPACE, K_SOUND_MUSIC_FILTER,
        )
        private val VIDEO_SOUND = SoundKeys(
            K_SOUND_VIDEO_EQ_ON, K_SOUND_VIDEO_BANDS, K_SOUND_VIDEO_BASS,
            K_SOUND_VIDEO_VOLUME, K_SOUND_VIDEO_SPACE, K_SOUND_VIDEO_FILTER,
        )

        fun soundKeys(target: dev.niccc2007.filet.playback.SoundTarget): SoundKeys = when (target) {
            dev.niccc2007.filet.playback.SoundTarget.MUSIC -> MUSIC_SOUND
            dev.niccc2007.filet.playback.SoundTarget.VIDEO -> VIDEO_SOUND
        }

        /**
         * What the split writes: each shared value under every player's key that has none yet.
         * A player that already has its own keeps it - the newer of the two answers.
         */
        fun soundSplit(stored: Map<String, Any?>): Map<String, Any?> =
            SettingsTransfer.SUPERSEDED.filterKeys { it in LEGACY_SOUND }.flatMap { (old, newer) ->
                val v = stored[old] ?: return@flatMap emptyList()
                newer.filterNot { it in stored }.map { it to v }
            }.toMap()
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
 * @param tile null for the list steps; for the grid steps, the narrowest a cell may be in dp.
 *   The grid fits as many as the pane allows and never fewer than two - see `TileGrid`. Set so
 *   a phone held upright, 360dp to 412dp, gets four, three and two across.
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
    // THE LIST ICONS ARE BIGGER THAN THEY WERE - 14/16/21 became 18/24/34.
    //
    // They were sized when a folder was a flat glyph, where twenty-one dp is plenty: a
    // folder outline reads at any size because it is one shape. A folder now has its own
    // contents standing in it, and a file has a thumbnail of what it is, and at twenty-one
    // dp those are a few pixels each - so the whole point of the redesign was invisible at
    // the setting most people leave it on. Reported as not being able to see the features
    // even at Comfortable.
    //
    // The row heights move with them. An icon that grows inside a row that does not is a
    // cramped icon, and these steps have always scaled every dimension together.
    COMPACT_LIST("Compact list", 18, 12, 10, 9, 32),
    LIST("List", 24, 13, 11, 10, 42),
    COMFORTABLE("Comfortable", 34, 14, 11, 11, 60, twoLine = true),
    SMALL_GRID("Small grid", 30, 12, 10, 10, 0, tile = 84),
    MEDIUM_GRID("Medium grid", 42, 13, 11, 11, 0, tile = 108),
    LARGE_GRID("Large grid", 56, 15, 12, 12, 0, tile = 150);

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
    /**
     * The key FOLDERS are sorted on, or null to use the same one as the files.
     *
     * ## Why this is nullable rather than a second key plus a flag
     *
     * Asked for as issue #10: folders are easiest to find alphabetically and new files are
     * easiest to find by date, so one order cannot serve both. The obvious shape is two keys
     * and a boolean saying whether to use the second - and that is three values with an
     * illegal combination in it, where this is two values with none. Null means uniform, and
     * uniform is the default, so nothing changes for anybody who does not ask.
     *
     * It is only consulted when [foldersFirst] is on. With folders mixed in among the files
     * there is ONE sequence, and a sequence cannot be in two orders at once - so the setting
     * is ignored rather than half-applied. See `sortedBy`.
     */
    val folderKey: SortKey? = null,
    /** The direction for [folderKey], or null to follow [descending]. */
    val folderDescending: Boolean? = null,
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

/**
 * The player-detail ids, in the order the settings row shows them.
 *
 * Top level rather than in `Prefs`' companion because that companion is private and holds every
 * preference key in the app; opening it up so one screen can read one list would publish the
 * lot. A list, not a set, because the segmented row is index-based and the order is the UI's.
 */
internal val PLAYER_TIERS = listOf("auto", "full", "light")
