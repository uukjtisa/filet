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

    /**
     * An eighth of the heap, which is the usual share for an image cache.
     *
     * It was a flat 6 MB - about forty entries at 96 px, which the comment called "a screenful
     * and a bit". That is true of a list and false of the grid, where a tile asks for up to
     * 320 px and forty entries is less than one screen: scrolling a photo folder down and back
     * up evicted everything on the way down and re-decoded all of it on the way back. Sized
     * against the heap instead, a scroll-back is a cache hit.
     */
    private val CACHE_BYTES: Int = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(6L * 1024 * 1024, 48L * 1024 * 1024)
        .toInt()

    private val cache = object : LruCache<String, Answer>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Answer): Int =
            value.bitmap?.byteCount ?: 1
    }

    /**
     * A remembered answer. A null bitmap is a remembered FAILURE, so it is not attempted again -
     * which is why this is a box around the bitmap rather than the bitmap itself: "no preview"
     * and "not looked yet" are different states and a bare null cannot tell them apart.
     */
    class Answer internal constructor(val bitmap: Bitmap?)

    /** Extensions we will attempt. Asking is cheap; opening a 4 GB mkv to find out is not. */
    private val JPEG = setOf("jpg", "jpeg", "jpe", "jfif")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val VIDEO = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v", "avi", "ts")

    /**
     * Formats that can carry embedded cover art.
     *
     * Not every audio format can: a bare WAV has no tag container at all. Listing the ones
     * that can keeps a pointless extractor spin-up off every .wav in a folder.
     */
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wma", "mp4a", "m4b")
    private const val APK = "apk"

    fun canPreview(extension: String): Boolean {
        val e = extension.lowercase()
        return e in IMAGE || e in VIDEO || e in AUDIO || e == APK
    }

    /**
     * Drop every size held for [path]. For a file changed in place, so a list that still holds
     * its old size and time does not find its old pixels - a photo turned and saved would
     * otherwise keep its old thumbnail wherever the list had not been read again yet.
     */
    fun forget(path: VPath) {
        val prefix = "$path|"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
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
    /**
     * The answer already held for this file, or null when it has never been attempted.
     *
     * Non-blocking - a map lookup and nothing else - so a composable can use it during
     * composition and skip staging any work at all. This is what makes scrolling back up a
     * folder free: without it, every row that returned to the screen started a coroutine to ask
     * a question that was already answered.
     */
    fun peek(path: VPath, size: Long, mtime: Long, px: Int): Answer? =
        cache.get(keyOf(path, size, mtime, px))

    /**
     * The same queue the playlist rows use, for the same three reasons: newest row first, work
     * never thrown away when a row scrolls off, and a hard ceiling on how many decodes run at
     * once. See [LoadQueue].
     */
    private val queue = LoadQueue(workers = 2)

    /**
     * Decode for a row on screen, or join a decode already running for the same file.
     *
     * Suspends until there is an answer. Cancelling the caller stops the waiting and not the
     * work, so the next pass over the list finds it cached - which is the whole difference
     * between a second scroll being free and being a repeat of the first.
     */
    suspend fun load(
        context: Context,
        vfs: Vfs,
        path: VPath,
        size: Long,
        mtime: Long,
        px: Int,
        ahead: Boolean = false,
    ): Bitmap? {
        val key = keyOf(path, size, mtime, px)
        cache.get(key)?.let { return it.bitmap }
        val body = { get(context, vfs, path, size, mtime, px); Unit }
        if (ahead) queue.ahead(key, body) else queue.join(key, body)
        return cache.get(key)?.bitmap
    }

    fun get(context: Context, vfs: Vfs, path: VPath, size: Long, mtime: Long, px: Int): Bitmap? {
        val key = keyOf(path, size, mtime, px)
        cache.get(key)?.let { return it.bitmap }

        val ext = path.name.substringAfterLast('.', "").lowercase()
        val os = vfs.osPath(path)
        val bitmap = runCatching {
            when {
                ext in IMAGE -> fromImage(vfs, path, os, px, ext)
                ext in VIDEO -> os?.let { fromVideo(it, px) }
                ext in AUDIO -> os?.let { fromAudio(it, px) }
                ext == APK -> os?.let { fromApk(context, it, px) }
                else -> null
            }
        }.getOrNull()

        cache.put(key, Answer(bitmap))
        return bitmap
    }

    // ── images ──

    private fun fromImage(vfs: Vfs, path: VPath, os: String?, px: Int, ext: String): Bitmap? {
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
        return scaleTo(full, px).upright(orientationOf(vfs, path, ext))
    }

    /**
     * Which way up a JPEG is meant to be seen. `BitmapFactory` does not apply it, and a camera
     * that stores the turn as a tag rather than in the pixels gave every portrait photo a
     * thumbnail lying on its side. See [JpegOrientation].
     *
     * JPEG names only: the read is a second open of the file, and nothing else this decodes
     * carries the tag in a form BitmapFactory ignores.
     */
    private fun orientationOf(vfs: Vfs, path: VPath, ext: String): Int {
        if (ext !in JPEG) return JpegOrientation.NORMAL
        return runCatching {
            vfs.openReadBlocking(path).use { JpegOrientation.read(JpegOrientation.head(it)) }
        }.getOrDefault(JpegOrientation.NORMAL)
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

    // ── audio ──

    /**
     * Cover art out of the file's own tags.
     *
     * `embeddedPicture` reads the art the file carries, which is the art the user put there.
     * The tempting alternative is MediaStore's album-art table, and it is wrong twice over: it
     * only knows files it has scanned, so anything sideloaded or sitting in a working folder
     * has no entry, and it answers per ALBUM rather than per file, so one mistagged track
     * gives the whole folder somebody else's cover.
     */
    private fun fromAudio(os: String, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(os)
            val bytes = retriever.embeddedPicture ?: return null
            // Two-pass decode, same as an image file: cover art is routinely 1200x1200 and
            // decoding that full-size for a 40dp row is most of a megabyte per track.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, px)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { scaleTo(it, px) }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── apk ──

    /**
     * The icon inside an APK file, at [px].
     *
     * Public because the inspector's identity block wants it too: a generic package glyph where
     * the app's own icon could be is the inspector failing to say which app this is, which is
     * the first thing the screen is for.
     */
    fun apkIcon(context: Context, os: String, px: Int): Bitmap? = fromApk(context, os, px)

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
