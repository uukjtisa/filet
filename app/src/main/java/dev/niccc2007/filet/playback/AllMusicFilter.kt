package dev.niccc2007.filet.playback

/**
 * What qualifies for the All Music list.
 *
 * The decision only - which folders, which lengths - with no storage API in it. The `WHERE`
 * these turn into is assembled in `CategoryQuery.audioSelection`, which is the layer that owns
 * MediaStore; R3 keeps query building and query decisions apart on purpose, and a filter that
 * imported `MediaStore` to name two columns would be the thin end of that.
 *
 * ## Why a list of every audio file on the device was the wrong list
 *
 * "All Music" was literally that: every row MediaStore classes as audio. On a real phone that
 * is notification tones, keyboard clicks, game effects, WhatsApp voice notes and the three
 * seconds of silence some apps ship - thousands of entries, none of them music, none of them
 * anything anybody would play. The list was technically correct and useless.
 *
 * Two filters fix it, and they are different kinds of answer:
 *
 *  - **Where it is.** Music arrives in a few folders and system sounds arrive in others, so the
 *    folder is the strongest signal there is and it costs one `LIKE` per folder.
 *  - **How long it is.** A notification tone is two seconds and a podcast is two hours; neither
 *    is what this list is for. A floor and a ceiling cut both without naming a single file.
 *
 * Both are settings rather than constants, because the right answer is per-phone: somebody who
 * keeps albums in `Recordings` or listens to half-hour mixes is not wrong, they just do not
 * match the default.
 *
 * Pure and tested, because it builds SQL out of paths - which are user data - and because a
 * filter that is subtly too strict presents as "my music is missing", which is indistinguishable
 * from the library being broken.
 */
object AllMusicFilter {

    /**
     * The folders scanned by default, relative to the primary volume.
     *
     * `Music` is where Android itself puts music. `Download` is where most of it actually
     * arrives. `Trawl/Audio` is where Filet's companion downloader writes, which is the one
     * folder on this phone that is certainly music and certainly not a system sound.
     */
    val DEFAULT_FOLDERS: List<String> = listOf("Music", "Download", "Trawl/Audio")

    /** A notification tone is seconds; a track is not. */
    const val DEFAULT_MIN_MS = 60_000L

    /** And a podcast, an audiobook or a DJ set is not what this list is for. */
    const val DEFAULT_MAX_MS = 600_000L

    /** The bounds the controls may be dragged between. */
    const val FLOOR_MS = 0L
    const val CEILING_MS = 3_600_000L

    /**
     * Absolute folder prefixes for [folders], resolved against [primary].
     *
     * An entry that is already absolute is kept as it is, so a folder on an SD card can be added
     * by its own path; anything else is taken as relative to the primary volume, which is what
     * somebody typing "Podcasts" means. Blank entries and a bare `/` are dropped rather than
     * turned into "everything".
     */
    fun prefixes(folders: Collection<String>, primary: String): List<String> =
        folders.asSequence()
            // TRAILING slashes only. Trimming both ends was a bug: it stripped the leading
            // slash off an absolute path before the test below could see it, so every folder
            // on an SD card was quietly re-rooted onto internal storage and matched nothing.
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() && it != "/" }
            .map { if (it.startsWith("/")) it else primary.trimEnd('/') + "/" + it }
            .distinct()
            .toList()

    /** Escape the wildcards LIKE treats specially, so a path matches itself and nothing more. */
    internal fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** `1:00`, for the controls. */
    fun clock(ms: Long): String {
        if (ms <= 0L) return "any"
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
