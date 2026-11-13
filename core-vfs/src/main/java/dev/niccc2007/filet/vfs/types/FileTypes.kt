package dev.niccc2007.filet.vfs.types

import dev.niccc2007.filet.vfs.provider.Archives
import java.util.Locale

/** What kind of thing an extension names. */
enum class TypeKind { IMAGE, VIDEO, AUDIO, CODE, DOC, BINARY, ARCHIVE, APK, DEX, OTHER }

/**
 * For [FileType.platformFrom]: every API level Filet runs on.
 *
 * Zero rather than one, because a JVM unit test runs against the stub `android.jar`, whose
 * `Build.VERSION.SDK_INT` is 0. At 1, every format every phone decodes would read as
 * undecodable in a test, and a routing test would pass or fail for the wrong reason.
 */
const val ANY_API = 0

/**
 * One extension, declared once.
 *
 * @param mime the type Filet names itself, or null to leave it to Android's own table.
 * @param platformFrom the lowest API level whose own decoders read it, or null when Android
 *   cannot decode it at all. Only meaningful for pictures, video and sound.
 * @param family the spellings one type goes by: a `.jpeg` is a JPEG as much as a `.jpg` is.
 * @param jpeg carries EXIF orientation, which a JPEG decode does not apply on its own.
 * @param animated can hold more than one frame.
 * @param lossless an edited copy is written back as PNG rather than recompressed as JPEG.
 * @param vector drawn from a description rather than decoded to pixels, so no bitmap decoder
 *   makes a thumbnail of it.
 * @param coverArt a sound container that can carry an embedded picture.
 * @param compressed already within a percent of its entropy, so a zip stores it rather than
 *   spending minutes deflating it for nothing.
 * @param galleryLists a gallery or music app would be expected to list it.
 * @param icon which file-type icon draws it where it has no picture of its own. Most follow
 *   their kind; the ones that do not (a PDF, a sheet, a web page, a shell script) are named
 *   where they are declared, so the icon cannot drift from the type the way a second list would.
 */
data class FileType(
    val ext: String,
    val kind: TypeKind,
    val mime: String? = null,
    val platformFrom: Int? = ANY_API,
    val family: String? = null,
    val jpeg: Boolean = false,
    val animated: Boolean = false,
    val lossless: Boolean = false,
    val vector: Boolean = false,
    val coverArt: Boolean = false,
    val compressed: Boolean = false,
    val galleryLists: Boolean =
        (kind == TypeKind.IMAGE || kind == TypeKind.VIDEO || kind == TypeKind.AUDIO) && platformFrom != null,
    val icon: String = when (kind) {
        TypeKind.IMAGE -> "image"
        TypeKind.VIDEO -> "video"
        TypeKind.AUDIO -> "audio"
        TypeKind.CODE -> "source"
        TypeKind.DOC -> "text"
        TypeKind.BINARY -> "binary"
        TypeKind.ARCHIVE -> "archive"
        TypeKind.APK -> "package"
        TypeKind.DEX -> "dex"
        TypeKind.OTHER -> "other"
    },
)

/**
 * Every file type Filet knows, in one table.
 *
 * Bug identified here: the extensions were listed separately in more than ten places - the
 * file kind, thumbnails, the gallery sweep, the MIME table, picture rotation, the picture
 * editor, the save-name check, three MIME maps of their own and two "already compressed"
 * lists - and the lists disagreed. `.jfif` was a JPEG to the rotation code and to the
 * thumbnailer's orientation check, and absent from the thumbnailer's picture list and from the
 * file kind, so a JFIF got no thumbnail and no picture viewer and had to be routed by hand. WMA
 * was listed as music although Android has no decoder for it, so it opened in a player that
 * could only fail.
 *
 * Every one of those sites reads this table now, and `check-filetypes` fails when a Kotlin file
 * starts a list of its own again. Archives are the exception that is not one: [Archives] was
 * already a table, so the archive rows here are generated from it rather than copied.
 */
object FileTypes {

    // Declared above [ALL] because an object initialises top to bottom, and [ALL] reads them.

    /** The archive-table extensions that are app packages, which install rather than open. */
    private val APP_PACKAGES = setOf("apk", "apks", "xapk", "apkm")

    private val ARCHIVE_MIME = mapOf(
        "zip" to "application/zip",
        "jar" to "application/java-archive",
        "apk" to "application/vnd.android.package-archive",
        "epub" to "application/epub+zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
    )

