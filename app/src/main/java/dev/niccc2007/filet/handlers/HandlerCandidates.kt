package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.browser.FileKind

/**
 * Which of Filet's own viewers are offered for a file, and which are one tap further away.
 *
 * Bug identified: the list was built from the extension and offered almost nothing. Anything
 * the tables did not recognise got the code editor, the hex viewer and "another app" - so a
 * `.mcaddon`, which is a zip, could not be opened with the archive viewer at all. The archive
 * viewer was never in the list unless it was already the default, and neither was the APK
 * inspector.
 *
 * That is backwards. "Open with" exists precisely for the case where the extension is wrong or
 * unknown, so it is the one list that must not be decided by the extension.
 *
 * ## Two tiers rather than one long list
 *
 * Offering all six viewers for every file flatly would fix the report and make the common case
 * worse - a photo would offer the archive viewer as prominently as the image viewer. So the
 * likely ones are shown and the rest sit behind a reveal, which is the same shape the external
 * app picker already uses and for the same reason: the app's guess goes first, and the person
 * holding the phone can always overrule it.
 *
 * Nothing in the second tier is a trap. A viewer handed a file it cannot read says so and
 * closes, which is a far better outcome than a file with no way to open it at all.
 */
object HandlerCandidates {

    /**
     * @param likely what to show straight away, best guess first.
     * @param rest what the reveal shows. Never contains anything already in [likely].
     */
    data class Offer(val likely: List<HandlerId>, val rest: List<HandlerId>) {
        /** Everything, in order. For callers with no room for two tiers. */
        val all: List<HandlerId> get() = likely + rest
    }

    /** Every viewer Filet has, in the order they are worth trying on an unknown file. */
    private val EVERY = listOf(
        HandlerId.TEXT,
        HandlerId.ARCHIVE,
        HandlerId.IMAGE,
        HandlerId.MEDIA,
        HandlerId.APK,
        HandlerId.HEX,
    )

    /**
     * @param kind what the file looks like from its name.
     * @param default the handler a plain tap would use.
     */
    fun offer(kind: FileKind, default: HandlerId): Offer {
        // A folder opens as a folder. Offering the hex viewer for a directory is a control
        // that cannot act.
        if (kind == FileKind.FOLDER) return Offer(listOf(default), emptyList())

        val likely = LinkedHashSet<HandlerId>()
        // What a tap would do, because it is the app's own best guess.
        likely += default
        // Then whatever the name points at.
        likely += likelyFor(kind)
        // Leaving Filet is always a reasonable answer, so it stays in the first tier.
        likely += HandlerId.EXTERNAL

        // Everything else, revealed on request. This is the part that was missing entirely.
        val rest = EVERY.filterNot { it in likely }
        return Offer(likely.toList(), rest)
    }

    /** Everything, flat. Kept for callers that cannot show two tiers. */
    fun forKind(kind: FileKind, default: HandlerId): List<HandlerId> = offer(kind, default).all

    /** The viewers the name points at. */
    private fun likelyFor(kind: FileKind): List<HandlerId> = when (kind) {
        FileKind.IMAGE -> listOf(HandlerId.IMAGE)
        FileKind.VIDEO, FileKind.AUDIO -> listOf(HandlerId.MEDIA)
        FileKind.ARCHIVE -> listOf(HandlerId.ARCHIVE)
        FileKind.APK -> listOf(HandlerId.APK, HandlerId.ARCHIVE)
        FileKind.DEX -> listOf(HandlerId.HEX)
        FileKind.CODE, FileKind.DOC -> listOf(HandlerId.TEXT)
        // A blob could be anything, and an archive with the wrong name lands here - which is
        // the case that started this.
        FileKind.BINARY, FileKind.OTHER -> listOf(HandlerId.ARCHIVE, HandlerId.TEXT, HandlerId.HEX)
        FileKind.FOLDER -> emptyList()
    }
}
