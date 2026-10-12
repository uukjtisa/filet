package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What a track looks like in a list: its real name, who made it, how long it is, and a thumb.
 *
 * @param video true when the file carries a picture track, so a list can say which of its
 *   rows is a video rather than leaving somebody to wonder why that one has no artist.
 * @param pending true until the file has actually been read, so a row can show the filename
 *   rather than flashing an empty line and then filling in.
 */
data class TrackCard(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val art: Bitmap?,
    val video: Boolean = false,
    val pending: Boolean = false,
) {
    /** `3:41`, or empty when the file would not say. */
    val clock: String
        get() {
            if (durationMs <= 0) return ""
            val total = durationMs / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }

    /** The second line: who made it, or what folder it came out of. */
    fun byline(path: VPath): String =
        artist?.takeIf { it.isNotBlank() }
            ?: album?.takeIf { it.isNotBlank() }
            ?: dev.niccc2007.filet.handlers.folderContext(path)
}

/**
 * Tags for a list, read once and kept.
 *
 * ## Why this is not `TrackMedia.tagsFor`
 *
 * That one is for the thing that is PLAYING: it decodes the cover at roughly a phone's width
 * because it fills a 400dp square and a lock screen. A playlist of two hundred rows doing
 * that is two hundred full-size bitmaps for forty-pixel thumbnails, and the first scroll
 * would be the last thing that phone did.
 *
 * So: a small thumbnail, a hard cap on how many files are open at once, and a cache. The
 * cache is the point - a `MediaMetadataRetriever` costs a header parse each time, and a list
 * recomposes whenever anything in it changes.
 *
 * ## Why the reads are throttled
 *
 * A `LazyColumn` composes a screen of rows at once, and each one starting its own retriever
 * means twelve simultaneous header parses on a phone that is also decoding audio. The
 * semaphore keeps it to [AT_ONCE], which is enough to hide the latency and few enough that
 * scrolling stays smooth.
 */
class TrackCards(private val context: Context, private val vfs: Vfs) {

    private val cache = ConcurrentHashMap<String, TrackCard>()
    private val gate = Semaphore(AT_ONCE)

    /** What is already known, or a holding card carrying the filename. */
    fun cached(path: VPath): TrackCard =
        cache[path.toString()] ?: TrackCard(
            title = path.name.substringBeforeLast('.', path.name),
            artist = null,
            album = null,
            durationMs = 0,
            art = null,
            pending = true,
        )

    suspend fun of(path: VPath): TrackCard {
        cache[path.toString()]?.let { return it }
        val read = gate.withPermit { withContext(Dispatchers.IO) { read(path) } }
        cache[path.toString()] = read
        return read
    }

    /** Forget everything, for the refresh button: a re-tagged file should re-read. */
    fun clear() = cache.clear()

    private fun read(path: VPath): TrackCard {
        val fallback = path.name.substringBeforeLast('.', path.name)
        val r = MediaMetadataRetriever()
        return try {
            // A file on a share is NOT staged for a list row. Copying two hundred files off
            // a network drive to put names on a screen is a far worse outcome than the names
            // staying as the filenames, so a path with no local file keeps the fallback.
            val os = vfs.osPath(path) ?: return TrackCard(fallback, null, null, 0, null)
            r.setDataSource(os)
            // Asked once and passed down: the same answer decides whether a frame is worth
            // grabbing and whether the row says "video", and it is a metadata lookup either
            // way.
            val isVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            TrackCard(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: fallback,
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?.takeIf { it.isNotBlank() },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() },
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                art = thumb(r, isVideo),
                video = isVideo,
            )
        } catch (_: Throwable) {
            // A file that has gone, or one nothing can parse. Both are ordinary in a list of
            // paths somebody saved last month.
            TrackCard(fallback, null, null, 0, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** A thumbnail, or a frame for a video. Decoded straight to list size. */
    private fun thumb(r: MediaMetadataRetriever, isVideo: Boolean): Bitmap? {
        r.embeddedPicture?.let { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > THUMB || bounds.outHeight / sample > THUMB) {
                sample *= 2
            }
            return runCatching {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
        // Only for a video, and only one frame: `getFrameAtTime` on an audio file is a parse
        // that always returns null, and doing it per row is the cost this class exists to
        // avoid paying.
        if (!isVideo) return null
        return runCatching {
            r.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMB,
                THUMB,
            )
        }.getOrNull()
    }

    private companion object {
        /** Big enough for a 44dp tile on a 3x phone, small enough to hold two hundred of. */
        const val THUMB = 160

        /** How many files are parsed at once. A screen of rows must not open a screen of files. */
        const val AT_ONCE = 3
    }
}

/**
 * One row's card, loaded off the main thread and remembered.
 *
 * Starts from whatever is cached, so a row that has been seen before draws complete on the
 * first frame instead of flashing its filename and then correcting itself.
 */
@Composable
fun rememberTrackCard(cards: TrackCards, path: VPath): State<TrackCard> =
    produceState(cards.cached(path), path) { value = cards.of(path) }
