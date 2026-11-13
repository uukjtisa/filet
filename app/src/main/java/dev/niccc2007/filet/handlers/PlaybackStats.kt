package dev.niccc2007.filet.handlers

import kotlin.math.roundToInt

/**
 * What the video player's Playback stats readout shows, and the arithmetic behind it. Pure.
 *
 * ## Why it exists
 *
 * "The picture jerks while the sound is smooth" has four different causes that look the same on
 * the screen, and each has a different fix:
 *
 * - **A software decoder.** The phone's hardware decoder was passed over (see [HardwareFirst]).
 *   The decoder line names it.
 * - **A decoder that cannot keep up.** Frames come out late and are dropped. Frames are then
 *   ready *late* or barely early, and the dropped count climbs.
 * - **Frames ready in time but shown unevenly.** Nothing is dropped, yet motion stutters: the
 *   screen's rate and the video's rate do not divide, or the picture is composed badly. The
 *   screen line is the one that tells this case apart.
 * - **A starved buffer.** Streamed files only: the buffer line runs down to nothing.
 *
 * Guessing between these cost three rounds of fixes. One screenshot of this readout names the
 * case.
 *
 * ## The counters
 *
 * Engines report cumulative counts ([Snapshot]); the readout shows rates, made from two
 * snapshots a second apart ([rates]). Cumulative, because that is what both Media3's
 * `DecoderCounters` and `MediaPlayer`'s metrics keep, and a rate taken from them cannot miss a
 * frame between two reads.
 */
internal object PlaybackStats {

    /**
     * What an engine can say about itself right now. Counts are totals since the engine started;
     * anything the engine cannot report is null.
     */
    data class Snapshot(
        val atMs: Long,
        val pictureDecoder: String?,
        /** True for a hardware decoder, false for software, null when not known. */
        val hardware: Boolean?,
        val soundDecoder: String?,
        val mime: String?,
        val width: Int,
        val height: Int,
        val fps: Float,
        val bitrate: Int,
        val shown: Long?,
        val dropped: Long?,
        /** Frames thrown away to reach the next keyframe when the decoder fell far behind. */
        val toKeyframe: Long? = null,
        /** The sum and count of how early each frame was ready, in microseconds. See [Rates.earlyMs]. */
        val offsetUsTotal: Long? = null,
        val offsetCount: Long? = null,
        val bufferedMs: Long?,
        val speed: Float = 1f,
        /** Decoders that would not start before the one drawing the picture, each with why. */
        val refused: List<String> = emptyList(),
    )

    /** Per-second figures from two snapshots. */
    data class Rates(
        val shownPerSec: Int?,
        val droppedPerSec: Int?,
        /**
         * How early, on average, a frame was ready before it was due, in the last window. Positive
         * is early, negative is late. A decoder keeping up is comfortably positive; one that is not
         * hovers near zero or below, and its frames are the ones dropped.
         */
        val earlyMs: Int?,
    )

    /** Rates over the time between [before] and [now]. Null figures where either side has no count. */
    fun rates(before: Snapshot?, now: Snapshot): Rates {
        if (before == null) return Rates(null, null, null)
        val secs = (now.atMs - before.atMs) / 1000f
        if (secs <= 0f) return Rates(null, null, null)
        fun perSec(a: Long?, b: Long?): Int? =
            if (a == null || b == null || b < a) null else ((b - a) / secs).roundToInt()
        // The sum is of signed offsets and may fall; the count may not, except when the decoder was
        // made again and its counters started over - which this window then cannot speak for.
        val early = run {
            val n0 = before.offsetCount ?: return@run null
            val n1 = now.offsetCount ?: return@run null
            val s0 = before.offsetUsTotal ?: return@run null
            val s1 = now.offsetUsTotal ?: return@run null
            val n = n1 - n0
            if (n <= 0L) null else ((s1 - s0) / n / 1000L).toInt()
        }
        return Rates(perSec(before.shown, now.shown), perSec(before.dropped, now.dropped), early)
    }

    /** The codec's everyday name, from its MIME type. */
    fun codecName(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        "video/mjpeg", "video/x-motion-jpeg" -> "Motion JPEG"
        else -> mime.substringAfter('/').uppercase()
    }

    /**
     * Whether a decoder is software, read from its name: for phones before Android 10, which
     * cannot be asked. Google's own (`c2.android.`, `OMX.google.`) and FFmpeg are software; a
     * vendor's name is taken as hardware, which is what every vendor decoder on a phone is.
     */
    fun softwareByName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.startsWith("ffmpeg") ||
            n.contains(".sw.") || n.endsWith(".sw")
    }

    /** The readout, line by line: a label and its value. */
    fun lines(now: Snapshot, rates: Rates, screenHz: Float?, heat: String?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val kind = when (now.hardware) {
            true -> "hardware"
            false -> "software"
            null -> null
        }
        out += "Picture" to listOfNotNull(now.pictureDecoder ?: "not started", kind).joinToString(" · ")
        // The first two: one line says why the picture is on a slower decoder, and the retry of
        // the same decoder is the second. More would push the frame counts off the box.
        for (r in now.refused.take(2)) out += "Refused" to r
        val video = listOfNotNull(
            codecName(now.mime),
            if (now.width > 0 && now.height > 0) "${now.width}×${now.height}" else null,
            if (now.fps > 0f) fps(now.fps) + " fps" else null,
            if (now.bitrate > 0) mbps(now.bitrate) else null,
        )
        if (video.isNotEmpty()) out += "Video" to video.joinToString(" · ")
        val shown = rates.shownPerSec?.let { "$it/s" } ?: "…"
        val dropped = rates.droppedPerSec?.let { "$it/s" } ?: "…"
        val total = if (now.dropped != null && now.shown != null) {
            " (${now.dropped} of ${now.shown + now.dropped})"
        } else {
            ""
        }
        out += "Frames" to "shown $shown · dropped $dropped$total"
        rates.earlyMs?.let { ms ->
            out += "Ready" to if (ms >= 0) "$ms ms before due" else "${-ms} ms late"
        }
        now.toKeyframe?.takeIf { it > 0 }?.let { out += "Caught up" to "$it frames skipped to a keyframe" }
        now.bufferedMs?.let { out += "Buffer" to String.format(java.util.Locale.ROOT, "%.1f s ahead", it / 1000f) }
        val screen = listOfNotNull(screenHz?.let { fps(it) + " Hz" }, heat?.let { "phone $it" })
        if (screen.isNotEmpty()) out += "Screen" to screen.joinToString(" · ")
        if (now.speed != 1f) out += "Speed" to fps(now.speed) + "×"
        now.soundDecoder?.let { out += "Sound" to it }
        return out
    }

    /** 59.94 as "59.94", 60.0 as "60". */
    private fun fps(v: Float): String {
        val r = (v * 100f).roundToInt() / 100f
        return if (r == r.roundToInt().toFloat()) r.roundToInt().toString() else r.toString()
    }

    private fun mbps(bps: Int): String =
        if (bps >= 1_000_000) String.format(java.util.Locale.ROOT, "%.1f Mb/s", bps / 1_000_000f) else "${bps / 1000} kb/s"

    /** The phone's thermal state in words, from `PowerManager`'s status number. Null for none. */
    fun heat(status: Int): String? = when (status) {
        1 -> "warm"
        2 -> "hot"
        3 -> "hot, slowed down"
        4 -> "very hot, slowed right down"
        5, 6 -> "overheating"
        else -> null
    }
}
