package dev.niccc2007.filet.data

/**
 * The name a file shared in from another app is kept under. Pure, because it decides what the
 * file is called everywhere after: on the Share page, in a fast share, and on the phone that
 * receives it.
 *
 * Bug identified here: a file shared in from another app was named after the last piece of its
 * content link. A gallery's links end in the picture's row number -
 * `content://media/external/images/media/14536` - so a photo passed on with fast share arrived on
 * the other phone as `14536`, with no extension, listed and downloaded under that name and opened
 * as nothing until it was renamed by hand. The name is now the one the sending app gives for the
 * file, and a name without an extension gets the one its type implies.
 */
object IncomingName {

    /**
     * The name to keep: the sending app's [display] name, else the link's [lastSegment], with an
     * extension from [mime] (through [extensionFor]) when the name has none. Never a path: a slash
     * cannot split it and `.` or `..` cannot step out of the folder it is kept in.
     */
    fun of(display: String?, lastSegment: String?, mime: String?, extensionFor: (String) -> String?): String {
        val raw = display?.trim()?.takeIf { it.isNotEmpty() }
            ?: lastSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        val base = raw?.replace('/', '_')?.replace('\\', '_')?.replace('\u0000', ' ')
            ?.takeIf { it != "." && it != ".." }
            ?: "shared"
        if (hasExtension(base)) return base
        val ext = mime?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { '/' in it && !it.endsWith("/*") }
            ?.let(extensionFor)?.trim()?.removePrefix(".")
            ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            ?: return base
        return "$base.$ext"
    }

    /** Whether [name] ends in an extension: a dot after the first character, then up to eight letters or digits. */
    fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1)
        return ext.length <= 8 && ext.all(Char::isLetterOrDigit)
    }

    /**
     * [name], or `name (2).ext`, `name (3).ext` and on: the first that [taken] says is free.
     * Two apps can share two different `image.jpg`s, and the second must not overwrite the first
     * while the first is still being shared.
     */
    fun free(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && hasExtension(name) } ?: name.length
        var i = 2
        while (true) {
            val next = name.substring(0, dot) + " ($i)" + name.substring(dot)
            if (!taken(next)) return next
            i++
        }
    }
}

/** The name [uri] is kept under when it is copied in from another app. See [IncomingName.of]. */
fun incomingNameOf(resolver: android.content.ContentResolver, uri: android.net.Uri): String {
    val display = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    val mime = runCatching { resolver.getType(uri) }.getOrNull()
    return IncomingName.of(display, uri.lastPathSegment, mime) {
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
    }
}