    val ALL: List<FileType> = buildList {
        // ── pictures: everything Android decodes ──
        fun jpeg(ext: String) =
            add(FileType(ext, TypeKind.IMAGE, "image/jpeg", family = "jpeg", jpeg = true, compressed = true))
        jpeg("jpg"); jpeg("jpeg"); jpeg("jpe"); jpeg("jfif"); jpeg("jif")
        add(FileType("png", TypeKind.IMAGE, "image/png", lossless = true, compressed = true))
        add(FileType("gif", TypeKind.IMAGE, "image/gif", animated = true, lossless = true, compressed = true))
        add(FileType("webp", TypeKind.IMAGE, "image/webp", animated = true, lossless = true, compressed = true))
        add(FileType("bmp", TypeKind.IMAGE, "image/bmp", lossless = true))
        add(FileType("heic", TypeKind.IMAGE, "image/heic", platformFrom = 28, family = "heif", compressed = true))
        add(FileType("heif", TypeKind.IMAGE, "image/heif", platformFrom = 28, family = "heif", compressed = true))
        add(FileType("avif", TypeKind.IMAGE, "image/avif", platformFrom = 31, compressed = true))
        add(FileType("dng", TypeKind.IMAGE, "image/x-adobe-dng"))
        add(FileType("ico", TypeKind.IMAGE, "image/x-icon", galleryLists = false))
        add(FileType("wbmp", TypeKind.IMAGE, "image/vnd.wap.wbmp", galleryLists = false))
        add(FileType("svg", TypeKind.IMAGE, "image/svg+xml", vector = true, lossless = true, galleryLists = false))
        // Android cannot decode these, so another app opens them; a gallery still lists them.
        add(FileType("tif", TypeKind.IMAGE, "image/tiff", platformFrom = null, family = "tiff", galleryLists = true))
        add(FileType("tiff", TypeKind.IMAGE, "image/tiff", platformFrom = null, family = "tiff", galleryLists = true))
        // Pictures met inside office documents, which nothing in Android draws: named there
        // rather than shown as an empty box, and opened by another app on their own.
        add(FileType("emf", TypeKind.IMAGE, "image/emf", platformFrom = null, vector = true))
        add(FileType("wmf", TypeKind.IMAGE, "image/wmf", platformFrom = null, vector = true))
        add(FileType("eps", TypeKind.IMAGE, "application/postscript", platformFrom = null, vector = true))
        add(FileType("pict", TypeKind.IMAGE, "image/x-pict", platformFrom = null))

        // ── video ──
        fun video(ext: String, mime: String, platformFrom: Int? = ANY_API) =
            add(FileType(ext, TypeKind.VIDEO, mime, platformFrom = platformFrom, compressed = true))
        video("mp4", "video/mp4"); video("m4v", "video/mp4")
        video("mkv", "video/x-matroska"); video("webm", "video/webm")
        video("mov", "video/quicktime"); video("3gp", "video/3gpp")
        video("avi", "video/x-msvideo"); video("ts", "video/mp2t")
        video("mpg", "video/mpeg"); video("mpeg", "video/mpeg")
        // Not in Android itself, though some vendors add them - see [claimsHere].
        video("flv", "video/x-flv", platformFrom = null)
        video("wmv", "video/x-ms-wmv", platformFrom = null)

        // ── sound: everything Android plays ──
        fun audio(ext: String, mime: String, cover: Boolean = false, compressed: Boolean = true) =
            add(FileType(ext, TypeKind.AUDIO, mime, coverArt = cover, compressed = compressed))
        audio("mp3", "audio/mpeg", cover = true)
        audio("m4a", "audio/mp4", cover = true); audio("m4b", "audio/mp4", cover = true)
        audio("mp4a", "audio/mp4", cover = true); audio("aac", "audio/aac", cover = true)
        audio("flac", "audio/flac", cover = true)
        audio("ogg", "audio/ogg", cover = true); audio("oga", "audio/ogg", cover = true)
        audio("opus", "audio/ogg", cover = true)
        audio("mka", "audio/x-matroska", cover = true); audio("weba", "audio/webm", cover = true)
        audio("wav", "audio/wav", compressed = false)
        audio("amr", "audio/amr"); audio("awb", "audio/amr-wb")
        audio("mid", "audio/midi", compressed = false); audio("midi", "audio/midi", compressed = false)
        audio("xmf", "audio/mobile-xmf", compressed = false); audio("rtttl", "audio/x-rtttl", compressed = false)
        // Android has no WMA decoder. Listed so it still reads as music; never claimed.
        add(FileType("wma", TypeKind.AUDIO, "audio/x-ms-wma", platformFrom = null, compressed = true, galleryLists = false))

        // ── code and text the editor opens ──
        fun code(ext: String, mime: String? = "text/plain", icon: String = "source") =
            add(FileType(ext, TypeKind.CODE, mime, platformFrom = null, icon = icon))
        for (e in listOf(
            "kt", "kts", "java", "py", "lua", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "smali", "gradle",
        )) code(e)
        for (e in listOf("sh", "bash")) code(e, icon = "shell")
        code("sql", icon = "sql")
        for (e in listOf("yml", "yaml", "toml", "ini", "cfg", "conf", "properties")) code(e, icon = "config")
        for (e in listOf("cs", "pro")) code(e, null)
        for (e in listOf("tsx", "jsx")) code(e, null, icon = "script")
        code("scss", null, icon = "style")
        code("gradlew", null, icon = "shell")
        code("json", "application/json", icon = "json")
        code("xml", "text/xml", icon = "xml")
        code("html", "text/html", icon = "markup"); code("htm", "text/html", icon = "markup")
        code("css", "text/css", icon = "style")
        code("js", "text/javascript", icon = "script"); code("mjs", "text/javascript", icon = "script")
        code("cjs", "text/javascript", icon = "script")

        // ── documents ──
        fun doc(ext: String, mime: String? = null, compressed: Boolean = false, icon: String = "text") =
            add(FileType(ext, TypeKind.DOC, mime, platformFrom = null, compressed = compressed, icon = icon))
        doc("txt", "text/plain"); doc("log", "text/plain"); doc("md", "text/plain", icon = "markdown")
        doc("markdown", icon = "markdown"); doc("mdown", icon = "markdown"); doc("mkd", icon = "markdown")
        doc("pdf", "application/pdf", compressed = true, icon = "pdf")
        doc("doc", "application/msword", icon = "word")
        doc("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", compressed = true, icon = "word")
        doc("xls", "application/vnd.ms-excel", icon = "sheet")
        doc("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", compressed = true, icon = "sheet")
        doc("xlsm", compressed = true, icon = "sheet")
        doc("ppt", "application/vnd.ms-powerpoint", icon = "slides")
        doc("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", compressed = true, icon = "slides")
        doc("odt", "application/vnd.oasis.opendocument.text", compressed = true, icon = "word")
        doc("ods", "application/vnd.oasis.opendocument.spreadsheet", compressed = true, icon = "sheet")
        doc("odp", "application/vnd.oasis.opendocument.presentation", compressed = true, icon = "slides")
        doc("rtf", "application/rtf", icon = "word")
        doc("csv", "text/csv", icon = "sheet")
        doc("xhtml", "application/xhtml+xml", icon = "markup")
        doc("srt", "application/x-subrip", icon = "subs")
        doc("vtt", "text/vtt", icon = "subs")

        // ── raw binaries ──
        for (e in listOf("bin", "dat", "arsc")) add(FileType(e, TypeKind.BINARY, platformFrom = null))
        for (e in listOf("so", "o", "a", "dll")) add(FileType(e, TypeKind.BINARY, platformFrom = null, icon = "lib"))
        for (e in listOf("img", "iso")) add(FileType(e, TypeKind.BINARY, platformFrom = null, icon = "disc"))
        add(FileType("exe", TypeKind.BINARY, platformFrom = null, icon = "exe"))

        // ── archives and app packages, from the archive table ──
        //
        // A suffix with a dot in it (`tar.gz`) is not an extension in this sense - a name's
        // extension is what follows its LAST dot - so `backup.tar.gz` is found under "gz".
        for (format in Archives.ALL) for (ext in format.extensions) {
            if ('.' in ext || any { it.ext == ext }) continue
            val kind = if (ext in APP_PACKAGES) TypeKind.APK else TypeKind.ARCHIVE
            // A split bundle is drawn apart from a single APK: it installs differently, and an
            // APK usually shows its own app icon anyway.
            val icon = when {
                kind == TypeKind.ARCHIVE -> "archive"
                ext == "apk" -> "package"
                else -> "bundle"
            }
            add(FileType(ext, kind, ARCHIVE_MIME[ext], platformFrom = null, compressed = format.id != "tar", icon = icon))
        }
        // Not a real registered type, and deliberately so: a dex is a blob nothing else opens,
        // and naming it keeps it out of the wildcard branch where every app claims it.
        for (e in listOf("dex", "odex", "vdex")) {
            add(FileType(e, TypeKind.DEX, "application/octet-stream", platformFrom = null))
        }

        // ── other things with a type worth naming ──
        fun other(ext: String, mime: String, icon: String) =
            add(FileType(ext, TypeKind.OTHER, mime, platformFrom = null, icon = icon))
        other("torrent", "application/x-bittorrent", "torrent")
        // A source map is JSON to a page that fetches it, and not something to open in an editor.
        other("map", "application/json", "json")
        other("woff", "font/woff", "font"); other("woff2", "font/woff2", "font")
        other("ttf", "font/ttf", "font"); other("otf", "font/otf", "font")
        other("wasm", "application/wasm", "wasm")
    }

