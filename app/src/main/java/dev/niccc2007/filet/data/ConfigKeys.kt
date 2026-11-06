package dev.niccc2007.filet.data

/**
 * Every preference Filet keeps in its main store, and what each one is.
 *
 * ## Why one registry
 *
 * Three things need the same facts about a key: the settings file Filet keeps in its own folder
 * (`filet.json`, see [ConfigSync]), the export that carries a setup to another phone
 * ([SettingsTransfer]), and anything that reads a value it did not write - a hand-edited file, an
 * import from an older build. Before this they were three lists. The export's lists had already
 * drifted: thirteen settings added since it was written were in none of its sections, and its
 * encoder returned null for a string set, so the custom extensions and the blocked folders were
 * listed for export and silently never written.
 *
 * So a key is declared here once, with its type and the export section it belongs to, and the
 * other two read it. `ConfigKeysTest` scans the sources for preference keys and fails on any that
 * is neither registered nor [EXCLUDED] with a reason - a new setting cannot be added without
 * deciding whether it travels.
 *
 * ## Why the type is declared rather than read off the stored value
 *
 * A key that has never been written has no stored value to read a type from, and a hand-edited
 * file says `18080` without saying whether that is an Int or a Long. `SharedPreferences` throws
 * on the next read of a key written with the wrong putter, at some unrelated moment later. The
 * declared type is what lets a value from a file be refused at the door instead.
 */
enum class ConfigType { BOOL, INT, LONG, FLOAT, STRING, STRING_SET, JSON }

/**
 * One preference.
 *
 * @param values the only strings it accepts, for a key that stores an enum's name. Null where
 *   the getter normalises anything it is given.
 * @param inFile whether `filet.json` carries it. False for what must not sit in a file any app
 *   with storage access can read - share access codes - and for a superseded key.
 * @param legacy a key a later key replaced, still read for old exports. See
 *   [SettingsTransfer.SUPERSEDED].
 */
data class ConfigKey(
    val key: String,
    val type: ConfigType,
    val section: SettingsTransfer.Section,
    val values: List<String>? = null,
    val inFile: Boolean = true,
    val legacy: Boolean = false,
)

object ConfigKeys {

    private fun names(entries: List<Enum<*>>) = entries.map { it.name }

    private fun look(key: String, type: ConfigType, values: List<String>? = null, inFile: Boolean = true, legacy: Boolean = false) =
        ConfigKey(key, type, SettingsTransfer.Section.LOOK, values, inFile, legacy)

    private fun act(key: String, type: ConfigType, values: List<String>? = null, inFile: Boolean = true, legacy: Boolean = false) =
        ConfigKey(key, type, SettingsTransfer.Section.BEHAVIOUR, values, inFile, legacy)

    private fun opener(key: String, type: ConfigType) =
        ConfigKey(key, type, SettingsTransfer.Section.OPENERS)

    private fun sharing(key: String, type: ConfigType, inFile: Boolean = true) =
        ConfigKey(key, type, SettingsTransfer.Section.SHARING, inFile = inFile)

