package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.icons.COLOUR_IDS
import dev.niccc2007.filet.icons.EMBLEM_IDS
import dev.niccc2007.filet.icons.FolderLook
import dev.niccc2007.filet.script.Script
import dev.niccc2007.filet.vfs.VNode

// What a home-screen shortcut is for, and the icon it wears: the shortcut sheet's model, kept
// apart from the sheet so every rule in it can be checked without a phone.

/**
 * Whether the plainer icon is offered on the sheet. Off until the minimalist look is settled;
 * everything it needs is built, so offering it is this one value.
 */
internal const val SIMPLE_OFFERED = false

/** Where a shortcut's icon comes from, as the sheet's Icon row names it. */
enum class IconSource(val label: String) {
    PICTURE("Picture"),
    TYPE("File type"),
    FOLDER("Folder"),
    EMBLEM("Emblem"),
    DRAWING("Drawing"),
    FILET("Filet"),
    SIMPLE("Simple"),
}

/**
 * The icon chosen for a shortcut, kept on its record as one short string.
 *
 * Anything that string cannot say - a source this build does not have, an emblem or colour that
 * was dropped - reads back as null, which means the kind's own icon. An old shortcut never
 * breaks; at worst it wears what it would have worn before the choice existed.
 */
sealed interface ShortcutIcon {
    val source: IconSource

    /** The file's own picture: cut to fill the launcher's shape, or whole in its middle on nothing. */
    data class Picture(val framed: Boolean) : ShortcutIcon {
        override val source get() = IconSource.PICTURE
    }

    /** The file-type icon the list draws. */
    data object Type : ShortcutIcon {
        override val source get() = IconSource.TYPE
    }

    /**
     * A folder: [own] null wears the folder's look in Filet and follows it; a look of its own stays
     * as it was set, whatever the folder wears later.
     */
    data class Folder(val own: FolderLook?) : ShortcutIcon {
        override val source get() = IconSource.FOLDER
    }

    /** An emblem on its own, drawn full size. */
    data class Emblem(val emblem: String?, val colour: String) : ShortcutIcon {
        override val source get() = IconSource.EMBLEM
    }

    /** A tab's or an action's drawing, by its id in the tab icon mock. */
    data class Drawing(val art: String) : ShortcutIcon {
        override val source get() = IconSource.DRAWING
    }

    /** Filet's own icon. */
    data object Filet : ShortcutIcon {
        override val source get() = IconSource.FILET
    }

    /** The app's line icon on a plain disc in Filet's colour. See [SIMPLE_OFFERED]. */
    data object Simple : ShortcutIcon {
        override val source get() = IconSource.SIMPLE
    }

    fun encode(): String = when (this) {
        is Picture -> if (framed) "picture:framed" else "picture:fill"
        Type -> "type"
        is Folder -> own?.let { "folder:own:${it.emblem ?: NONE}:${it.colour}" + (it.cover?.let { c -> ":$c" } ?: "") } ?: "folder:same"
        is Emblem -> "emblem:${emblem ?: NONE}:$colour"
        is Drawing -> "art:$art"
        Filet -> "filet"
        Simple -> "simple"
    }

    companion object {
        private const val NONE = "-"

        /** The icon [raw] names, or null for anything this build cannot draw. */
        fun parse(raw: String?): ShortcutIcon? {
            if (raw.isNullOrBlank()) return null
            val p = raw.split(':', limit = 5)
            return when (p[0]) {
                "picture" -> when (p.getOrNull(1)) {
                    "fill" -> Picture(framed = false)
                    "framed" -> Picture(framed = true)
                    else -> null
                }
                "type" -> Type.takeIf { p.size == 1 }
                "folder" -> when (p.getOrNull(1)) {
                    "same" -> Folder(null)
                    "own" -> {
                        val emblem = emblemOrNull(p.getOrNull(2)) ?: return null
                        val colour = p.getOrNull(3)?.takeIf { it in COLOUR_IDS } ?: return null
                        Folder(FolderLook(emblem.value, colour, p.getOrNull(4)?.ifEmpty { null }))
                    }
                    else -> null
                }
                "emblem" -> {
                    val emblem = emblemOrNull(p.getOrNull(1)) ?: return null
                    val colour = p.getOrNull(2)?.takeIf { it in COLOUR_IDS } ?: return null
                    Emblem(emblem.value, colour).takeIf { p.size == 3 }
                }
                "art" -> p.getOrNull(1)?.takeIf { it in TabIconRes.LAUNCHER && p.size == 2 }?.let(::Drawing)
                "filet" -> Filet.takeIf { p.size == 1 }
                "simple" -> Simple.takeIf { p.size == 1 }
                else -> null
            }
        }

        /** No emblem, a known one, or null for an id this build does not have. */
        private fun emblemOrNull(raw: String?): Box? = when (raw) {
            null -> null
            NONE -> Box(null)
            in EMBLEM_IDS -> Box(raw)
            else -> null
        }

        private class Box(val value: String?)
    }
}

