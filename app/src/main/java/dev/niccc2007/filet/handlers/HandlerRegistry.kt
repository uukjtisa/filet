package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

/**
 * What opens what, inside Filet.
 *
 * PLAN.md L2 names two tables that are constantly confused and are not the same thing:
 * **inbound** routing (other apps to Filet) is manifest work, and **internal** routing (tap a
 * file, something opens) is this registry. Only the second one is a user preference.
 *
 * - single tap -> the registered handler
 * - double tap -> the system "Open with" chooser
 * - long press -> selection, and the action sheet
 */
enum class HandlerId(val label: String) {
    TEXT("Code editor"),
    IMAGE("Image viewer"),
    MEDIA("Media player"),
    HEX("Hex viewer"),
    ARCHIVE("Open as folder"),
    APK("APK inspector"),
    MANIFEST("Manifest editor"),
    EXTERNAL("Another app"),
}

class HandlerRegistry(private val prefs: Prefs) {

    private val _overrides = MutableStateFlow(load())
    val overrides: StateFlow<Map<String, HandlerId>> = _overrides.asStateFlow()

    /**
     * Which installed app opens each extension, when the handler is [HandlerId.EXTERNAL].
     *
     * Separate from [overrides] because it answers a different question. The handler says
     * *Filet hands this off*; this says *to whom*. An extension can have the second without
     * the first (Filet's own viewer is still the default, but a chooser answered once is
     * remembered for when you ask again), and it keeps the answer if the handler is switched
     * back and forth.
     */
    private val _externals = MutableStateFlow(loadExternals())
    val externals: StateFlow<Map<String, ExternalApp>> = _externals.asStateFlow()

    /** The handler a single tap should use. */
    fun handlerFor(node: VNode): HandlerId {
        val ext = node.extension.lowercase(Locale.US)
        _overrides.value[ext]?.let { return it }
        return defaultFor(node)
    }

    fun defaultFor(node: VNode): HandlerId = when (FileKind.of(node)) {
        FileKind.FOLDER -> HandlerId.ARCHIVE
        FileKind.APK -> HandlerId.APK
        FileKind.ARCHIVE -> HandlerId.ARCHIVE
        FileKind.IMAGE -> HandlerId.IMAGE
        FileKind.VIDEO, FileKind.AUDIO -> HandlerId.MEDIA
        FileKind.CODE -> HandlerId.TEXT
        FileKind.DOC -> if (node.extension == "pdf") HandlerId.EXTERNAL else HandlerId.TEXT
        FileKind.DEX -> HandlerId.APK
        FileKind.BINARY -> HandlerId.HEX
        FileKind.OTHER -> if (node.extension.isEmpty()) HandlerId.TEXT else HandlerId.EXTERNAL
    }

    /** Every handler that can actually open this file, for the chooser sheet. */
    fun candidatesFor(node: VNode): List<HandlerId> {
        val kind = FileKind.of(node)
        val out = LinkedHashSet<HandlerId>()
        out += defaultFor(node)
        if (kind != FileKind.FOLDER) {
            out += HandlerId.TEXT
            out += HandlerId.HEX
            out += HandlerId.EXTERNAL
        }
        if (kind == FileKind.IMAGE) out += HandlerId.IMAGE
        if (kind == FileKind.VIDEO || kind == FileKind.AUDIO) out += HandlerId.MEDIA
        return out.toList()
    }

    // ── extension-first queries, for the Settings list ──
    //
    // The routing functions above answer "what opens THIS file", which is the right question
    // at a tap. Settings asks a different one - "what opens .mkv" - with no file in hand, so
    // these answer it from a probe node rather than making the caller invent one.

    /** What a single tap on `name.<ext>` would open, override or not. */
    fun handlerForExtension(ext: String): HandlerId = handlerFor(probe(ext))

    /** What Filet would pick for `.<ext>` with no user preference in the way. */
    fun builtInFor(ext: String): HandlerId = defaultFor(probe(ext))

    /** Every handler offered for `.<ext>`, in the order the chooser shows them. */
    fun candidatesForExtension(ext: String): List<HandlerId> = candidatesFor(probe(ext))

    /** True when the user has set this one rather than inheriting Filet's pick. */
    fun isOverridden(ext: String): Boolean = ext.lowercase(Locale.US) in _overrides.value

    /** Forget every override at once. */
    fun clearAll() {
        _overrides.value = emptyMap()
        _externals.value = emptyMap()
        save()
    }

    private fun probe(ext: String) = VNode(
        path = dev.niccc2007.filet.vfs.VPath("local", "/probe." + ext.lowercase(Locale.US).trimStart('.')),
        isDir = false, size = 0, mtime = 0,
    )

    // ── which app, not just "an app" ──

    /** The app remembered for `.<ext>`, or null when Filet has never been told. */
    fun externalFor(extension: String): ExternalApp? =
        _externals.value[extension.lowercase(Locale.US)]

    /**
     * Remember an app for this extension.
     *
     * @param alsoRoute true when the user picked it as the default opener rather than for one
     *   file. Then the handler flips to EXTERNAL as well, so a single tap goes straight there.
     *   False when they only answered "open this one with", which should be remembered as the
     *   suggestion without silently changing what a tap does.
     */
    fun setExternal(extension: String, app: ExternalApp, alsoRoute: Boolean) {
        val ext = extension.lowercase(Locale.US)
        if (ext.isEmpty()) return
        _externals.value = _externals.value + (ext to app)
        if (alsoRoute) _overrides.value = _overrides.value + (ext to HandlerId.EXTERNAL)
        save()
    }