    private val byExt: Map<String, FileType> = ALL.associateBy { it.ext }

    /** An extension as the table keys it: lower case, no leading dot. */
    fun norm(extension: String): String = extension.lowercase(Locale.US).trimStart('.')

    /** The extension of [name]: what follows its last dot, or nothing for a name with none. */
    fun extensionOf(name: String): String = norm(name.substringAfterLast('.', ""))

    fun of(extension: String): FileType? = byExt[norm(extension)]

    fun kindOf(extension: String): TypeKind? = of(extension)?.kind

    /** The type Filet names itself, or null when the table leaves it to Android. */
    fun mime(extension: String): String? = of(extension)?.mime

    fun decodesOn(type: FileType, sdk: Int): Boolean = type.platformFrom != null && sdk >= type.platformFrom

    /**
     * Whether Filet's own viewer or player is the default for [extension] on [sdk].
     *
     * A picture or a sound Android cannot decode on this phone is not claimed, so it goes to
     * another app instead of a viewer that can only fail. Video is always claimed: what plays
     * varies by vendor (Samsung adds WMV and FLV), and the player says so in words when the
     * picture never comes.
     */
    fun claimsHere(extension: String, sdk: Int): Boolean {
        val t = of(extension) ?: return false
        return when (t.kind) {
            TypeKind.IMAGE, TypeKind.AUDIO -> decodesOn(t, sdk)
            TypeKind.VIDEO -> true
            else -> false
        }
    }

