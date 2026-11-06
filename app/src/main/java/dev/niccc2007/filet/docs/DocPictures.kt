package dev.niccc2007.filet.docs

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.svg.SvgDecoder
import coil3.toBitmap
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.data.FiletStore
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Where a link or a picture's address points, once read against the file it is in. */
internal sealed interface Ref {
    /** A `#name` in this document. */
    data class Anchor(val fragment: String) : Ref

    data class Web(val url: String) : Ref

    data class Mail(val address: String) : Ref

    /**
     * A file beside this one, or anywhere it can name.
     *
     * @param fromRoot set for an address that starts with `/`: GitHub reads that from the top of
     *   the repository, so it is tried there first, under the nearest folder holding `.git`, and
     *   only then as a path from the top of the volume.
     */
    data class Local(val path: VPath, val fromRoot: String = "") : Ref

    /** Nothing to follow - empty, or a kind of address this reader will not act on. */
    data object None : Ref
}

/**
 * Links and picture addresses, resolved the way GitHub resolves them.
 *
 * Relative to the folder the document is in, with `./` and `../`; a leading `/` from the
 * repository's top; `?query` and `#fragment` cut off a local path; `%20` decoded. A scheme other
 * than the web's or mail's - `javascript:`, `intent:`, `data:` - goes nowhere: a document is
 * somebody else's text, and an address in it is not a reason to run anything.
 */
internal object DocRefs {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun link(href: String, file: VPath?): Ref {
        val h = href.trim()
        if (h.isEmpty()) return Ref.None
        if (h.startsWith("#")) return Ref.Anchor(h.substring(1))
        val lower = h.lowercase()
        if (lower.startsWith("mailto:")) return Ref.Mail(h.substring(7).substringBefore('?'))
        return place(h, file)
    }

    fun picture(src: String, file: VPath?): Ref = when (val r = place(src.trim(), file)) {
        is Ref.Web, is Ref.Local -> r
        else -> Ref.None
    }

    private fun place(h: String, file: VPath?): Ref {
        if (h.isEmpty() || h.startsWith("#")) return Ref.None
        val lower = h.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return Ref.Web(h)
        if (h.startsWith("//")) return Ref.Web("https:$h")
        val scheme = file?.scheme ?: "local"
        if (lower.startsWith("file://")) {
            val p = decode(h.substring(7).substringBefore('#').substringBefore('?'))
            return if (p.startsWith("/")) Ref.Local(VPath.of(scheme, p)) else Ref.None
        }
        if (SCHEME.containsMatchIn(h)) return Ref.None
        val p = decode(h.substringBefore('#').substringBefore('?')).replace('\\', '/')
        if (p.isEmpty()) return Ref.None
        if (p.startsWith("/")) return Ref.Local(VPath.of(scheme, p), fromRoot = p)
        val dir = file?.parent ?: return Ref.None
        return Ref.Local(VPath.of(dir.scheme, dir.path.trimEnd('/') + "/" + p))
    }

    /** `%20` into a space; a `+` stays a `+`, because this is a path and not a form. */
    private fun decode(s: String): String =
        if ('%' !in s) s else runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** What to call a picture that is not shown: its alt text, or the name in its address. */
    fun nameOf(p: Picture): String = p.alt.trim().ifEmpty {
        p.src.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').ifEmpty { "picture" }
    }
}

/** A picture in the VFS, to be read through it. [file] is the document, for the repository search. */
internal data class VfsPicture(val path: VPath, val fromRoot: String, val file: VPath?)

/**
 * The image loader for document pictures - Coil 3, with the app's own plumbing under it.
 *
 * ## What is Filet's rather than Coil's
 *
 * - **The network** is `HttpURLConnection` through [UrlClient], which is how the rest of the app
 *   talks HTTP; adding OkHttp for pictures would be a second network stack in the APK.
 * - **Local pictures** are read through the VFS ([VfsFetcher]), so a README inside an archive or
 *   on a share shows its pictures like one in Downloads, and nothing here holds a platform file.
 * - **The disk cache** is a FiletStore area, so it is counted and emptied with the rest of the
 *   derived files rather than hiding in the app's private cache.
 */