/**
 * What a shortcut is for: a file or a folder, a tab, an action or a script.
 *
 * Every way of making a shortcut ends here, so the sheet is the same sheet whichever way it was
 * opened.
 */
sealed interface PinSubject {
    val kind: ShortcutKind

    /** The name the shortcut is offered under. */
    val name: String

    /** What it is, in a word, after its name on the Opens row. */
    val noun: String

    /** A file or a folder. [picture] is whether Filet can draw a picture of it. */
    data class File(val node: VNode, val picture: Boolean) : PinSubject {
        override val kind get() = ShortcutKind.FILE
        override val name get() = node.name
        override val noun: String get() = if (node.isDir) "folder" else when (FileKind.of(node)) {
            FileKind.IMAGE -> "photo"
            FileKind.VIDEO -> "video"
            FileKind.AUDIO -> "audio"
            FileKind.APK -> "app"
            FileKind.ARCHIVE -> "archive"
            else -> "file"
        }
    }

    data class Tab(val target: TabTarget) : PinSubject {
        override val kind get() = ShortcutKind.TAB
        override val name get() = target.home
        override val noun get() = "tab"
    }

    data class Action(val action: AppAction) : PinSubject {
        override val kind get() = ShortcutKind.ACTION
        override val name get() = action.home
        override val noun get() = "action"
    }

    data class Scripted(val script: Script) : PinSubject {
        override val kind get() = ShortcutKind.SCRIPT
        override val name get() = script.name
        override val noun get() = "script"
    }
}

/** The drawing a subject wears by its nature: a tab's or action's own, the Scripts tab's for a script. */
fun ownArt(s: PinSubject): String? = when (s) {
    is PinSubject.Tab -> s.target.art
    is PinSubject.Action -> s.action.art
    is PinSubject.Scripted -> TabTarget.SCRIPTS.art
    is PinSubject.File -> null
}

/** Which sources the sheet offers [s], in the order its Icon row shows them. The first is the default. */
fun sourcesFor(s: PinSubject, simple: Boolean = SIMPLE_OFFERED): List<IconSource> = buildList {
    when {
        s is PinSubject.File && s.node.isDir -> add(IconSource.FOLDER)
        s is PinSubject.File -> {
            if (s.picture) add(IconSource.PICTURE)
            add(IconSource.TYPE)
        }
        else -> add(IconSource.DRAWING)
    }
    add(IconSource.EMBLEM)
    if (s is PinSubject.File) add(IconSource.DRAWING)
    add(IconSource.FILET)
    if (simple) add(IconSource.SIMPLE)
}

/**
 * Everything the sheet's Icon section holds at once, so moving between sources and back keeps
 * what was set in each.
 *
 * @param framed for a picture.
 * @param emblem, [colour] for the Emblem source.
 * @param group the emblem group being shown, for both emblem grids.
 * @param art for the Drawing source.
 * @param folderOwn whether a folder's shortcut has a look of its own; [look] is then that look,
 *   and otherwise the folder's own as it will be once the sheet is added.
 */