    fun clearExternal(extension: String) {
        _externals.value = _externals.value - extension.lowercase(Locale.US)
        save()
    }

    /** "Always" in the chooser writes here, and the next single tap skips the sheet. */
    fun setDefault(extension: String, handler: HandlerId) {
        val ext = extension.lowercase(Locale.US)
        if (ext.isEmpty()) return
        _overrides.value = _overrides.value + (ext to handler)
        save()
    }

    fun clearDefault(extension: String) {
        val ext = extension.lowercase(Locale.US)
        _overrides.value = _overrides.value - ext
        // The remembered app goes with it. Leaving it behind means "reset to default" resets
        // what the row SAYS and not what the next tap DOES, which is the worst of both.
        _externals.value = _externals.value - ext
        save()
    }

    private fun load(): Map<String, HandlerId> {
        val raw = prefs.getString(KEY) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                val v = runCatching { HandlerId.valueOf(o.getString(k)) }.getOrNull()
                if (v == null) null else k to v
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun loadExternals(): Map<String, ExternalApp> {
        val raw = prefs.getString(KEY_EXTERNAL) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                val e = o.optJSONObject(k) ?: return@mapNotNull null
                val pkg = e.optString("pkg")
                val act = e.optString("act")
                if (pkg.isEmpty() || act.isEmpty()) null
                else k to ExternalApp(pkg, act, e.optString("label").ifEmpty { pkg })
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun save() {
        val o = JSONObject()
        _overrides.value.forEach { (k, v) -> o.put(k, v.name) }
        prefs.putString(KEY, o.toString())

        val e = JSONObject()
        _externals.value.forEach { (k, v) ->
            e.put(k, JSONObject().put("pkg", v.packageName).put("act", v.activity).put("label", v.label))
        }
        prefs.putString(KEY_EXTERNAL, e.toString())
    }

    private companion object {
        const val KEY = "handlers.v1"
        const val KEY_EXTERNAL = "handlers.external.v1"
    }
}

/** MIME guess from the extension, for outgoing intents. Never used for routing decisions. */
fun mimeOf(node: VNode): String = mimeForExtension(node.extension)

/**
 * The same guess, from an extension alone.
 *
 * Settings asks "which app opens .mkv" with no file in hand, and intent resolution keys on
 * the mime type, so the question has to be answerable without one.
 *
 * Three steps, in this order:
 *
 * 1. [knownMime], the table below. Explicit, pure, and tested - and it is deliberately not
 *    short, because the types people actually route by hand are the Office ones.
 * 2. Android's own `MimeTypeMap`, which knows several hundred more.
 * 3. [ANY_TYPE], the wildcard, meaning "no idea".
 *
 * Step 1 existed alone before, and the hole it left was not cosmetic: `.pptx` fell through to
 * the wildcard, and querying the package manager with that matches every app carrying a
 * wildcard VIEW filter. The "apps that handle this type" list for a PowerPoint deck came back as
 * Certificate Installer, HTML Viewer and Manage SIM contacts.
 */
fun mimeForExtension(extension: String): String {
    val ext = extension.lowercase(Locale.US).trimStart('.')
    knownMime(ext)?.let { return it }
    val fromAndroid = runCatching {
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }.getOrNull()
    return fromAndroid?.takeIf { it.isNotBlank() } ?: ANY_TYPE
}

/** "No idea what this is." A query with it matches wildcard filters and nothing meaningful. */
const val ANY_TYPE = "*/*"

/**
 * The types Filet names itself, rather than asking the platform.
 *
 * Pure, so `MimeTableTest` can hold the Office entries in place: those are the ones somebody
 * sets by hand, and they are the ones whose absence was invisible.
 */
fun knownMime(extension: String): String? = when (extension.lowercase(Locale.US).trimStart('.')) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "3gp" -> "video/3gpp"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "opus", "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "txt", "log", "md" -> "text/plain"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "xml" -> "text/xml"

    // Office. The reason this function grew: every one of these used to be `*/*`.
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "rtf" -> "application/rtf"
    "csv" -> "text/csv"
    "epub" -> "application/epub+zip"

    // More of what a file manager meets every day.
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    "avif" -> "image/avif"
    "ico" -> "image/x-icon"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "wmv" -> "video/x-ms-wmv"
    "ts" -> "video/mp2t"
    "flv" -> "video/x-flv"
    "aac" -> "audio/aac"
    "wma" -> "audio/x-ms-wma"
    "amr" -> "audio/amr"
    "mid", "midi" -> "audio/midi"
    "jar" -> "application/java-archive"
    // Not a real registered type, and deliberately so: a dex is a zip-ish blob nothing else
    // opens, and naming it keeps it out of the wildcard branch where every app claims it.
    "dex", "odex", "vdex" -> "application/octet-stream"
    "7z" -> "application/x-7z-compressed"
    "rar" -> "application/vnd.rar"
    "tar" -> "application/x-tar"
    "gz", "tgz" -> "application/gzip"
    "torrent" -> "application/x-bittorrent"
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    "css" -> "text/css"
    "js" -> "text/javascript"
    "yml", "yaml", "toml", "ini", "cfg", "conf", "properties" -> "text/plain"
    "kt", "kts", "java", "py", "lua", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php",
    "sh", "bash", "smali", "gradle", "sql" -> "text/plain"

    else -> null
}