    /** In the order `filet.json` lists them: the look, then behaviour, then the rest. */
    val ALL: List<ConfigKey> = listOf(
        // ── appearance and layout ──
        look("ui.theme", ConfigType.STRING, names(ThemeChoice.entries)),
        look("ui.accent", ConfigType.STRING, names(AccentChoice.entries)),
        look("browse.viewStep", ConfigType.INT),
        look("browse.tabSize", ConfigType.STRING, names(TabSize.entries)),
        look("browse.bottomBar", ConfigType.STRING),
        look("browse.selectionBar", ConfigType.STRING),
        look("browse.split", ConfigType.FLOAT),
        look("browse.restoreTabs", ConfigType.BOOL),
        look("entry.parts", ConfigType.STRING),
        look("entry.sideFile", ConfigType.STRING),
        look("entry.sideFolder", ConfigType.STRING),
        look("entry.dateStyle", ConfigType.STRING, names(dev.niccc2007.filet.browser.DateStyle.entries)),
        look("entry.dateOrder", ConfigType.STRING, names(dev.niccc2007.filet.browser.DateOrder.entries)),
        look("entry.dateSeparator", ConfigType.STRING),
        look("entry.relativeDates", ConfigType.BOOL),
        look("entry.subtitle", ConfigType.BOOL, inFile = false, legacy = true),
        look("entry.measure", ConfigType.BOOL, inFile = false, legacy = true),
        look("entry.dateColumn", ConfigType.BOOL, inFile = false, legacy = true),
        look("files.nameLines", ConfigType.INT),
        look("home.order", ConfigType.STRING),
        look("home.hiddenSections", ConfigType.STRING_SET),
        look("home.hiddenCards", ConfigType.STRING_SET),
        look("home.folds", ConfigType.STRING),
        look("home.arrived.count", ConfigType.INT),
        look("home.arrived.density", ConfigType.FLOAT),
        look("home.opened.count", ConfigType.INT),
        look("home.opened.density", ConfigType.FLOAT),
        look("home.feedLinked", ConfigType.BOOL),
        look("playlists.grid", ConfigType.BOOL),
        look("drawer.order", ConfigType.STRING),
        look("drawer.width", ConfigType.INT),
        look("rail.width", ConfigType.INT),
        look("viewer.grey", ConfigType.BOOL),
        // How documents read: the same for every document, so they travel with the look.
        look("read.size", ConfigType.INT),
        look("read.column", ConfigType.BOOL),
        look("read.tables", ConfigType.STRING, names(dev.niccc2007.filet.docs.TableMode.entries)),
        look("drawer.hidden", ConfigType.STRING_SET),
        look("drawer.quick", ConfigType.BOOL),
        look("drawer.recent", ConfigType.BOOL),
        look("drawer.recentNew", ConfigType.BOOL),
        look("drawer.storage", ConfigType.BOOL),
        look("drawer.places", ConfigType.BOOL),
        look("drawer.tools", ConfigType.BOOL),
        look("drives.names", ConfigType.JSON),
        look("details.memory", ConfigType.BOOL),

        // ── sorting and behaviour ──
        act("sort.key", ConfigType.STRING, names(SortKey.entries)),
        act("sort.desc", ConfigType.BOOL),
        act("sort.foldersFirst", ConfigType.BOOL),
        act("sort.folderKey", ConfigType.STRING, names(SortKey.entries)),
        act("sort.folderDesc", ConfigType.BOOL),
        act("browse.hidden", ConfigType.BOOL),
        act("files.rootVolumes", ConfigType.BOOL),
        act("view.tree", ConfigType.BOOL),
        // Whether a document fetches its pictures from the web: what the phone does, not how it looks.
        act("read.webPictures", ConfigType.BOOL),
        act("view.reachMode", ConfigType.BOOL),
        act("view.splitFit", ConfigType.STRING),
        act("panes.swipe", ConfigType.BOOL),
        act("panes.swipeAt", ConfigType.STRING),
        act("home.folder", ConfigType.STRING),
        act("home.hideStorage", ConfigType.BOOL),
        act("home.newFilesOrder", ConfigType.STRING),
        act("home.categoryLayout", ConfigType.STRING),
        act("home.blockedFolders", ConfigType.STRING_SET),
        act("home.feedPrefixes", ConfigType.STRING_SET),
        act("index.enabled", ConfigType.BOOL),
        act("index.auto", ConfigType.BOOL),
        act("index.listenMinutes", ConfigType.INT),
        act("search.hidden", ConfigType.BOOL),
        act("stop.confirm", ConfigType.BOOL),
        act("cache.location", ConfigType.STRING, names(FiletStore.Location.entries)),
        act("cache.remindPast", ConfigType.LONG),
        act("logs.verbose", ConfigType.BOOL),
        act("update.notificationsOff", ConfigType.BOOL),
        act("update.notesFraction", ConfigType.FLOAT),
        act("play.uninterrupted", ConfigType.BOOL),
        act("play.videoUninterrupted", ConfigType.BOOL),
        act("play.videoNext", ConfigType.BOOL),
        act("play.videoSkip", ConfigType.INT),
        act("viewer.onlyKind", ConfigType.BOOL),
        act("play.videoPip", ConfigType.BOOL),
        // Each player's own sound, music and video apart. The volume travels as set; reading it
        // back holds it at 100% or under, so an imported boost does not arrive at full blast.
        act("sound.music.eqOn", ConfigType.BOOL),
        act("sound.music.bands", ConfigType.STRING),
        act("sound.music.bass", ConfigType.INT),
        act("sound.music.volume", ConfigType.INT),
        act("sound.music.space", ConfigType.STRING),
        act("sound.music.filter", ConfigType.STRING),
        act("sound.video.eqOn", ConfigType.BOOL),
        act("sound.video.bands", ConfigType.STRING),
        act("sound.video.bass", ConfigType.INT),
        act("sound.video.volume", ConfigType.INT),
        act("sound.video.space", ConfigType.STRING),
        act("sound.video.filter", ConfigType.STRING),
        // The one sound both shared before the split: read from older files, never written.
        act("sound.eqOn", ConfigType.BOOL, inFile = false, legacy = true),
        act("sound.bands", ConfigType.STRING, inFile = false, legacy = true),
        act("sound.bass", ConfigType.INT, inFile = false, legacy = true),
        act("sound.volume", ConfigType.INT, inFile = false, legacy = true),
        act("sound.space", ConfigType.STRING, inFile = false, legacy = true),
        act("sound.filter", ConfigType.STRING, inFile = false, legacy = true),
        act("play.resume", ConfigType.BOOL),
        act("play.face", ConfigType.STRING),
        act("play.pitch", ConfigType.STRING),
        act("play.deviceTier", ConfigType.STRING, PLAYER_TIERS),
        act("play.discGlow", ConfigType.BOOL),
        act("play.glowLevel", ConfigType.STRING),
        act("play.glowStrength", ConfigType.FLOAT),
        act("play.glowSpill", ConfigType.BOOL),
        act("play.vibrancy", ConfigType.FLOAT),
        act("play.discTurnMs", ConfigType.FLOAT),
        act("play.scratch", ConfigType.BOOL),
        act("play.pulse", ConfigType.BOOL),
        act("play.pulseSense", ConfigType.FLOAT),
        act("play.pulseDepth", ConfigType.FLOAT),
        act("play.waveGap", ConfigType.FLOAT),
        act("play.allMusicFolders", ConfigType.STRING_SET),
        act("play.allMusicMinMs", ConfigType.LONG),
        act("play.allMusicMaxMs", ConfigType.LONG),
        act("allmusic.sort", ConfigType.STRING),
        act("jobs.keepDays", ConfigType.INT),

        // ── openers ──
        opener("handlers.v1", ConfigType.JSON),
        opener("handlers.external.v1", ConfigType.JSON),
        opener("openers.customExtensions", ConfigType.STRING_SET),

        // ── lists with files of their own ──
        // In the export, and not in filet.json: bookmarks.json is theirs.
        ConfigKey("bookmarks", ConfigType.JSON, SettingsTransfer.Section.BOOKMARKS, inFile = false),

        // ── what this phone shares ──
        sharing("dav.port", ConfigType.INT),
        sharing("nearby.uploads", ConfigType.BOOL),
        sharing("nearby.idle", ConfigType.LONG),
        sharing("nearby.grant.lifetime", ConfigType.LONG),
        sharing("nearby.grant.idle", ConfigType.LONG),
        // The share lists carry each share's access code. An export is a file somebody chose
        // to write; filet.json sits in a folder any app with storage access can read.
        sharing("dav.shares", ConfigType.STRING, inFile = false),
        sharing("nearby.shares", ConfigType.STRING, inFile = false),
    )

