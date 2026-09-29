package dev.niccc2007.filet.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Icons for apps that are already installed.
 *
 * ## Why this is not the APK thumbnail loader
 *
 * `Thumbnails.apkIcon` reads an icon out of an APK **file**: it hands the path to
 * `getPackageArchiveInfo`, which parses the archive's resource table before anything can be
 * decoded. That is the right and only way to get the icon of a file you are looking at, and it
 * is far too expensive to do per row in a list of four hundred.
 *
 * An installed app needs none of it. The platform already holds a parsed `ApplicationInfo`, so
 * the icon is one lookup with no archive parsing at all.
 *
 * ## The two things that made the list chop
 *
 * 1. **It ran during composition.** A `remember { decode() }` is not lazy - it executes on the
 *    composing thread, which is the main thread, for every row that scrolls into view. The
 *    frame cannot be drawn until it returns.
 * 2. **Nothing was kept.** Scrolling back up re-decoded everything it had already decoded,
 *    because `remember` dies with the composition the moment a row leaves the viewport.
 *
 * So: off the main thread, and into a cache that outlives the rows.
 */
object AppIcons {

    /**
     * Bounded by bytes rather than by count.
     *
     * An icon's cost is its pixels, and a count-based cap sized for small icons is a memory
     * fault on a device with large ones. 6 MB holds several hundred at this size and is
     * trimmed by the platform under pressure like any other LruCache.
     */
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Already decoded, or null. Cheap enough to call from composition. */
    fun cached(packageName: String): Bitmap? = cache.get(packageName)

    /**
     * The icon for an installed package, decoding it if this is the first time.
     *
     * Suspends and does its work on IO. A caller that wants it now and has nothing to show
     * meanwhile uses [cached] first.
     */
    suspend fun of(context: Context, packageName: String, px: Int): Bitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val drawable: Drawable = runCatching {
                pm.getApplicationIcon(packageName)
            }.getOrNull() ?: return@withContext null
            val bitmap = drawable.toBitmap(px)
            cache.put(packageName, bitmap)
            bitmap
        }
    }

    private fun Drawable.toBitmap(px: Int): Bitmap {
        // An already-rasterised icon at the right size is used as it is. Redrawing it through a
        // Canvas would be a copy for nothing, and most launcher icons arrive this way.
        if (this is BitmapDrawable && bitmap != null && bitmap.width == px && bitmap.height == px) {
            return bitmap
        }
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, px, px)
        draw(Canvas(out))
        return out
    }
}
