package dev.niccc2007.filet.playback

import android.content.Context
import dev.niccc2007.filet.data.FiletStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decoded track and its beat map, kept on disk between sittings.
 *
 * ## Why
 *
 * Reading a four-minute file and measuring its spectrum are each a second or two, and both
 * answers are the same every time. Holding them in memory meant paying again after the
 * process had been away - which on a file manager is most of the time, because people open
 * it, do something, and leave. Reported simply as everything taking so long.
 *
 * A beat map is tiny, seventy kilobytes for three minutes, and is worth keeping for almost
 * nothing. The samples are ten megabytes, and keeping those is a real decision: see [CAP].
 *
 * ## What is NOT done here
 *
 * No integrity check beyond the header. A cache entry is keyed on the track's URI and its
 * own length and modification time, so a file edited in place gets a different key and the
 * old entry simply ages out. Hashing ten megabytes to confirm what the filesystem already
 * said would cost more than the read it is protecting.
 *
 * Everything is best-effort. A cache that throws is a cache that has made the app worse, so
 * every path here returns null or does nothing rather than failing.
 */
object AudioCache {

    private const val PCM_MAGIC = 0x4650_434D // "FPCM"
    private const val BEAT_MAGIC = 0x4642_4541 // "FBEA"
    /**
     * Two, because a beat file now carries the rate it was measured at.
     *
     * A reader of version 1 cannot know it, and guessing would reintroduce exactly the timing
     * error the rate is there to remove. The check below rejects the old entries and they are
     * measured again once - which costs a second, on first play, never again.
     */
    private const val VERSION = 2

    /**
     * Three for the beat maps alone: a version 3 map's hits are on the tracked beat grid rather
     * than on every onset (see BeatGrid). A version 2 map is not wrong to read, only measured
     * the old way, and serving it would keep the off-beat pulses on every track already heard -
     * so it is rejected and measured again once. The decoded samples are untouched, which is
     * why this is not [VERSION]: bumping that would throw away eighty megabytes of decodes to
     * change a file of seventy kilobytes.
     */
    private const val BEAT_VERSION = 3

    /**
     * How much disk the samples may use.
     *
     * Eighty megabytes, which is seven or eight tracks - comfortably the album somebody is
     * listening to - and is deleted by the system along with everything else in the cache
     * directory when the phone needs the space. The beat maps are not counted against it;
     * a thousand of them would be seventy megabytes and nobody has a thousand.
     */
    private const val CAP = 80L * 1024 * 1024

    /**
     * The samples. Large, derived, and evicted here under [CAP].
     *
     * In shared storage rather than `cacheDir` so that the eviction is OURS and visible rather
     * than the system's and silent - see [FiletStore].
     */
    private fun pcmDir(context: Context): File? = runCatching {
        FiletStore.dir(context, FiletStore.Area.AUDIO)
    }.getOrNull()?.takeIf { it.isDirectory }

    /**
     * The beat maps. A separate folder, and that separation is the point.
     *
     * Bug identified here: both lived in `cacheDir`, which Android empties whenever the device
     * wants the space - so a map produced by two seconds of spectrum analysis survived only
     * until the next time the phone was short of room, and a track measured on Monday was
     * measured again on Friday. Worse, [trim] deleted a track's map along with its samples, so
     * the permanent half of the cache was evicted on the schedule of the disposable half.
     *
     * Seventy kilobytes for three minutes. A thousand tracks is seventy megabytes and nobody
     * has a thousand, so there is no eviction here at all - the folder is visible and somebody
     * who wants the space can empty it.
     */
    private fun beatDir(context: Context): File? = runCatching {
        FiletStore.dir(context, FiletStore.Area.BEATS)
    }.getOrNull()?.takeIf { it.isDirectory }

