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

/** The options of Share › Incoming files, as they are now. */
data class IncomingSettings(
    /** The inbox stays open with Filet in the background. Off unless turned on. */
    val ready: Boolean = false,
    val ask: AskStyle = AskStyle.CARD,
    val oneFile: OneFileStyle = OneFileStyle.MINI,
)

/** Where a send that asks is shown. */
enum class AskSurface {
    /** Filet is on screen: its own dialogue, as before. */
    IN_APP,
    SIDE_CARD,
    MINI_CARD,
    NOTIFICATION,
}

/** The incoming rules, apart from Android so they can be tested. */
object IncomingRules {

    /**
     * Where a send of [files] files asks.
     *
     * Every push comes from a fast share, so the one-line card is for a push of one file and the
     * side card for several - the mini card has no room to show more than one, and a single file
     * gains nothing from the larger one. Without permission to draw over other apps the answer
     * is a notification whatever the setting says: a card that cannot be drawn is a send that
     * waits unseen, which is the failure this exists to end.
     */
    fun surface(inFront: Boolean, canOverlay: Boolean, s: IncomingSettings, files: Int): AskSurface = when {
        inFront -> AskSurface.IN_APP
        !canOverlay || s.ask == AskStyle.NOTICE -> AskSurface.NOTIFICATION
        files == 1 && s.oneFile == OneFileStyle.MINI -> AskSurface.MINI_CARD
        else -> AskSurface.SIDE_CARD
    }

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

    private fun read() = IncomingSettings(
        ready = prefs.getBool(KEY_READY, false),
        ask = AskStyle.of(prefs.getString(KEY_ASK)),
        oneFile = OneFileStyle.of(prefs.getString(KEY_ONE_FILE)),
    )

    private fun write(s: IncomingSettings) {
        prefs.putBool(KEY_READY, s.ready)
        prefs.putString(KEY_ASK, s.ask.stored)
        prefs.putString(KEY_ONE_FILE, s.oneFile.stored)
        _settings.value = s
    }

    companion object {
        private const val PREFIX = "nearby.incoming."
        const val KEY_READY = "nearby.incoming.ready"
        const val KEY_ASK = "nearby.incoming.ask"
        const val KEY_ONE_FILE = "nearby.incoming.oneFile"
    }
}