internal object DocPictures {

    /** The largest a picture is decoded, either way. A phone column never needs more. */
    const val MAX_PX = 1440

    /** Past this, a picture file is not what a document meant to show. */
    const val MAX_BYTES = 24L * 1024 * 1024

    private const val DISK_BYTES = 64L * 1024 * 1024

    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: build(context.applicationContext).also { loader = it }
    }

    private fun build(context: Context): ImageLoader {
        val vfs = FiletApp.graphOf(context).vfs
        return ImageLoader.Builder(context)
            .components {
                add(VfsFetcher.Factory(vfs), VfsPicture::class)
                add(Keyer<VfsPicture> { data, _ -> "vfs:${data.path}|${data.fromRoot}|${data.file?.parent}" }, VfsPicture::class)
                add(NetworkFetcher.Factory(networkClient = { UrlClient }), Uri::class)
                add(SvgAtOwnSize(SvgDecoder.Factory(scaleToDensity = true)))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(FiletStore.dirPath(context, FiletStore.Area.PICTURES).toPath())
                    .maxSizeBytes(DISK_BYTES)
                    .build()
            }
            .build()
    }

    /**
     * Load [data] and return it with the size it should be drawn at, in dp, or null.
     *
     * A picture's pixels are drawn as CSS pixels - one to a dp - which is the size the author
     * saw it at on GitHub. An SVG is drawn at its own declared size, rendered at the screen's
     * density so a badge is sharp; see [SvgAtOwnSize].
     */
    suspend fun load(context: Context, data: Any): Pair<ImageBitmap, DpSize>? {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(MAX_PX, MAX_PX)
            .precision(Precision.INEXACT)
            .build()
        val result = loader(context).execute(request) as? SuccessResult ?: return null
        val image = result.image
        val bitmap = withContext(Dispatchers.Default) { image.toBitmap().asImageBitmap() }
        // An SVG says its own size, in CSS pixels, and is drawn at exactly that many dp. Read off
        // the file rather than worked back from the bitmap: see [SvgAtOwnSize] for why the
        // bitmap's size cannot be trusted to carry it.
        val css = (image as? SvgImage)?.css
        if (css != null) return bitmap to DpSize(css.first.dp, css.second.dp)
        val density = if (image is SvgImage) context.resources.displayMetrics.density else 1f
        return bitmap to DpSize((image.width / density).dp, (image.height / density).dp)
    }
}

/**
 * An SVG, with the size it declares in CSS pixels when it declared one. See [DocPictures.load].
 */
private class SvgImage(inner: coil3.Image, val css: Pair<Float, Float>?) : coil3.Image by inner

/**
 * The size an SVG declares on its root element, in CSS pixels. Pure.
 *
 * `width` and `height` when both are plain numbers or pixels; one of them and the `viewBox` for
 * the other; the `viewBox` alone when neither is given. Null when it says nothing usable - a
 * percentage, an `em` - and the picture then falls back to the size Coil decoded it at.
 */