    /**
     * The name a track's entries live under.
     *
     * Length and modification time as well as the path, so a file replaced in place does
     * not come back as whatever used to be there.
     */
    fun keyFor(context: Context, uri: android.net.Uri): String? = runCatching {
        var size = -1L
        var mtime = -1L
        context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            size = f.length()
            mtime = f.lastModified()
        }
        if (size <= 0L) return null
        val raw = "$uri|$size|$mtime"
        var h = -0x7ee3_623bL
        for (c in raw) {
            h = h xor c.code.toLong()
            h *= 0x0100_0000_01b3L
        }
        java.lang.Long.toHexString(h)
    }.getOrNull()

    // ── samples ───────────────────────────────────────────────────────────────────

    fun readPcm(context: Context, key: String): TrackPcm? = runCatching {
        val file = File(pcmDir(context) ?: return null, "$key.pcm")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 12) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != PCM_MAGIC || buffer.int != VERSION) return null
        val rate = buffer.int
        if (rate !in 4_000..192_000) return null
        val shorts = ShortArray((bytes.size - 12) / 2)
        buffer.asShortBuffer().get(shorts)
        file.setLastModified(System.currentTimeMillis())
        TrackPcm(shorts, rate)
    }.getOrNull()

    fun writePcm(context: Context, key: String, pcm: TrackPcm) {
        runCatching {
            val folder = pcmDir(context) ?: return
            val buffer = ByteBuffer.allocate(12 + pcm.samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(PCM_MAGIC).putInt(VERSION).putInt(pcm.rate)
            buffer.asShortBuffer().put(pcm.samples)
            // Written beside and renamed, so a read never finds half a file: a process
            // killed mid-write would otherwise leave an entry that parses and is wrong.
            val temp = File(folder, "$key.pcm.part")
            temp.writeBytes(buffer.array())
            temp.renameTo(File(folder, "$key.pcm"))
            trim(folder)
        }
    }

    // ── beat maps ─────────────────────────────────────────────────────────────────

    fun readBeats(context: Context, key: String): BeatMap? = runCatching {
        val file = File(beatDir(context) ?: return null, "$key.beat")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 12) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != BEAT_MAGIC || buffer.int != BEAT_VERSION) return null
        val n = buffer.int
        val rate = buffer.int
        if (n <= 0 || bytes.size != 16 + n * 8) return null
        if (rate !in 4_000..192_000) return null
        val onsets = FloatArray(n)
        val levels = FloatArray(n)
        val floats = buffer.asFloatBuffer()
        floats.get(onsets)
        floats.get(levels)
        file.setLastModified(System.currentTimeMillis())
        BeatMap(onsets, levels, rate)
    }.getOrNull()

    fun writeBeats(
        context: Context,
        key: String,
        onsets: FloatArray,
        levels: FloatArray,
        rate: Int,
    ) {
        runCatching {
            if (onsets.isEmpty() || onsets.size != levels.size) return
            val folder = beatDir(context) ?: return
            val buffer = ByteBuffer.allocate(16 + onsets.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(BEAT_MAGIC).putInt(BEAT_VERSION).putInt(onsets.size).putInt(rate)
            val floats = buffer.asFloatBuffer()
            floats.put(onsets)
            floats.put(levels)
            val temp = File(folder, "$key.beat.part")
            temp.writeBytes(buffer.array())
            temp.renameTo(File(folder, "$key.beat"))
        }
    }

    /**
     * Least recently read goes first, which for a music cache is the right order.
     *
     * Samples ONLY. It used to delete each evicted track's beat map alongside its samples,
     * which threw away the expensive half to reclaim space taken by the cheap half: the map is
     * seventy kilobytes and two seconds of analysis, the samples are ten megabytes and one
     * second of decoding. The map now outlives the samples it was measured from, which is the
     * whole reason a track is only ever analysed once.
     */
    private fun trim(folder: File) {
        val pcm = folder.listFiles { f -> f.name.endsWith(".pcm") }?.toMutableList() ?: return
        var total = pcm.sumOf { it.length() }
        if (total <= CAP) return
        pcm.sortBy { it.lastModified() }
        for (f in pcm) {
            if (total <= CAP) break
            total -= f.length()
            f.delete()
        }
    }
}
