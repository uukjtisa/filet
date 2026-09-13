package dev.niccc2007.filet.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.io.ByteArrayOutputStream

/**
 * Previews for the things that have one: images, video frames, APK icons.
 *
 * Three rules, and they are the whole design:
 *
 * 1. **Never decode a full-size image to draw a 48 px square.** A 12-megapixel JPEG is 48 MB
 *    decoded; a grid of them is an OutOfMemoryError with a stack trace that blames Compose.
 *    Everything here goes through `inSampleSize`, so the large allocation never happens.
 * 2. **Cache by content, not by path.** The key carries size and mtime, so an edited file gets
 *    a new preview and a renamed one keeps its old — which is what makes scrolling back up a
 *    list free.
 * 3. **Failure is silent and cheap.** A corrupt JPEG, a DRM video, an APK with no icon: all
 *    return null, get remembered as null, and are never retried during that session.
 */
object Thumbnails {

    /** Roughly 40 entries at 96 px ARGB, which is a screenful and a bit. */
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val cache = object : LruCache<String, Holder>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Holder): Int =
            value.bitmap?.byteCount ?: 1
    }

    /** A null bitmap is a *remembered* failure, so it is not attempted again. */
    private class Holder(val bitmap: Bitmap?)

    /** Extensions we will attempt. Asking is cheap; opening a 4 GB mkv to find out is not. */
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val VIDEO = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v", "avi", "ts")
    private const val APK = "apk"

    fun canPreview(extension: String): Boolean {
        val e = extension.lowercase()
        return e in IMAGE || e in VIDEO || e == APK
    }

    private fun keyOf(path: VPath, size: Long, mtime: Long, px: Int) =
        "$path|$size|$mtime|$px"

    /**
     * @param px the longest edge wanted, in pixels.
     * @return a bitmap, or null when this file has no preview to give.
     *
     * Blocking. Call it off the main thread - the app does so through a `LaunchedEffect`, the
     * server from its own worker.
     */
    fun get(context: Context, vfs: Vfs, path: VPath, size: Long, mtime: Long, px: Int): Bitmap? {
        val key = keyOf(path, size, mtime, px)
        cache.get(key)?.let { return it.bitmap }

        val ext = path.name.substringAfterLast('.', "").lowercase()
        val os = vfs.osPath(path)
        val bitmap = runCatching {
            when {
                ext in IMAGE -> fromImage(vfs, path, os, px)
                ext in VIDEO -> os?.let { fromVideo(it, px) }
                ext == APK -> os?.let { fromApk(context, it, px) }
                else -> null
            }
        }.getOrNull()

        cache.put(key, Holder(bitmap))
        return bitmap
    }

    // ── images ──

    private fun fromImage(vfs: Vfs, path: VPath, os: String?, px: Int): Bitmap? {
        // Two passes. The first reads only the header to learn the dimensions, which is why
        // `inJustDecodeBounds` exists and why this never allocates the full image.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (os != null) {
            BitmapFactory.decodeFile(os, bounds)
        } else {
            vfs.openReadBlocking(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, px)
            inPreferredConfig = Bitmap.Config.RGB_565   // half the memory, invisible at this size
        }
        val full = if (os != null) {
            BitmapFactory.decodeFile(os, opts)
        } else {
            vfs.openReadBlocking(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return null
        return scaleTo(full, px)
    }

    /**
     * The power of two that gets the image just under the target.
     *
     * `inSampleSize` only honours powers of two, so overshooting and scaling once afterwards
     * is both faster and sharper than asking for an exact size.
     */
    private fun sampleFor(w: Int, h: Int, px: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= px) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleTo(source: Bitmap, px: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= px) return source
        val ratio = px.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    // ── video ──

    private fun fromVideo(os: String, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(os)
            // A frame one second in, not frame zero: the first frame of a phone video is very
            // often black, and a grid of black squares is worse than no thumbnails at all.
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTimeCompat()
            frame?.let { scaleTo(it, px) }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.frameAtTimeCompat(): Bitmap? =
        runCatching { getFrameAtTime(0) }.getOrNull()

    // ── apk ──

    private fun fromApk(context: Context, os: String, px: Int): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(os, 0) ?: return null
        // The APK is not installed, so its resources have no base path until we give it one.
        // Skip this and every icon lookup silently returns the default Android robot.
        info.applicationInfo?.apply {
            sourceDir = os
            publicSourceDir = os
        } ?: return null
        val drawable = runCatching { info.applicationInfo!!.loadIcon(pm) }.getOrNull() ?: return null
        return drawable.toBitmap(px)
    }

    private fun Drawable.toBitmap(px: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return scaleTo(bitmap, px)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        setBounds(0, 0, px, px)
        draw(canvas)
        return out
    }

    // ── for the LAN server ──

    /** JPEG bytes at a size a browser grid wants, or null when there is no preview. */
    fun forWeb(context: Context, vfs: Vfs, path: VPath): ByteArray? {
        val node = runCatching { kotlinx.coroutines.runBlocking { vfs.stat(path) } }.getOrNull() ?: return null
        val bitmap = get(context, vfs, path, node.size, node.mtime, WEB_PX) ?: return null
        val out = ByteArrayOutputStream(16 * 1024)
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)) out.toByteArray() else null
    }

    private const val WEB_PX = 160

    fun clear() = cache.evictAll()
}

/** Blocking read, for the two-pass decode above. Kept here rather than widening the Vfs API. */
private fun Vfs.openReadBlocking(path: VPath) =
    kotlinx.coroutines.runBlocking { openRead(path) }
