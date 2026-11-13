package dev.niccc2007.filet.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Previews written down, so a picture decoded once is never decoded from its file again.
 *
 * Bug identified here: the preview cache lived in memory only, so every start of the app, and
 * every scroll back past what memory held, decoded the photos from the files again - a 12
 * megapixel JPEG is tens of milliseconds even sampled down, a video frame several hundred - and
 * a gallery scrolled at speed showed empty squares while the phone's own gallery, which keeps
 * its previews on disk, showed pictures. Read back from here a preview is a few milliseconds.
 *
 * In the app's cache folder, which the system empties when storage runs low: losing it costs a
 * decode, nothing else. One folder per file, named from its path, so [forget] can drop every size
 * held for a file without knowing which sizes there are; the file inside is named from the rest
 * of the key - size, time, decode size and shape - so an edited file never finds its old picture.
 * Written on a thread of its own, after the picture is already on screen.
 */
internal object ThumbDisk {

    /** Kept to about this much; the oldest-read go first. */
    const val LIMIT_BYTES = 256L * 1024 * 1024

    /** Trimmed again after this many writes, and once a run. */
    private const val TRIM_EVERY = 400

    private const val QUALITY = 88

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "filet-thumb-disk").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val writes = AtomicInteger(0)
    @Volatile private var trimmedThisRun = false

    private fun root(context: Context) = File(context.cacheDir, "thumbs")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fileFor(context: Context, path: String, rest: String) =
        File(File(root(context), hash(path)), hash(rest))

    /** The picture kept for [path] under [rest], or null. Marks it read, for the trim. */
    fun read(context: Context, path: String, rest: String): Bitmap? {
        val f = fileFor(context, path, rest)
        if (!f.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
        if (bitmap == null) {
            runCatching { f.delete() }
            return null
        }
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return bitmap
    }

    /**
     * Keeps [bitmap] for [path] under [rest], off the caller's thread. JPEG for an opaque picture
     * and PNG for one with transparency (an app icon, an SVG), since JPEG has none and would draw
     * it on black.
     */
    fun write(context: Context, path: String, rest: String, bitmap: Bitmap) {
        val app = context.applicationContext
        writer.execute {
            val f = fileFor(app, path, rest)
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part")
                tmp.outputStream().use { out ->
                    if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                }
                if (!tmp.renameTo(f)) tmp.delete()
            }
            if (!trimmedThisRun || writes.incrementAndGet() % TRIM_EVERY == 0) {
                trimmedThisRun = true
                trim(app)
            }
        }
    }

    /** Drops every size kept for [path]. */
    fun forget(context: Context, path: String) {
        val app = context.applicationContext
        writer.execute { runCatching { File(root(app), hash(path)).deleteRecursively() } }
    }

    private fun trim(context: Context) {
        val files = root(context).listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
        val gone = ThumbTrim.drop(files.map { ThumbTrim.Kept(it.path, it.length(), it.lastModified()) }, LIMIT_BYTES)
        for (p in gone) runCatching { File(p).delete() }
        root(context).listFiles()?.forEach { dir -> if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete() }
    }
}

/** Which kept previews go when the folder is over its limit. Pure. */
internal object ThumbTrim {

    data class Kept(val path: String, val bytes: Long, val readAt: Long)

    /**
     * The paths to delete so what is left is at most [limit] bytes, the longest unread first.
     * Brought down to nine-tenths of the limit rather than to it, so the next few writes do not
     * each set off another trim.
     */
    fun drop(files: List<Kept>, limit: Long): List<String> {
        val total = files.sumOf { it.bytes }
        if (total <= limit) return emptyList()
        val target = limit / 10 * 9
        var left = total
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.readAt }) {
            if (left <= target) break
            out += f.path
            left -= f.bytes
        }
        return out
    }
}