    /**
     * Keys in the main store that are deliberately in neither the file nor any export, and why.
     *
     * [SettingsTransfer.NEVER] is the part of this the export screen documents; the rest is
     * state this install keeps about itself.
     */
    val EXCLUDED: Map<String, String> = SettingsTransfer.NEVER + mapOf(
        "folderVisits" to "which folders were worked in on this phone",
        "home.hint.shown" to "first-run help this phone has already seen",
        "store_migrated" to "a one-time move of this install's folders",
        "settings.hintsSeen" to "first-run help this phone has already seen",
        "cache.remindDismissedAt" to "a reminder dismissed at this phone's cache size",
        "play.pulseLead" to "retired: the lateness is measured now, see BeatLatency",
        "drag.behaviour" to "retired: dragging between panes always asks",
        "dav.root" to "superseded by dav.shares, which folds it in on upgrade",
        "nearby.fixedPin" to "this phone's pairing pin",
        "signing.defaultAlias" to "names a key in this phone's keystore, which never leaves it",
        "playlists.v1" to "kept in playlists.json, a file of its own",
    )

    private val byKey: Map<String, ConfigKey> = ALL.associateBy { it.key }

    fun of(key: String): ConfigKey? = byKey[key]

    /** What `filet.json` carries, in file order. */
    val FILE: List<ConfigKey> = ALL.filter { it.inFile }

    /** The keys of one export section, in registry order. */
    fun keysOf(section: SettingsTransfer.Section): List<String> =
        ALL.filter { it.section == section }.map { it.key }
}