data class IconDraft(
    val source: IconSource,
    val framed: Boolean = false,
    val emblem: String? = null,
    val colour: String = "steel",
    val group: String = "All",
    val art: String = "documents",
    val folderOwn: Boolean = false,
    val look: FolderLook = FolderLook.PLAIN,
) {
    /** The icon this draft makes, for the record and for the launcher. */
    fun icon(): ShortcutIcon = when (source) {
        IconSource.PICTURE -> ShortcutIcon.Picture(framed)
        IconSource.TYPE -> ShortcutIcon.Type
        IconSource.FOLDER -> ShortcutIcon.Folder(if (folderOwn) look else null)
        IconSource.EMBLEM -> ShortcutIcon.Emblem(emblem, colour)
        IconSource.DRAWING -> ShortcutIcon.Drawing(art)
        IconSource.FILET -> ShortcutIcon.Filet
        IconSource.SIMPLE -> ShortcutIcon.Simple
    }

    /** The same draft wearing [icon] in its source, as when editing a shortcut that has one. */
    fun wearing(icon: ShortcutIcon): IconDraft = when (icon) {
        is ShortcutIcon.Picture -> copy(source = IconSource.PICTURE, framed = icon.framed)
        ShortcutIcon.Type -> copy(source = IconSource.TYPE)
        is ShortcutIcon.Folder -> if (icon.own != null) copy(source = IconSource.FOLDER, folderOwn = true, look = icon.own)
            else copy(source = IconSource.FOLDER, folderOwn = false)
        is ShortcutIcon.Emblem -> copy(source = IconSource.EMBLEM, emblem = icon.emblem, colour = icon.colour)
        is ShortcutIcon.Drawing -> copy(source = IconSource.DRAWING, art = icon.art)
        ShortcutIcon.Filet -> copy(source = IconSource.FILET)
        ShortcutIcon.Simple -> copy(source = IconSource.SIMPLE)
    }

    companion object {
        /**
         * The draft a sheet opens with: the subject's first source, and in every other source what
         * suits it. A folder's emblem and colour start from the look it wears in Filet now.
         *
         * @param folderLook the folder's look in Filet now, for a folder.
         * @param seeThrough whether the file's picture has see-through edges: such a picture, an
         *   app's icon say, starts framed so it keeps its shape.
         * @param current the icon a shortcut being edited already wears.
         */
        fun start(
            s: PinSubject,
            folderLook: FolderLook? = null,
            seeThrough: Boolean = false,
            current: ShortcutIcon? = null,
        ): IconDraft {
            val isFile = s is PinSubject.File && !s.node.isDir
            val isFolder = s is PinSubject.File && s.node.isDir
            val base = IconDraft(
                source = sourcesFor(s).first(),
                framed = seeThrough,
                emblem = folderLook?.emblem ?: if (isFile) "document" else "star",
                colour = folderLook?.colour ?: "steel",
                art = ownArt(s) ?: if (isFolder) TabTarget.FILES.art else "documents",
                look = folderLook ?: FolderLook.PLAIN,
            )
            val worn = current?.takeIf { it.source in sourcesFor(s, simple = true) } ?: return base
            return base.wearing(worn)
        }
    }
}

/** The kind's own icon, for a record with no choice kept or one this build cannot read. */
fun defaultIcon(s: PinSubject, seeThrough: Boolean = false): ShortcutIcon = IconDraft.start(s, seeThrough = seeThrough).icon()

/** One line under the name saying what a tap on the icon will do. */
fun opensLine(s: PinSubject): String = when (s) {
    is PinSubject.File -> if (s.node.isDir) "Opens the folder in Filet, and follows it if you move it." else "Follows the file if you move it."
    is PinSubject.Tab -> "Opens Filet on ${s.target.label}."
    is PinSubject.Action -> when (s.action) {
        AppAction.SEARCH -> "Opens Filet with the search box ready."
        AppAction.INDEX_NOW -> "Starts indexing without opening Settings."
        // The held icon's menu flips between the two; a placed icon cannot, so each says what it does.
        AppAction.SHARE_NEARBY -> "Starts sharing and shows the code."
        AppAction.STOP_SHARING -> "Stops sharing without opening the tab."
        else -> s.action.description + "."
    }
    is PinSubject.Scripted -> "Runs the script in Filet."
}

/**
 * The line under the sheet's title: where a file or folder lives, that it is already on the home
 * screen, or what kind of thing it is.
 *
 * @param where the file's folder as the app names it, "DCIM/Camera" or "Internal storage".
 */
fun sheetSub(s: PinSubject, editing: Boolean, where: String?): String = when {
    s is PinSubject.File -> if (where.isNullOrEmpty()) s.node.name else "$where/${s.node.name}"
    editing -> "On your home screen"
    else -> "${s.noun.replaceFirstChar { it.uppercase() }} · ${s.name}"
}

