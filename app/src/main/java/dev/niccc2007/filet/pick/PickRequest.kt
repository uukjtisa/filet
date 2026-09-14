package dev.niccc2007.filet.pick

/**
 * What another app asked for when it opened Filet as a file picker.
 *
 * Parsed from primitives rather than from an `Intent`, so it can be tested on the JVM - the
 * decisions here are all of the "wrong in a way nobody notices" kind, and every one of them is
 * about a field that is ABSENT far more often than it is present. A caller that sends
 * `ACTION_GET_CONTENT` with `type = "&#42;/&#42;"` and nothing else is the common case, so the defaults
 * are the part that has to be right.
 *
 * @param mimeTypes what the caller will accept. Empty means anything.
 * @param allowMultiple the caller set `EXTRA_ALLOW_MULTIPLE` and can read a `ClipData` back.
 * @param localOnly the caller set `EXTRA_LOCAL_ONLY` - it wants a file on this device rather
 *   than something a cloud provider would stream. Filet is local by nature, so this changes
 *   nothing about what is offered; it is carried so the picker never claims otherwise.
 * @param openable the caller carried `CATEGORY_OPENABLE`, meaning it will call
 *   `openInputStream` on whatever comes back. A folder is not an answer to that.
 */
data class PickRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val localOnly: Boolean,
    val openable: Boolean,
) {

    /** Anything goes, so the browser shows everything without dimming half of it. */
    val acceptsAnything: Boolean
        get() = mimeTypes.isEmpty() || mimeTypes.any { it == MimeMatch.ANY_TYPE }

    /**
     * A short phrase for the bar: "images", "PDFs", "any file".
     *
     * Written from the types the caller asked for rather than from a label the caller supplied,
     * because callers supply nothing and a picker with no idea what it is for is the thing that
     * makes somebody tap the wrong file.
     */
    val what: String
        get() {
            if (acceptsAnything) return "any file"
            val families = mimeTypes.map { it.substringBefore('/') }.distinct()
            if (families.size == 1 && mimeTypes.all { it.endsWith("/*") }) {
                return when (families[0]) {
                    "image" -> "an image"
                    "video" -> "a video"
                    "audio" -> "audio"
                    "text" -> "a text file"
                    else -> "a ${families[0]} file"
                }
            }
            val exact = mimeTypes.filterNot { it.endsWith("/*") }
            if (exact.size == 1) return shortName(exact[0])
            return "one of ${mimeTypes.size} file types"
        }

    private fun shortName(mime: String): String = when (mime) {
        "application/pdf" -> "a PDF"
        "application/zip" -> "a zip"
        "application/vnd.android.package-archive" -> "an APK"
        else -> "a ${mime.substringAfterLast('/')} file"
    }

    companion object {
        /**
         * Build from the pieces of an `Intent`.
         *
         * @param type the intent's own `type`. Null is treated as `&#42;/&#42;`: a caller that names no
         *   type wants anything, and refusing everything would be the opposite of what it asked.
         * @param extraMimeTypes `EXTRA_MIME_TYPES`, which OVERRIDES `type` when present. That is
         *   the platform's own rule and getting it backwards silently narrows every multi-type
         *   caller to the single placeholder type it also sends.
         */
        fun of(
            type: String?,
            extraMimeTypes: Array<String>?,
            allowMultiple: Boolean,
            localOnly: Boolean,
            openable: Boolean,
        ): PickRequest {
            val extras = extraMimeTypes?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val declared = type?.trim().orEmpty()
            val types = when {
                extras.isNotEmpty() -> extras
                declared.isNotEmpty() -> listOf(declared)
                else -> emptyList()
            }
            return PickRequest(
                mimeTypes = types.map { it.lowercase() }.distinct(),
                allowMultiple = allowMultiple,
                localOnly = localOnly,
                openable = openable,
            )
        }
    }
}

/**
 * Does this file match what the caller asked for?
 *
 * Kept apart from [PickRequest] because it is the half that runs per row, and because its
 * awkward case deserves to be stated on its own: **a file whose type cannot be named is
 * accepted whenever the request is not exact.**
 *
 * That is deliberate, and it is the difference between a usable picker and a decorative one. A
 * file manager's whole population is files the system has no mime for - `.7z`, `.ino`, `.kt`, a
 * download with no extension - and a caller asking for `&#42;/&#42;` that is then shown an empty folder
 * has been failed by the picker, not by the files. An EXACT request (`application/pdf`) still
 * refuses them, because there the caller has said precisely what it can open.
 */
object MimeMatch {

    /**
     * The all-types wildcard.
     *
     * A constant rather than a literal in two places, and worth a sentence: written out inside
     * a KDoc block it would END the comment, because the middle two characters close one. That
     * is why the prose here spells it with entities - it renders as the wildcard and does not
     * cut the comment in half, which is exactly what happened the first time.
     */
    const val ANY_TYPE = "*" + "/" + "*"

    /**
     * @param mime the file's type as the app resolved it, or null when nothing could name it.
     */
    fun accepts(request: PickRequest, mime: String?): Boolean {
        if (request.acceptsAnything) return true
        val actual = mime?.trim()?.lowercase()
        if (actual.isNullOrEmpty()) {
            // Unknown type: allowed through a family wildcard, refused by an exact request.
            return request.mimeTypes.any { it.endsWith("/*") }
        }
        return request.mimeTypes.any { matches(it, actual) }
    }

    /** One pattern against one type. `image/&#42;` matches `image/png`; `&#42;/&#42;` matches everything. */
    fun matches(pattern: String, mime: String): Boolean {
        if (pattern == ANY_TYPE || pattern == "*") return true
        if (pattern == mime) return true
        if (!pattern.endsWith("/*")) return false
        val family = pattern.dropLast(2)
        return mime.startsWith("$family/")
    }
}
