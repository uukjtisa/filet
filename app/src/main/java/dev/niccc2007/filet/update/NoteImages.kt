package dev.niccc2007.filet.update

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The screenshots in a release body.
 *
 * ## Why this is not Coil
 *
 * Trawl renders the same image strips with Coil, because Seal already had Coil for video
 * thumbnails. Filet has no image loader at all - its own viewer decodes local files directly -
 * so taking Coil here would be adding a whole loading, caching and lifecycle stack to the APK
 * to draw at most a handful of pictures on one screen. This is that screen's worth of it:
 * download, downsample, cache, done.
 *
 * ## The three things that make it safe rather than short
 *
 * 1. **Downsampled before it is decoded.** `inJustDecodeBounds` first, then an `inSampleSize`,
 *    so a 4000 px screenshot becomes a few hundred KB of bitmap instead of 64 MB and an OOM.
 *    A phone-sized strip never needs more than [TARGET_WIDTH] across.
 * 2. **Capped and scheme-checked.** http/https only and [MAX_BYTES] at most, because a release
 *    body is text fetched over the network and a renderer that will download anything of any
 *    size on sight is a way to hurt somebody's data plan at best.
 * 3. **Cached on disk by URL.** Reopening the update sheet does not re-download. The cache is
 *    in `cacheDir`, so Android may reclaim it and nothing depends on it surviving.
 */
object NoteImages {

    /** Wide enough for a full-bleed phone screenshot, small enough to be cheap. */
    private const val TARGET_WIDTH = 720

    /** A screenshot is a few hundred KB. Anything past this is not what we think it is. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    private const val DIR = "release-notes"

    /**
     * Decoded images, newest last.
     *
     * Small and hard-capped: the whole point is that reopening the sheet is instant, not that
     * every picture ever seen stays resident. Bitmaps are the largest thing this app holds.
     */
    private const val MEMORY_ENTRIES = 8
    private val memory = LinkedHashMap<String, ImageBitmap>()
    private val lock = Mutex()

    /**
     * Fetch and decode [url], from memory, then disk, then the network.
     *
     * @return null for anything that went wrong. A release note with a broken picture in it
     *   still has to render its text; there is no error state worth showing for this.
     */
    suspend fun load(context: Context, url: String): ImageBitmap? {
        lock.withLock { memory[url] }?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFile(context, url)
                if (!file.isFile || file.length() == 0L) {
                    currentCoroutineContext().ensureActive()
                    if (!download(url, file)) return@runCatching null
                }
                currentCoroutineContext().ensureActive()
                val bitmap = decode(file) ?: return@runCatching null
                val image = bitmap.asImageBitmap()
                lock.withLock {
                    memory[url] = image
                    while (memory.size > MEMORY_ENTRIES) {
                        memory.remove(memory.keys.first())
                    }
                }
                image
            }.getOrNull()
        }
    }

    /** Drop everything. Called when the sheet closes for good, not between openings. */
    suspend fun clearMemory() {
        lock.withLock { memory.clear() }
    }

    // ── wire ──

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // Hashed rather than sanitised: a URL is not a filename, and every attempt to turn one
        // into a filename by replacing characters eventually collides or escapes the directory.
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    private suspend fun download(url: String, target: File): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (parsed.protocol !in setOf("http", "https")) return false

        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        var ok = false
        try {
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Filet")
            }
            if (conn.responseCode !in 200..299) return false
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) return false

            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        // Checked as it arrives, not only from the header: a server that lies
                        // about Content-Length, or omits it, must not be able to fill the disk.
                        if (done > MAX_BYTES) return false
                        out.write(buf, 0, n)
                    }
                }
            }
            ok = part.renameTo(target)
            return ok
        } catch (_: Throwable) {
            return false
        } finally {
            if (!ok) part.delete()
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, TARGET_WIDTH)
            inPreferredConfig = Bitmap.Config.RGB_565.takeIf { !bounds.outMimeType.orEmpty().endsWith("png") }
                ?: Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * The largest power of two that still leaves at least [targetWidth] pixels across.
     *
     * Powers of two because that is the only thing `inSampleSize` actually honours; anything
     * else is rounded down to one, which is how a "downsampled" decode silently becomes a
     * full-size one.
     */
    internal fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}