internal object SvgSize {
    private val ROOT = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun declared(head: String): Pair<Float, Float>? {
        val tag = ROOT.find(head)?.value ?: return null
        fun attr(name: String): String? =
            Regex("\\s" + name + "\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim()
        fun px(v: String?): Float? =
            v?.removeSuffix("px")?.trim()?.toFloatOrNull()?.takeIf { it > 0f && it < 10_000f }
        val w = px(attr("width"))
        val h = px(attr("height"))
        val box = attr("viewBox")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
        return when {
            w != null && h != null -> w to h
            w != null && box != null -> w to w * box[3] / box[2]
            h != null && box != null -> h * box[2] / box[3] to h
            box != null -> box[2] to box[3]
            else -> null
        }
    }
}

/**
 * SVGs decoded at their own size rather than the request's.
 *
 * The request caps a photo at [DocPictures.MAX_PX]; an SVG asked for that size is drawn that
 * size, and a 90-pixel badge comes back fourteen hundred wide having forgotten what it was. So an
 * SVG is decoded at its declared size, times the screen's density.
 *
 * Bug identified here: that size was left to the decoder, asked for its "original" size with
 * density scaling on, and the bitmap's width divided by the density afterwards to get back to
 * CSS pixels. On the phone the badges came out a third of their size - the decode had not been
 * scaled and the division ran anyway - and nothing in the reader could tell the two cases apart,
 * since both are just a bitmap of some width. Now the declared size is read off the file itself
 * ([SvgSize]), the decoder is asked for exactly that times the density, and the picture carries
 * its CSS size beside the pixels, so no step has to infer one from the other.
 */
private class SvgAtOwnSize(private val svg: SvgDecoder.Factory) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val css = runCatching {
            val peek = result.source.source().peek()
            peek.request(HEAD_BYTES)
            SvgSize.declared(peek.buffer.readUtf8(minOf(peek.buffer.size, HEAD_BYTES)))
        }.getOrNull()
        val density = options.context.resources.displayMetrics.density
        val asked = if (css == null) {
            options.copy(size = Size.ORIGINAL)
        } else {
            options.copy(
                size = Size((css.first * density).toInt().coerceAtLeast(1), (css.second * density).toInt().coerceAtLeast(1)),
                scale = Scale.FIT,
            )
        }
        val decoder = svg.create(result, asked, imageLoader) ?: return null
        return object : Decoder {
            override suspend fun decode(): DecodeResult? =
                decoder.decode()?.let { DecodeResult(SvgImage(it.image, css), it.isSampled) }
        }
    }

    private companion object {
        /** Enough of the file to hold its root tag, which comes first. */
        const val HEAD_BYTES = 4096L
    }
}

/**
 * Where a local address lands: [path], unless it was written from the top (`/docs/a.png`) and a
 * repository holds the document - then from the nearest folder above it that has `.git`, which
 * is where GitHub reads it from. Stats the VFS, so off the main thread.
 */
internal suspend fun localTarget(vfs: Vfs, path: VPath, fromRoot: String, file: VPath?): VPath {
    if (fromRoot.isEmpty()) return path
    var dir = file?.parent
    repeat(24) {
        val d = dir ?: return path
        val git = runCatching { vfs.stat(d.child(".git")) }.getOrNull()
        if (git != null) return VPath.of(d.scheme, d.path.trimEnd('/') + fromRoot)
        dir = d.parent
    }
    return path
}

/** A picture read through the VFS. */
private class VfsFetcher(private val data: VfsPicture, private val options: Options, private val vfs: Vfs) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val target = localTarget(vfs, data.path, data.fromRoot, data.file)
        val node = vfs.stat(target) ?: throw IOException("${target.name} is not there")
        if (node.isDir) throw IOException("${target.name} is a folder")
        if (node.size > DocPictures.MAX_BYTES) throw IOException("${target.name} is too large to show")
        val source = vfs.openRead(target).source().buffer()
        SourceFetchResult(ImageSource(source, options.fileSystem), mimeOf(target.name), DataSource.DISK)
    }

    /** A picture's type from the file type table; anything that is not a picture is left to Coil to sniff. */
    private fun mimeOf(name: String): String? {
        val type = FileTypes.of(FileTypes.extensionOf(name)) ?: return null
        return type.mime.takeIf { type.kind == TypeKind.IMAGE }
    }

    class Factory(private val vfs: Vfs) : Fetcher.Factory<VfsPicture> {
        override fun create(data: VfsPicture, options: Options, imageLoader: ImageLoader): Fetcher =
            VfsFetcher(data, options, vfs)
    }
}

