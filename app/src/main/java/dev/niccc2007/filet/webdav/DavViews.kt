package dev.niccc2007.filet.webdav

import dev.niccc2007.filet.vfs.VPath

/**
 * Flat, indexed views of the phone, served beside the real tree.
 *
 * Navigating a phone from Windows Explorer is genuinely painful, and not because WebDAV is
 * slow. Explorer gives you a tree and nothing else: no search box that reaches the server, no
 * filter, no sort that means anything across folders. Finding one photo means opening DCIM,
 * then Camera, then scrolling a thousand files whose names are timestamps.
 *
 * The index already knows where everything is. So it is exposed as folders - `Recent`,
 * `Images`, `Videos`, `Documents` - each one a flat listing of real files pulled from wherever
 * they actually live. Opening one opens the real file. It is a rendering of the index as a
 * path, which is the only shape Explorer can navigate.
 *
 * Two decisions here are real and both have tests:
 *
 *  - **A flat view collides names.** Three folders each holding `notes.txt` become one folder
 *    holding `notes.txt` three times, and Explorer shows one of them. Collisions are
 *    disambiguated with the containing folder, and only the ones that actually collide - so
 *    the common case keeps the name the file really has.
 *  - **A view is capped.** A flat `Images` on a phone with forty thousand photos is a PROPFIND
 *    that times out and an Explorer window that hangs, which reads as the drive being broken.
 */
object DavViews {

    /** The collection the views live under, so they cannot shadow a real folder. */
    const val ROOT = "~Find"

    /** How many entries one view will serve. */
    const val LIMIT = 500

    enum class Kind(val label: String) {
        RECENT("Recent"),
        IMAGES("Images"),
        VIDEOS("Videos"),
        AUDIO("Audio"),
        DOCUMENTS("Documents"),
        ARCHIVES("Archives"),
        APPS("Apps"),
        LARGE("Large files"),
        ;

        /** The folder name as it appears over WebDAV. */
        val folder: String get() = label
    }

    /** One file in a view. */
    data class Entry(val path: VPath, val name: String, val size: Long, val mtime: Long)

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "flv", "ts")
    private val SOUND = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr")
    private val DOC = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "ods", "csv", "epub",
    )
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst")
    private val APP = setOf("apk", "xapk", "apkm", "apks", "aab")

    /** Whether [name] belongs in [kind], by extension. Size and time views answer elsewhere. */
    fun matches(kind: Kind, name: String, size: Long): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (kind) {
            Kind.IMAGES -> ext in IMAGE
            Kind.VIDEOS -> ext in VIDEO
            Kind.AUDIO -> ext in SOUND
            Kind.DOCUMENTS -> ext in DOC
            Kind.ARCHIVES -> ext in ARCHIVE
            Kind.APPS -> ext in APP
            // Not an extension question: everything is a candidate and the ordering decides.
            Kind.RECENT -> true
            Kind.LARGE -> size >= LARGE_THRESHOLD
        }
    }

    /** Newest first for [Kind.RECENT], biggest first for [Kind.LARGE], else newest first. */
    fun order(kind: Kind, entries: List<Entry>): List<Entry> = when (kind) {
        Kind.LARGE -> entries.sortedByDescending { it.size }
        else -> entries.sortedByDescending { it.mtime }
    }

    /** Everything that belongs in [kind], ordered and capped. */
    fun build(kind: Kind, all: List<Entry>): List<Entry> =
        disambiguate(order(kind, all.filter { matches(kind, it.name, it.size) }).take(LIMIT))

    /**
     * Make every name in a flat listing unique.
     *
     * Only the ones that actually collide are touched. Renaming everything defensively would
     * mean every file in every view carries a suffix it does not need, and the name a file has
     * is the thing being looked for.
     *
     * The suffix is the containing folder, because that is the information the flattening threw
     * away and the thing that tells two identically named files apart. A second collision -
     * same name, same folder name, different path - falls back to a counter.
     */
    fun disambiguate(entries: List<Entry>): List<Entry> {
        val counts = HashMap<String, Int>()
        for (e in entries) counts[e.name] = (counts[e.name] ?: 0) + 1

        val used = HashSet<String>()
        val out = ArrayList<Entry>(entries.size)
        for (e in entries) {
            if ((counts[e.name] ?: 0) <= 1) {
                used.add(e.name)
                out.add(e)
                continue
            }
            val folder = e.path.parent?.name.orEmpty().ifBlank { "root" }
            val base = e.name.substringBeforeLast('.', e.name)
            val ext = e.name.substringAfterLast('.', "")
            val dot = if (ext.isEmpty()) "" else ".$ext"
            var candidate = "$base ($folder)$dot"
            var n = 2
            while (!used.add(candidate)) {
                candidate = "$base ($folder $n)$dot"
                n++
            }
            out.add(e.copy(name = candidate))
        }
        return out
    }

    /**
     * Read a path under the views root.
     *
     * @return the view, or null when [rel] is not inside the views at all. A path one level
     *   deeper than a view is a file inside it, which the caller resolves by name.
     */
    fun viewOf(rel: String): Kind? {
        val parts = rel.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() != ROOT || parts.size < 2) return null
        return Kind.entries.firstOrNull { it.folder.equals(parts[1], ignoreCase = true) }
    }

    /** Whether [rel] is the views root itself, which lists the views as folders. */
    fun isRoot(rel: String): Boolean =
        rel.split('/').filter { it.isNotEmpty() }.let { it.size == 1 && it[0] == ROOT }

    /** The file name inside a view, or null when the path is the view folder itself. */
    fun fileIn(rel: String): String? {
        val parts = rel.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 3 && parts[0] == ROOT) parts.drop(2).joinToString("/") else null
    }

    /** Anything at or over this is "large" - roughly, the things worth finding to delete. */
    const val LARGE_THRESHOLD = 100L * 1024 * 1024
}
