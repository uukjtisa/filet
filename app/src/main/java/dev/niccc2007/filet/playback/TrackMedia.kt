package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.niccc2007.filet.data.AppFiles
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turning a path into something a player can open, and reading its tags.
 *
 * ## Why this is its own file
 *
 * Both of these used to live where the one caller was: the URI resolver on `BrowserViewModel`,
 * the tag reader private inside `AudioScreen`. There are three callers now - the screen, the
 * player that outlives it, and the notification - and the player is deliberately not allowed
 * to hold a view model.
 *
 * Copying either of them would have been the obvious move and the wrong one. The resolver
 * carries a cap and a cache-reuse rule that are easy to get subtly different, and the tag
 * reader carries the art-sampling arithmetic. Two copies of "is this file small enough to
 * stage" is two answers to a question with one right answer.
 */
object TrackMedia {

    /**
     * The largest file worth copying into the cache so it can be played.
     *
     * `MediaPlayer` needs a seekable source and cannot take an `InputStream`, so anything with
     * no real path - an entry in an archive, a file on a share - is copied first. Honest but
     * slow, hence the cap: past it the player says so rather than silently copying 2 GB.
     */
    const val CACHE_COPY_LIMIT = 256L * 1024 * 1024

    /** A `Uri` a player can seek in, or null when the file is too large to stage. */
    suspend fun uriFor(vfs: Vfs, files: AppFiles, node: VNode): Uri? {
        vfs.osPath(node.path)?.let { return files.localUri(files.fileAt(it)) }
        if (node.size > CACHE_COPY_LIMIT) return null
        return withContext(Dispatchers.IO) {
            val out = files.playCache(node.name)
            // Reused when it is already there at the right size. A re-open of the same track
            // must not re-copy it off a share, and size is the cheap check that catches a copy
            // that was interrupted half way.
            if (!files.exists(out) || files.size(out) != node.size) {
                vfs.openRead(node.path).use { input ->
                    vfs.openWrite(files.vpath(out)).use { o -> input.copyTo(o) }
                }
            }
            files.localUri(out)
        }
    }

    /** The same, addressed by the id a [Track] carries. Null when the file has gone. */
    suspend fun uriFor(vfs: Vfs, files: AppFiles, id: String): Uri? {
        val path = runCatching { VPath.parse(id) }.getOrNull() ?: return null
        val node = runCatching { vfs.stat(path) }.getOrNull() ?: return null
        return uriFor(vfs, files, node)
    }

    /**
     * Tags as the file itself reports them, with the filename as the fallback for the title.
     *
     * A tagless or malformed file is ordinary rather than an error - it still plays, and the
     * filename is a perfectly good name for it.
     */
    suspend fun tagsFor(
        context: Context,
        vfs: Vfs,
        id: String,
        uri: Uri,
    ): Playback.TrackTags = withContext(Dispatchers.IO) {
        val path = runCatching { VPath.parse(id) }.getOrNull()
        val fallback = (path?.name ?: id).substringBeforeLast('.', path?.name ?: id)
        val r = MediaMetadataRetriever()
        try {
            val os = path?.let { vfs.osPath(it) }
            if (os != null) r.setDataSource(os) else r.setDataSource(context, uri)
            Playback.TrackTags(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: fallback,
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?.takeIf { it.isNotBlank() },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() },
                // A frame, when the file has no embedded picture.
                //
                // This is what gives a video played as audio a picture in the notification and
                // on the lock screen. Without it every video is the generic icon, so a lecture
                // and a clip of somebody's dog look identical in the shade - which is the one
                // place you are looking BECAUSE you cannot see the app.
                //
                // Taken at the same point the player screen's loop starts, so the two agree:
                // not frame zero, which on most things anybody films or edits is black.
                art = r.embeddedPicture?.let { bytes ->
                    // Sampled to roughly a phone's width. Album art is routinely 1500 square,
                    // and decoding that at full size for a 400 px box is 9 MB for nothing.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > ART_MAX || bounds.outHeight / sample > ART_MAX) {
                        sample *= 2
                    }
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                } ?: posterOf(r),
            )
        } catch (_: Throwable) {
            Playback.TrackTags(fallback, null, null, null)
        } finally {
            // MediaMetadataRetriever only became AutoCloseable at API 29 and this app floors
            // at 26.
            runCatching { r.release() }
        }
    }

    /**
     * A still from a video to stand in for cover art, or null for anything that is not one.
     *
     * `OPTION_CLOSEST_SYNC` rather than `OPTION_CLOSEST`: the exact frame means decoding
     * forward from the previous keyframe, and this runs for every track that starts. A
     * keyframe within a second of the mark is the same picture for this purpose.
     *
     * Scaled down on API 27 and up, where the retriever will do it during the decode. Below
     * that it comes back at the video's own size, which for a 4K clip is a 30 MB bitmap held
     * for as long as the track plays - so it is dropped rather than kept.
     */
    private fun posterOf(r: MediaMetadataRetriever): android.graphics.Bitmap? {
        val isVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        if (!isVideo) return null
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val window = dev.niccc2007.filet.playback.VideoPeek.windowOf(duration)
        val atUs = dev.niccc2007.filet.playback.VideoPeek.loopStart(window) * 1000L
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_MAX, POSTER_MAX,
                )
            } else {
                val full = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                full?.takeIf { it.width <= ART_MAX && it.height <= ART_MAX }
                    ?: full?.also { it.recycle() }?.let { null }
            }
        }.getOrNull()
    }

    private const val ART_MAX = 1200

    /** A notification's large icon and a lock screen's background; not a gallery view. */
    private const val POSTER_MAX = 640
}
