package dev.niccc2007.filet.browser

import dev.niccc2007.filet.home.MediaCategory

/**
 * Where a tab sits, in the bar picker and on the Shortcuts tab. The side bar's own folds, so a
 * tab is found under the same heading everywhere it is offered.
 */
enum class TabGroup(val label: String) {
    PLACES("Places"),
    COLLECTIONS("Collections"),
    CATEGORIES("Categories"),
    TOOLS("Tools"),
    FILET("Filet"),
}

/**
 * Every tab that can be opened by name: from the bottom bar, from a home-screen shortcut, and
 * from the app icon's hold menu.
 *
 * ## The failure this closes
 *
 * The bar's list and the shortcut list were written by hand, separately, and each new tab had to
 * be remembered in both. Apps, the QR scanner and the six categories were in neither, and Carry on
 * reached the bar but never became a shortcut. Here a tab is declared once; the bar items point at
 * it, the shortcut catalogue lists it, and `TabTargetsTest` fails when a pane can be opened from
 * the side bar but is missing from this list.
 *
 * @param id stored in the bar setting and inside pinned shortcuts, so it never changes. The older
 *   ones match the bar ids they replaced, which is what keeps a saved bar and an old shortcut
 *   working.
 * @param kind the pane it opens. [FILES] is a folder rather than a pane of its own.
 * @param home the name a home-screen shortcut is offered with. Where the plain name is also
 *   another app's or a system screen's - Settings, Share, Photos - it starts with Filet, or two
 *   icons on one home screen read the same and open different things. It can be changed before
 *   the icon is placed.
 * @param bar the bar's caption, where [label] is too long for it.
 */
enum class TabTarget(
    val id: String,
    val label: String,
    val group: TabGroup,
    val kind: PaneKind,
    val category: MediaCategory? = null,
    val home: String = label,
    val bar: String = label,
) {
    HOME("home", "Home", TabGroup.PLACES, PaneKind.HOME, home = "Filet Home"),

    /**
     * The phone's own storage. Called Files once, which read as a list of every file and not as
     * the one drive it opens - the name the drive already has everywhere else in Filet.
     */
    FILES("files", "Internal storage", TabGroup.PLACES, PaneKind.FOLDER, bar = "Storage"),
    BOOKMARKS("bookmarks", "Bookmarks", TabGroup.PLACES, PaneKind.BOOKMARKS, home = "Filet Bookmarks"),
    OPENED("recent", "Opened", TabGroup.PLACES, PaneKind.RECENT),
    NEW_FILES("newfiles", "New files", TabGroup.PLACES, PaneKind.HISTORY),

    PLAYLISTS("playlists", "Playlists", TabGroup.COLLECTIONS, PaneKind.PLAYLISTS, home = "Filet Playlists"),
    CARRY_ON("resume", "Carry on", TabGroup.COLLECTIONS, PaneKind.RESUME),

    PHOTOS("cat.images", "Photos", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.IMAGES, home = "Filet Photos"),
    VIDEOS("cat.videos", "Videos", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.VIDEOS, home = "Filet Videos"),
    AUDIO("cat.audio", "Audio", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.AUDIO, home = "Filet Audio"),
    PACKAGES("cat.apps", "Packages", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.APPS),
    DOCUMENTS("cat.documents", "Documents", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.DOCUMENTS, home = "Filet Documents"),
    ARCHIVES("cat.archives", "Archives", TabGroup.CATEGORIES, PaneKind.CATEGORY, MediaCategory.ARCHIVES),

    REMOTES("remotes", "Remotes", TabGroup.TOOLS, PaneKind.REMOTES),
    SHARE("sharing", "Share", TabGroup.TOOLS, PaneKind.NEARBY, home = "Filet Share"),
    APPS("apps", "Apps", TabGroup.TOOLS, PaneKind.APPS, home = "Filet Apps"),
    SHORTCUTS("shortcuts", "Shortcuts", TabGroup.TOOLS, PaneKind.SHORTCUTS, home = "Filet Shortcuts"),
    SCRIPTS("scripts", "Scripts", TabGroup.TOOLS, PaneKind.SCRIPTS),
    SCAN("scan", "QR Scanner", TabGroup.TOOLS, PaneKind.SCAN, home = "Filet QR"),

    ACTIVITY("activity", "Activity", TabGroup.FILET, PaneKind.ACTIVITY, home = "Filet Activity"),
    SETTINGS("settings", "Settings", TabGroup.FILET, PaneKind.SETTINGS, home = "Filet Settings");

    companion object {
        /** Null for an id this build does not have: a pinned shortcut outlives the version that made it. */
        fun byId(id: String?): TabTarget? = entries.firstOrNull { it.id == id }

        fun inGroup(group: TabGroup): List<TabTarget> = entries.filter { it.group == group }
    }
}

/** The tab's line glyph, the one the bar and the side bar draw. */
fun tabGlyph(target: TabTarget): androidx.compose.ui.graphics.vector.ImageVector = when (target) {
    TabTarget.HOME -> FiletIcons.Home
    TabTarget.FILES -> FiletIcons.Storage
    TabTarget.BOOKMARKS -> FiletIcons.Star
    TabTarget.OPENED -> FiletIcons.Clock
    TabTarget.NEW_FILES -> FiletIcons.Download
    TabTarget.PLAYLISTS -> FiletIcons.Audio
    TabTarget.CARRY_ON -> FiletIcons.Clock
    TabTarget.PHOTOS, TabTarget.VIDEOS, TabTarget.AUDIO, TabTarget.PACKAGES,
    TabTarget.DOCUMENTS, TabTarget.ARCHIVES -> target.category!!.icon
    TabTarget.REMOTES -> FiletIcons.Device
    TabTarget.SHARE -> FiletIcons.Wifi
    TabTarget.APPS -> FiletIcons.Apk
    TabTarget.SHORTCUTS -> FiletIcons.Pin
    TabTarget.SCRIPTS -> FiletIcons.Script
    TabTarget.SCAN -> FiletIcons.Scan
    TabTarget.ACTIVITY -> FiletIcons.Jobs
    TabTarget.SETTINGS -> FiletIcons.Cog
}