    /**
     * What to say when a picture or a sound goes to another app because Android cannot decode
     * it here, or null when nothing needs saying.
     */
    fun notPlayableHere(extension: String, sdk: Int): String? {
        val t = of(extension) ?: return null
        if (t.kind != TypeKind.IMAGE && t.kind != TypeKind.AUDIO) return null
        if (claimsHere(extension, sdk)) return null
        val name = t.ext.uppercase(Locale.US)
        return if (t.kind == TypeKind.AUDIO) {
            "Android cannot play $name on its own, so Filet's player cannot either. Open it in an app that can."
        } else {
            "This phone cannot show $name pictures on its own, so Filet's viewer cannot either. Open it in an app that can."
        }
    }

    /** Which thumbnail [extension] gets on [sdk]: a decoded picture, a video frame, cover art, or none. */
    fun thumbnailKind(extension: String, sdk: Int): TypeKind? {
        val t = of(extension) ?: return null
        if (!decodesOn(t, sdk)) return null
        return when {
            t.kind == TypeKind.IMAGE && !t.vector -> TypeKind.IMAGE
            t.kind == TypeKind.VIDEO -> TypeKind.VIDEO
            t.kind == TypeKind.AUDIO && t.coverArt -> TypeKind.AUDIO
            else -> null
        }
    }

    fun isJpeg(extension: String): Boolean = of(extension)?.jpeg == true

    fun isLossless(extension: String): Boolean = of(extension)?.lossless == true

    fun isAnimated(extension: String): Boolean = of(extension)?.animated == true

    fun isCompressed(extension: String): Boolean = of(extension)?.compressed == true

    fun galleryLists(extension: String): Boolean = of(extension)?.galleryLists == true

    fun isPicture(extension: String): Boolean = of(extension)?.kind == TypeKind.IMAGE

    /** The file-type icon for [extension]; "other" for one the table does not know. */
    fun icon(extension: String): String = of(extension)?.icon ?: "other"

    /** Every spelling of the type [extension] names, itself included. */
    fun spellings(extension: String): Set<String> {
        val e = norm(extension)
        val family = of(e)?.family ?: return setOf(e)
        return ALL.filter { it.family == family }.mapTo(LinkedHashSet()) { it.ext }
    }

    /** The extensions of one [kind], in table order. */
    fun extensions(kind: TypeKind): List<String> = ALL.filter { it.kind == kind }.map { it.ext }
}