/**
 * Coil's network, over `HttpURLConnection`.
 *
 * http and https only, redirects followed, and the body cut off past [DocPictures.MAX_BYTES]
 * whatever the server says its length is - a document is text from anywhere, and a picture in
 * it must not be able to fill a data plan or a disk.
 */
private object UrlClient : NetworkClient {

    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (response: NetworkResponse) -> T): T =
        withContext(Dispatchers.IO) {
            val url = URL(request.url)
            if (url.protocol != "http" && url.protocol != "https") throw IOException("Not a web address: ${request.url}")
            val sent = System.currentTimeMillis()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                requestMethod = request.method
                for ((k, values) in request.headers.asMap()) for (v in values) addRequestProperty(k, v)
                setRequestProperty("User-Agent", "Filet")
            }
            try {
                val code = conn.responseCode
                if (conn.contentLengthLong > DocPictures.MAX_BYTES) throw IOException("Picture too large")
                val headers = NetworkHeaders.Builder().apply {
                    for ((k, values) in conn.headerFields) if (k != null) for (v in values) add(k, v)
                }.build()
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.let { NetworkResponseBody(Capped(it, DocPictures.MAX_BYTES).source().buffer()) }
                block(NetworkResponse(code, sent, System.currentTimeMillis(), headers, body, null))
            } finally {
                conn.disconnect()
            }
        }
}

/** A stream that refuses to go past [max] bytes. */
private class Capped(inner: InputStream, private val max: Long) : FilterInputStream(inner) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && ++count > max) throw IOException("Picture too large")
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw IOException("Picture too large")
        }
        return n
    }
}

/** One picture's state in a run of text: what loaded, at what size, or why not. */
@Stable
internal class PictureSlot {
    var size by mutableStateOf<DpSize?>(null)
    var image by mutableStateOf<ImageBitmap?>(null)
    var failed by mutableStateOf(false)

    /** A web picture the reader tapped Load on, with web pictures off. */
    var allowed by mutableStateOf(false)
}

/**
 * One picture, inside the placeholder its text holds for it. See [RichText] for how big that is.
 */
@Composable
internal fun InlinePicture(p: Picture, slot: PictureSlot, env: DocEnv) {
    val colors = Filet.colors
    val context = LocalContext.current
    val ref = androidx.compose.runtime.remember(p.src, env.place?.file) { DocRefs.picture(p.src, env.place?.file) }
    val allowed = ref !is Ref.Web || env.reading.webPictures || slot.allowed
    LaunchedEffect(ref, allowed) {
        if (!allowed || slot.image != null) return@LaunchedEffect
        val data: Any = when (ref) {
            is Ref.Web -> ref.url
            is Ref.Local -> VfsPicture(ref.path, ref.fromRoot, env.place?.file)
            else -> { slot.failed = true; return@LaunchedEffect }
        }
        val loaded = runCatching { DocPictures.load(context, data) }.getOrNull()
        if (loaded == null) {
            slot.failed = true
        } else {
            slot.image = loaded.first
            slot.size = loaded.second
        }
    }
    val tap = if (p.href.isNotEmpty()) Modifier.clickable { env.onLink(p.href) } else Modifier
    val image = slot.image
    when {
        image != null -> Image(
            image,
            contentDescription = p.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).then(tap),
        )
        !allowed -> Pill("⤓ ${DocRefs.nameOf(p)}", Modifier.clickable { slot.allowed = true })
        slot.failed -> Pill("▨ ${DocRefs.nameOf(p)}", tap)
        else -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(colors.sunken).then(tap))
    }
}

/** A picture that is not shown, as its name in a small box the size of one. */
@Composable
private fun Pill(label: String, modifier: Modifier) {
    val colors = Filet.colors
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(5.dp))
            .background(colors.sunken)
            .then(modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, fontSize = 10.5.sp, lineHeight = 12.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
