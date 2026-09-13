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
        save()
    }

    private fun probe(ext: String) = VNode(
        path = dev.niccc2007.filet.vfs.VPath("local", "/probe." + ext.lowercase(Locale.US).trimStart('.')),
        isDir = false, size = 0, mtime = 0,
    )

    /** "Always" in the chooser writes here, and the next single tap skips the sheet. */
    fun setDefault(extension: String, handler: HandlerId) {
        val ext = extension.lowercase(Locale.US)
        if (ext.isEmpty()) return
        _overrides.value = _overrides.value + (ext to handler)
        save()
    }

    fun clearDefault(extension: String) {
        _overrides.value = _overrides.value - extension.lowercase(Locale.US)
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

    private fun save() {
        val o = JSONObject()
        _overrides.value.forEach { (k, v) -> o.put(k, v.name) }
        prefs.putString(KEY, o.toString())
    }

    private companion object { const val KEY = "handlers.v1" }
}

/** MIME guess from the extension, for outgoing intents. Never used for routing decisions. */
fun mimeOf(node: VNode): String = when (node.extension.lowercase(Locale.US)) {
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
    else -> "*/*"
}