/** What a file's shortcut can open it with. A folder has no choice: it opens in Filet. */
fun openersFor(node: VNode): List<Pair<HandlerId?, String>> = buildList {
    if (node.isDir) return@buildList
    add(null to "Default")
    when (FileKind.of(node)) {
        FileKind.IMAGE -> add(HandlerId.IMAGE to "Image viewer")
        FileKind.VIDEO, FileKind.AUDIO -> add(HandlerId.MEDIA to "Media player")
        FileKind.APK -> add(HandlerId.APK to "APK inspector")
        FileKind.ARCHIVE -> add(HandlerId.ARCHIVE to "Archive viewer")
        else -> Unit
    }
    add(HandlerId.TEXT to "Text editor")
    add(HandlerId.HEX to "Hex viewer")
    add(HandlerId.EXTERNAL to "Another app")
}.distinctBy { it.first }

/** Every tab and action drawing the Drawing source offers, in the tab icon mock's order, Stop sharing left out. */
val DRAWINGS: List<String> get() = TabIconRes.LAUNCHER.keys.filter { it != AppAction.STOP_SHARING.art }

/** The name of a drawing, as its tab or action is called. */
fun drawingLabel(art: String): String =
    TabTarget.entries.firstOrNull { it.art == art }?.label
        ?: AppAction.entries.firstOrNull { it.art == art }?.label
        ?: art

/**
 * A file's folder as the sheet's title line names it: inside the phone's own storage, the path
 * from its top ("DCIM/Camera"), or "Internal storage" for the top itself; anywhere else, the path.
 */
fun whereOf(folder: String?): String {
    val p = folder?.trimEnd('/') ?: return ""
    val top = Regex("""^/storage/emulated/\d+""").find(p)
    if (top != null) return p.substring(top.range.last + 1).trim('/').ifEmpty { "Internal storage" }
    return p.trim('/')
}

/** How the sheet was reached, which decides its Opens row and its buttons. */
enum class SheetFrom {
    /** A file's menu, a row's hold, a tab's long-press: the subject is already plain. */
    MENU,

    /** The Shortcuts tab: the Opens row, with Change to pick something else. */
    TAB,

    /** A shortcut already on the home screen: Opens without Change, and Remove, Cancel, Save. */
    EDIT,
}

/** The sheet for [subject]; [editing] is the record when it is already placed. */
data class ShortcutAsk(val subject: PinSubject, val from: SheetFrom, val editing: ShortcutRecord? = null)

/** What is open: the sheet, or the list of what a new shortcut could open. */
sealed interface ShortcutSheetState {
    data class Sheet(val ask: ShortcutAsk) : ShortcutSheetState

    /** [returnTo] is the sheet its Back goes to, or none for a list opened from New shortcut. */
    data class Choosing(val returnTo: ShortcutAsk?) : ShortcutSheetState
}

/** A row of the "What should it open?" list. */
sealed interface ChooserRow {
    data class Heading(val label: String) : ChooserRow
    data class Pick(val subject: PinSubject, val note: String? = null) : ChooserRow

    /** A file or folder, chosen in Filet's own browser. */
    data class Browse(val folder: Boolean, val label: String, val note: String) : ChooserRow
}

/**
 * The browse rows, as one list. The picker takes one file or the folder being stood in, never
 * either in a single pick, so there are two.
 */
val BROWSE_ROWS: List<ChooserRow.Browse> = listOf(
    ChooserRow.Browse(folder = false, label = "Choose a file…", note = "Picked in Filet's browser"),
    ChooserRow.Browse(folder = true, label = "Choose a folder…", note = "Open it, then use it"),
)

/** Everything a new shortcut could open, in the chooser's order: tabs by group, actions, scripts, files. */
fun chooserRows(scripts: List<Script>): List<ChooserRow> = buildList {
    for (g in dev.niccc2007.filet.browser.TabGroup.entries) {
        val tabs = TabTarget.entries.filter { it.group == g }
        if (tabs.isEmpty()) continue
        add(ChooserRow.Heading("Tabs · ${g.label}"))
        tabs.forEach { add(ChooserRow.Pick(PinSubject.Tab(it))) }
    }
    add(ChooserRow.Heading("Actions"))
    AppAction.LISTED.forEach { add(ChooserRow.Pick(PinSubject.Action(it), opensLine(PinSubject.Action(it)))) }
    if (scripts.isNotEmpty()) {
        add(ChooserRow.Heading("Scripts"))
        scripts.forEach { add(ChooserRow.Pick(PinSubject.Scripted(it))) }
    }
    add(ChooserRow.Heading("Files and folders"))
    addAll(BROWSE_ROWS)
}
