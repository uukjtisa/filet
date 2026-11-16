package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a send from another Filet asks while Filet is not on screen. */
enum class AskStyle(val stored: String, val label: String) {
    /** A card sliding in from the right edge over the app in use. The default. */
    CARD("card", "Side card"),

    /** A dialog in the middle of the screen with the files previewed: seen and played before accepting. */
    DIALOG("dialog", "Full dialog"),

    /** A notification with Accept and Decline: no drawing over other apps at all. */
    NOTICE("notice", "Notification"),
    ;

    companion object {
        fun of(stored: String?): AskStyle = entries.firstOrNull { it.stored == stored } ?: CARD
    }
}

/** How a send of a single file asks: the one-line card, or the same as several files. */
enum class OneFileStyle(val stored: String, val label: String) {
    MINI("mini", "Mini card"),
    SAME("same", "Same as above"),
    ;

    companion object {
        fun of(stored: String?): OneFileStyle = entries.firstOrNull { it.stored == stored } ?: MINI
    }
}

/** How a file is viewed before it is accepted: over the whole screen, or in a window over the app. */
enum class ViewStyle(val stored: String, val label: String) {
    FULL("full", "Full screen"),
    FLOAT("float", "Floating window"),
    ;

    companion object {
        fun of(stored: String?): ViewStyle = entries.firstOrNull { it.stored == stored } ?: FULL
    }
}

/** The options of Share › Incoming files, as they are now. */
data class IncomingSettings(
    /** The inbox stays open with Filet in the background. Off unless turned on. */
    val ready: Boolean = false,
    val ask: AskStyle = AskStyle.CARD,
    val oneFile: OneFileStyle = OneFileStyle.MINI,
    /** Video and sound in the full dialog and the viewer start without sound. */
    val muted: Boolean = true,
    val view: ViewStyle = ViewStyle.FULL,
)

/** Where a send that asks is shown. */
enum class AskPlace {
    /** Filet is on screen: the card is drawn over Filet's own screen. */
    IN_FILET,

    /** Over whatever app is open, in a window of Filet's own. */
    OVER_APPS,

    /** A notification with Accept and Decline. */
    NOTIFICATION,
}

/** How a send that asks looks, wherever it is shown. */
enum class AskLook { CARD, MINI, DIALOG }

/** What the full dialog and the viewer can show of a file before it is accepted. */
enum class PreviewKind { PICTURE, VIDEO, SOUND, TEXT, NONE }

/** One line of a text preview, with the little of Markdown that changes how it reads. */
data class PreviewLine(val text: String, val style: Style) {
    enum class Style { HEADING, BULLET, PLAIN }
}

/** The incoming rules, apart from Android so they can be tested. */
object IncomingRules {

    /**
     * Where a send asks.
     *
     * Decision: one card, two places. Filet on screen used to ask with a dialogue of its own, a
     * second design for the same question that showed less - no previews, nothing to play. Now
     * the card is drawn over Filet's screen exactly as it is over another app's, and [look] is the
     * same answer for both.
     *
     * Without permission to draw over other apps the answer is a notification whatever the
     * setting says: a card that cannot be drawn is a send that waits unseen, which is the failure
     * this exists to end. Inside Filet nothing needs that permission, and Notification as the
     * setting means "do not draw over my apps" - it does not reach Filet's own screen.
     */
    fun place(inFront: Boolean, canOverlay: Boolean, s: IncomingSettings): AskPlace = when {
        inFront -> AskPlace.IN_FILET
        !canOverlay || s.ask == AskStyle.NOTICE -> AskPlace.NOTIFICATION
        else -> AskPlace.OVER_APPS
    }

    /**
     * How a send of [files] files looks, in either place.
     *
     * Every push comes from a fast share, so the one-line card is for a push of one file and the
     * side card for several - the mini card has no room to show more than one, and a single file
     * gains nothing from the larger one. Notification as the setting draws the side card inside
     * Filet (see [place]).
     */
    fun look(s: IncomingSettings, files: Int): AskLook = when {
        files == 1 && s.oneFile == OneFileStyle.MINI -> AskLook.MINI
        s.ask == AskStyle.DIALOG -> AskLook.DIALOG
        else -> AskLook.CARD
    }

    /**
     * What a file can be shown as before it is accepted. Pictures shown, video and sound played,
     * text read; anything else shows its kind. A folder is a zip made on the other phone and has
     * nothing to show until it arrives.
     */
    fun preview(name: String, dir: Boolean): PreviewKind {
        if (dir) return PreviewKind.NONE
        val ext = FileTypes.extensionOf(name)
        return when (FileTypes.kindOf(ext)) {
            TypeKind.IMAGE -> PreviewKind.PICTURE
            TypeKind.VIDEO -> PreviewKind.VIDEO
            TypeKind.AUDIO -> PreviewKind.SOUND
            TypeKind.CODE -> PreviewKind.TEXT
            TypeKind.DOC -> if (FileTypes.mime(ext)?.startsWith("text/") == true) PreviewKind.TEXT else PreviewKind.NONE
            else -> PreviewKind.NONE
        }
    }

    /**
     * A text preview as lines. For Markdown, a heading is drawn as one and a list item gets its
     * bullet; the rest of Markdown is left as typed, which still reads. Everything else is plain.
     * At most [max] lines: the preview is a look, the file is what Accept brings.
     */
    fun previewLines(text: String, markdown: Boolean, max: Int = 200): List<PreviewLine> =
        text.lineSequence().take(max).map { raw ->
            val line = raw.trimEnd()
            when {
                !markdown -> PreviewLine(line, PreviewLine.Style.PLAIN)
                HEADING.matches(line) -> PreviewLine(line.trimStart('#').trim(), PreviewLine.Style.HEADING)
                BULLET.matches(line) -> PreviewLine(line.trimStart().drop(1).trim(), PreviewLine.Style.BULLET)
                else -> PreviewLine(line, PreviewLine.Style.PLAIN)
            }
        }.toList()

