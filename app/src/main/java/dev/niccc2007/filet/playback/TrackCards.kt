package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.niccc2007.filet.media.LoadQueue
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.delay
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
    /**
     * Whether this file HAS a picture, as distinct from whether one is loaded.
     *
     * The tags are kept for the session and the pictures are evicted under memory pressure, so
     * a null `art` is ambiguous without this: "this file has no cover" and "its cover was
     * dropped to free memory" need different answers, and guessing the first means a cover that
     * is evicted once never comes back.
     */
    val hasArt: Boolean = false,
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

    // ── the cache was one map, and that was the hang ───────────────────────────────────────
    //
    // Bug identified here: this was a single `ConcurrentHashMap` with NO eviction, and every
    // entry holds a 160x160 ARGB bitmap - 102 KB each. Scrolling fifteen hundred tracks of All
    // Music therefore retained about 150 MB of thumbnails on a device whose whole heap may be
    // 192 MB. The collector then runs continuously, and a collection pause stops the main
    // thread: the symptom was a hang of under a second that got WORSE the longer the list was
    // scrolled, which is the signature of heap pressure rather than of slow decoding.
    //
    // Split in two, because the two halves have nothing in common. Tags are a few dozen bytes
    // and are worth keeping for the whole session - that is what makes a second pass over a
    // list free. Pictures are four orders of magnitude bigger and are worth keeping only while
    // they are near the screen.
    private val tags = ConcurrentHashMap<String, TrackCard>()

    private val art = object : LruCache<String, Bitmap>(ART_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val queue = LoadQueue(workers = AT_ONCE)

    /** What is already known, or a holding card carrying the filename. */
    fun cached(path: VPath): TrackCard {
        val key = path.toString()
        tags[key]?.let { return it.copy(art = art.get(key)) }
        return holding(path)
    }

    private fun holding(path: VPath) = TrackCard(
        title = path.name.substringBeforeLast('.', path.name),
        artist = null,
        album = null,
        durationMs = 0,
        art = null,
        pending = true,
    )

    /** For a row on screen. */
    suspend fun of(path: VPath): TrackCard = fill(path, urgent = true)

    /**
     * For a row that is not on screen yet - the pre-render ahead of the viewport.
     *
     * Identical work at a lower priority: it yields to every row somebody is actually looking
     * at. See [LoadQueue] for why the two tiers cannot be one.
     */
    suspend fun warm(path: VPath): TrackCard = fill(path, urgent = false)

    private suspend fun fill(path: VPath, urgent: Boolean): TrackCard {
        val key = path.toString()
        tags[key]?.let { held ->
            // Read already, and either its picture is still here or it never had one.
            if (!held.hasArt || art.get(key) != null) return held.copy(art = art.get(key))
        }
        val body = { store(key, read(path)) }
        if (urgent) queue.join(key, body) else queue.ahead(key, body)
        return cached(path)
    }

    /** Tags into the map that keeps them, the picture into the one that evicts. */
    private fun store(key: String, card: TrackCard) {
        tags[key] = card.copy(art = null, hasArt = card.art != null)
        card.art?.let { art.put(key, it) }
    }

    /** Forget everything, for the refresh button: a re-tagged file should re-read. */
    fun clear() {
        tags.clear()
        art.evictAll()
    }

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

        /**
         * How many files are parsed at once. A screen of rows must not open a screen of files.
         *
         * Two, not three: a header parse and a bitmap decode are both CPU, and on the mid-range
         * phone this was reported on the main thread needs a core of its own in order to draw
         * the rows the parses are for.
         */
        const val AT_ONCE = 2

        /**
         * What the pictures may occupy. A tenth of the heap, clamped.
         *
         * At 102 KB a thumbnail that is roughly forty on a small heap and two hundred on a large
         * one - a screenful either way, with enough margin that scrolling back up a screen or
         * two is a cache hit rather than a re-read.
         */
        val ART_BYTES: Int = (Runtime.getRuntime().maxMemory() / 10)
            .coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024)
            .toInt()
    }
}

/**
 * One row's card, loaded off the main thread and remembered.
 *
 * Starts from whatever is cached, so a row that has been seen before draws complete on the
 * first frame instead of flashing its filename and then correcting itself.
 */
@Composable
fun rememberTrackCard(cards: TrackCards, path: VPath): State<TrackCard> {
    val start = cards.cached(path)

    // Already read: no producer at all.
    //
    // A list recomposes constantly while it is scrolled, and `produceState` launched a coroutine
    // for every row on every key change in order to ask a question whose answer was already in
    // the map. Scrolling back up a playlist should cost a lookup.
    if (!start.pending) return remember(path) { mutableStateOf(start) }

    return produceState(start, path) {
        // Let a fling or a scrubber drag pass before queueing a header parse.
        //
        // The gate inside `of` runs three reads at a time, and cancelling this producer does
        // drop a read still waiting its turn - so the backlog was self-limiting and this is not
        // the disaster the thumbnail path had. It is still three `MediaMetadataRetriever`
        // parses that nobody will see for every screenful swept past, and on the device that was
        // reported as hot it is three parses competing with audio decoding. A row that is still
        // here after this delay is a row somebody has stopped on.
        delay(SETTLE_MS)
        value = cards.of(path)
    }
}

/** How long a row must stay on screen before its tags are worth a header parse. */
private const val SETTLE_MS = 90L
