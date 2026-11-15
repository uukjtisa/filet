package dev.niccc2007.filet.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
    private val CACHE_BYTES: Int = (Runtime.getRuntime().maxMemory() / 6)
        .coerceIn(6L * 1024 * 1024, 96L * 1024 * 1024)
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

    /**
     * What a preview is for. A square one is cropped from the middle at decode, sized on its short
     * edge, because every list row, tile and folder picture draws it cropped to a square - see
     * [ThumbSize]. A whole one keeps the picture's shape, sized on its long edge, for the peek and
     * the viewer's poster, which show all of it.
     */
    enum class Shape { SQUARE, WHOLE }

    /**
     * Which preview an extension gets, if any, from the file type table. Asking is cheap; opening
     * a 4 GB mkv to find out is not.
     *
     * Bug identified here: this kept its own picture list, and `.jfif` was in the JPEG half of it
     * (for the orientation check) and missing from the half that decides whether to decode at
     * all - so a JFIF never got a thumbnail. The table answers both from one row. It also knows
     * the API level a format decodes from, so a HEIC on Android 8 is not opened only to fail,
     * and which sound formats can carry cover art: a bare WAV has no tag container, and asking
     * keeps a pointless extractor spin-up off every .wav in a folder.
     */
    private fun previewOf(extension: String): Kind? = when {
        FileTypes.norm(extension) == APK -> Kind.APK
        dev.niccc2007.filet.handlers.SvgPicture.handles(extension) -> Kind.SVG
        else -> when (FileTypes.thumbnailKind(extension, Build.VERSION.SDK_INT)) {
            TypeKind.IMAGE -> Kind.IMAGE
            TypeKind.VIDEO -> Kind.VIDEO
            TypeKind.AUDIO -> Kind.AUDIO
            else -> null
        }
    }

    /**
     * Where a preview comes from. SVG is its own: the type table gives a vector no preview, since
     * `BitmapFactory` cannot read one, so every .svg was a page with a tag on it. Bug identified
     * there; it is now drawn with the viewer's own renderer, once, and kept like any other preview.
     */
    private enum class Kind { IMAGE, SVG, VIDEO, AUDIO, APK }

    private const val APK = "apk"

    fun canPreview(extension: String): Boolean = previewOf(extension) != null

    /**
     * Drop every size held for [path]. For a file changed in place, so a list that still holds
     * its old size and time does not find its old pixels - a photo turned and saved would
     * otherwise keep its old thumbnail wherever the list had not been read again yet.
     */
    fun forget(context: Context, path: VPath) {
        val prefix = "$path|"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        ThumbDisk.forget(context, path.toString())
    }

    private fun keyOf(path: VPath, size: Long, mtime: Long, px: Int, shape: Shape) =
        "$path|" + restOf(size, mtime, px, shape)

    /** The key less the path: what tells two previews of one file apart on disk. */
    private fun restOf(size: Long, mtime: Long, px: Int, shape: Shape) = "$size|$mtime|$px|$shape|v2"

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
    fun peek(path: VPath, size: Long, mtime: Long, px: Int, shape: Shape = Shape.SQUARE): Answer? =
        cache.get(keyOf(path, size, mtime, px, shape))

    /**
     * The same queue the playlist rows use, for the same three reasons: newest row first, work
     * never thrown away when a row scrolls off, and a hard ceiling on how many decodes run at
     * once. See [LoadQueue].
     */
    private val queue = LoadQueue(workers = 4)

    /**
     * Reads of previews already written down, on a lane of their own.
     *
     * Bug identified here: a preview kept on disk took the same road as one never made - the row
     * waited out its settle delay, then queued behind whole-file decodes and video frames - so a
     * folder seen yesterday filled in at the speed of one never seen, and nothing at all showed
     * while a fling was moving. Reading a kept preview is a few milliseconds. Here it starts at
     * once, never waits behind a decode, and the gallery-style "already there" is what results.
     */
    private val diskQueue = LoadQueue(workers = 3, urgentCap = 96)

    /**
     * Video frames are read by the phone's hardware decoder, and it has room for only so many at
     * once. Two at a time here, whatever the queue's width, so a gallery of videos cannot take
     * every decoder session the phone has.
     */
    private val videoGate = java.util.concurrent.Semaphore(2)

    /** How many full-screen players are open; video frames wait while any is. See [holdVideoFrames]. */
    private val videoHolds = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * No video frame read for a preview until the returned handle is closed. For the full-screen
     * player, which needs the hardware decoder to itself: a 4K, 60 fps video takes most of what
     * the chip has, and a preview frame being read behind it - in the gallery the player was opened
     * from - is enough for the chip to refuse the player. Pictures, covers and icons carry on.
     */
    fun holdVideoFrames(): AutoCloseable {
        videoHolds.update { it + 1 }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) videoHolds.update { (it - 1).coerceAtLeast(0) } }
    }

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
        shape: Shape = Shape.SQUARE,
    ): Bitmap? {
        val key = keyOf(path, size, mtime, px, shape)
        cache.get(key)?.let { return it.bitmap }
        kept(context, path, size, mtime, px, shape, ahead)?.let { return it }
        if (previewOf(FileTypes.extensionOf(path.name)) == Kind.VIDEO && videoHolds.value > 0) {
            videoHolds.first { it == 0 }
        }
        val body = { get(context, vfs, path, size, mtime, px, shape); Unit }
        if (ahead) queue.ahead(key, body) else queue.join(key, body)
        return cache.get(key)?.bitmap
    }

    /**
     * The preview held in memory or written down, without decoding anything: null means it has to
     * be made. Cheap enough to ask the moment a row appears, a fling included - see [diskQueue].
     */
    suspend fun kept(
        context: Context,
        path: VPath,
        size: Long,
        mtime: Long,
        px: Int,
        shape: Shape = Shape.SQUARE,
        ahead: Boolean = false,
    ): Bitmap? {
        val key = keyOf(path, size, mtime, px, shape)
        cache.get(key)?.let { return it.bitmap }
        val app = context.applicationContext
        val rest = restOf(size, mtime, px, shape)
        val read = {
            // A miss is not remembered: "not on disk" is not "no preview", and the decode that
            // follows is what answers that.
            if (cache.get(key) == null) ThumbDisk.read(app, path.toString(), rest)?.let { cache.put(key, Answer(it.ready())) }
            Unit
        }
        if (ahead) diskQueue.ahead(key, read) else diskQueue.join(key, read)
        return cache.get(key)?.bitmap
    }

    fun get(context: Context, vfs: Vfs, path: VPath, size: Long, mtime: Long, px: Int, shape: Shape = Shape.SQUARE): Bitmap? {
        val key = keyOf(path, size, mtime, px, shape)
        cache.get(key)?.let { return it.bitmap }
        val rest = restOf(size, mtime, px, shape)
        ThumbDisk.read(context, path.toString(), rest)?.let {
            cache.put(key, Answer(it.ready()))
            return it
        }

        val ext = FileTypes.extensionOf(path.name)
        val os = vfs.osPath(path)
        val square = shape == Shape.SQUARE
        val kind = previewOf(ext)
        // The phone's own preview before a decode of ours, for the two kinds it makes. See
        // SystemThumbs for when it is and is not used.
        val system = if (os != null && (kind == Kind.IMAGE || kind == Kind.VIDEO)) {
            runCatching { SystemThumbs.load(context, os, size, mtime, px, square) }.getOrNull()?.let { fit(it, px, square) }
        } else null
        val bitmap = system ?: runCatching {
            when (kind) {
                Kind.IMAGE -> fromImage(vfs, path, os, px, ext, square)
                Kind.SVG -> fromSvg(vfs, path, px, square)
                Kind.VIDEO -> os?.let { fromVideo(it, px, square) }
                Kind.AUDIO -> os?.let { fromAudio(it, px, square) }
                Kind.APK -> os?.let { fromApk(context, it, px) }
                null -> null
            }
        }.getOrNull()

        cache.put(key, Answer(bitmap?.ready()))
        // Failures are not written down: a file that would not decode today may be a download
        // that had not finished, and memory already stops it being tried again this run.
        if (bitmap != null) ThumbDisk.write(context, path.toString(), rest, bitmap)
        return bitmap
    }

    /**
     * Starts the bitmap's upload to the graphics memory now, on the thread that made it. Left to
     * the first frame that draws it, a screenful arriving together is a screenful of uploads in
     * one frame, which is a dropped frame mid-scroll.
     */
    private fun Bitmap.ready(): Bitmap = also { runCatching { prepareToDraw() } }

    // ── images ──

    private fun fromImage(vfs: Vfs, path: VPath, os: String?, px: Int, ext: String, square: Boolean): Bitmap? {
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
            inSampleSize = ThumbSize.sample(bounds.outWidth, bounds.outHeight, px, square)
            // Full colour. RGB_565 halved the memory and banded every sky and face into steps,
            // which is a good part of why previews looked low-resolution. See ThumbSize.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val full = if (os != null) {
            BitmapFactory.decodeFile(os, opts)
        } else {
            vfs.openReadBlocking(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return null
        return fit(full, px, square).upright(orientationOf(vfs, path, ext))
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
        if (!FileTypes.isJpeg(ext)) return JpegOrientation.NORMAL
        return runCatching {
            vfs.openReadBlocking(path).use { JpegOrientation.read(JpegOrientation.head(it)) }
        }.getOrDefault(JpegOrientation.NORMAL)
    }

    /**
     * [source] brought to [px] - on its short edge and cropped to the middle square when
     * [square], on its long edge otherwise - recycling what it replaces. See [ThumbSize].
     */
    private fun fit(source: Bitmap, px: Int, square: Boolean): Bitmap {
        val (w, h) = ThumbSize.scaled(source.width, source.height, px, square)
        val scaled = if (w == source.width && h == source.height) source
        else Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source) source.recycle() }
        if (!square || scaled.width == scaled.height) return scaled
        val (left, top, side) = ThumbSize.crop(scaled.width, scaled.height)
        val cropped = Bitmap.createBitmap(scaled, left, top, side, side)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    // ── svg ──

    /**
     * An SVG drawn by the viewer's own renderer, its long side at [px]. A square preview gets the
     * whole drawing centred in the square rather than cropped: an SVG is usually an icon or a
     * logo, and its edges are the part that says what it is.
     */
    private fun fromSvg(vfs: Vfs, path: VPath, px: Int, square: Boolean): Bitmap? {
        val bytes = vfs.openReadBlocking(path).use { dev.niccc2007.filet.handlers.SvgPicture.readCapped(it) } ?: return null
        val drawn = dev.niccc2007.filet.handlers.SvgPicture.render(bytes, px) ?: return null
        if (!square || drawn.width == drawn.height) return drawn
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(drawn, ((px - drawn.width) / 2).toFloat(), ((px - drawn.height) / 2).toFloat(), null)
        drawn.recycle()
        return out
    }

    // ── video ──

    private fun fromVideo(os: String, px: Int, square: Boolean): Bitmap? {
        videoGate.acquireUninterruptibly()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(os)
            // A frame one second in, not frame zero: the first frame of a phone video is very
            // often black, and a grid of black squares is worse than no thumbnails at all.
            val frame = scaledFrame(retriever, px, square)
                ?: retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTimeCompat()
            frame?.let { fit(it, px, square) }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            videoGate.release()
        }
    }

    /**
     * The frame already brought down to the preview's size by the platform, where it can (Android
     * 8.1 and up). Without it a 4K frame is decoded into a 33 MB bitmap only to be shrunk to a few
     * hundred pixels, which is most of what a video preview cost. The box is square and as wide as
     * the long edge the preview needs, since the platform keeps the frame's shape inside it and the
     * frame may be turned.
     */
    private fun scaledFrame(retriever: MediaMetadataRetriever, px: Int, square: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT < 27) return null
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val (sw, sh) = ThumbSize.scaled(w, h, px, square)
        val box = maxOf(sw, sh)
        return runCatching {
            retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
        }.getOrNull()
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
    private fun fromAudio(os: String, px: Int, square: Boolean): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(os)
            val bytes = retriever.embeddedPicture ?: return null
            // Two-pass decode, same as an image file: cover art is routinely 1200x1200 and
            // decoding that full-size for a 40dp row is most of a megabyte per track.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ThumbSize.sample(bounds.outWidth, bounds.outHeight, px, square)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { fit(it, px, square) }
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
        if (this is BitmapDrawable && bitmap != null) return fit(bitmap, px, square = false)
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

    fun clear() {
        cache.evictAll()
        SystemThumbs.clear()
    }

    /** Drops every Locker picture held in memory. Called at each lock; none is ever on disk. */
    fun forgetPrivate() {
        cache.snapshot().keys.filter(dev.niccc2007.filet.locker.LockerPaths::isPrivate).forEach { cache.remove(it) }
    }
}

/** Blocking read, for the two-pass decode above. Kept here rather than widening the Vfs API. */
private fun Vfs.openReadBlocking(path: VPath) =
    kotlinx.coroutines.runBlocking { openRead(path) }