    fun isMarkdown(name: String): Boolean = FileTypes.extensionOf(name).let { it == "md" || it == "markdown" }

    /** "2 of 3", for the dialog's stage. */
    fun pager(index: Int, count: Int): String = "${index + 1} of $count"

    /** The line under the file's name in the dialog: who, how big, what kind. */
    fun detail(from: String, f: PushFile, size: (Long) -> String): String = buildList {
        add(from)
        if (!f.dir && f.size > 0) add(size(f.size))
        add(if (f.dir) "folder, arrives as a zip" else kindName(f.name))
    }.joinToString(" · ")

    private fun kindName(name: String): String = when (preview(name, false)) {
        PreviewKind.PICTURE -> "picture"
        PreviewKind.VIDEO -> "video"
        PreviewKind.SOUND -> "sound"
        PreviewKind.TEXT -> if (isMarkdown(name)) "Markdown" else "text"
        PreviewKind.NONE -> FileTypes.extensionOf(name).uppercase().ifEmpty { "file" }
    }

    private val HEADING = Regex("^#{1,6}\\s+.*")
    private val BULLET = Regex("^\\s*[-*+]\\s+.*")

    /**
     * The card's one line about what is coming: the first file by name, the rest by kind, and the
     * total size. "IMG_1.jpg, a video and notes.md · 51 MB". Folders count by their zip.
     */
    fun summary(files: List<PushFile>, size: (Long) -> String): String {
        if (files.isEmpty()) return ""
        val total = files.filter { !it.dir }.sumOf { it.size.coerceAtLeast(0) }
        val names = files.map { nameOf(it) }
        val head = when (files.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${kindPhrase(files[1])}"
            3 -> "${names[0]}, ${kindPhrase(files[1])} and ${kindPhrase(files[2])}"
            else -> "${names[0]} and ${files.size - 1} more"
        }
        return if (total > 0) "$head · ${size(total)}" else head
    }

    /** What the card's heading says under the sender's name. */
    fun wants(files: Int): String = "wants to send you " + if (files == 1) "a file" else "$files files"

    /** The tiles on the side card: the first three files, the third standing for the rest when there are more. */
    fun tiles(files: Int): Int = minOf(files, 3)

    /** How many more a third tile stands for, or 0 when it is a file of its own. */
    fun moreOnLast(files: Int): Int = if (files > 3) files - 2 else 0

    /** The sender's initial, for the round badge: the first letter or digit of its name. */
    fun initial(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    private fun nameOf(f: PushFile) = f.name + if (f.dir) ".zip" else ""

    private fun kindPhrase(f: PushFile): String {
        if (f.dir) return "a folder"
        return when (kindOf(f.name)) {
            TypeKind.IMAGE -> "a picture"
            TypeKind.VIDEO -> "a video"
            TypeKind.AUDIO -> "a sound"
            else -> f.name
        }
    }

    /** Whether the sender can be asked for a thumbnail of [name]: a picture or a video. */
    fun hasThumb(name: String): Boolean = kindOf(name).let { it == TypeKind.IMAGE || it == TypeKind.VIDEO }

    fun isVideo(name: String): Boolean = kindOf(name) == TypeKind.VIDEO

    /** From the one table of types, never a list of its own. See FileTypes. */
    private fun kindOf(name: String): TypeKind? = FileTypes.kindOf(FileTypes.extensionOf(name))
}

/**
 * Share › Incoming files, kept with the other sharing settings and carried by Export and the
 * settings file. See ConfigKeys.
 */
class IncomingStore(private val prefs: Prefs) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<IncomingSettings> = _settings.asStateFlow()

    init {
        // The settings file and an import write the store underneath; the options follow them
        // while Filet is open, as the rest of the settings do. Held for the life of the process.
        prefs.onChange { key -> if (key == null || key.startsWith(PREFIX)) _settings.value = read() }
    }

    fun setReady(on: Boolean) = write(_settings.value.copy(ready = on))
    fun setAsk(a: AskStyle) = write(_settings.value.copy(ask = a))
    fun setOneFile(o: OneFileStyle) = write(_settings.value.copy(oneFile = o))
    fun setMuted(on: Boolean) = write(_settings.value.copy(muted = on))
    fun setView(v: ViewStyle) = write(_settings.value.copy(view = v))

    private fun read() = IncomingSettings(
        ready = prefs.getBool(KEY_READY, false),
        ask = AskStyle.of(prefs.getString(KEY_ASK)),
        oneFile = OneFileStyle.of(prefs.getString(KEY_ONE_FILE)),
        muted = prefs.getBool(KEY_MUTED, true),
        view = ViewStyle.of(prefs.getString(KEY_VIEW)),
    )

    private fun write(s: IncomingSettings) {
        prefs.putBool(KEY_READY, s.ready)
        prefs.putString(KEY_ASK, s.ask.stored)
        prefs.putString(KEY_ONE_FILE, s.oneFile.stored)
        prefs.putBool(KEY_MUTED, s.muted)
        prefs.putString(KEY_VIEW, s.view.stored)
        _settings.value = s
    }

    companion object {
        private const val PREFIX = "nearby.incoming."
        const val KEY_READY = "nearby.incoming.ready"
        const val KEY_ASK = "nearby.incoming.ask"
        const val KEY_ONE_FILE = "nearby.incoming.oneFile"
        const val KEY_MUTED = "nearby.incoming.muted"
        const val KEY_VIEW = "nearby.incoming.view"
    }
}
