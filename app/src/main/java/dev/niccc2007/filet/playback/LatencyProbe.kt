package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the beat's time goes on this phone, measured rather than assumed. Logged under "beat"
 * as lines starting `lat `, and read back with `.unlazy/beat1/readout.sh`.
 *
 * The light used to read the map at `position - output - bias`, `output` being the mixer's
 * buffer doubled plus a Bluetooth constant. That is right only if the player's position is
 * where the DECODER is; if it is already where the SPEAKER is, the output is counted twice and
 * the light runs late by exactly that much. Two measurements say which it is, on the phone,
 * without an ear:
 *
 * - **The start gap.** After `start()`, how long the player's position stands still before it
 *   moves. A position that tracks the speaker waits for the first sound to come out, so the gap
 *   is about the output pipeline; one that tracks the decoder moves almost at once.
 * - **The downstream latency**, from a silent track on the same route: frames the mixer has
 *   taken from it, minus frames the output says it has presented. That is the delay after the
 *   app that the platform itself knows about - Bluetooth included, where the headset reports it.
 *
 * On this phone they said SPEAKER (see [BeatLatency]), and the output term is gone. The probe
 * stays as the check per route: a player on some other phone that reports the decoder logs a
 * start gap near zero against a downstream in the hundreds, and that is the line to read.
 * Nothing here changes what the light does.
 */
object LatencyProbe {

    /** How long the start gap is watched for: longer than any output pipeline worth having. */
    private const val WATCH_MS = 1500L

    /** How often the position is sampled while it is watched. */
    private const val SAMPLE_MS = 5L

    /** A position this far past where it started has moved; under it is rounding. */
    private const val MOVED_MS = 2L

    /** How long the silent track plays. Long enough for the timestamps to settle. */
    private const val PROBE_MS = 900L

    private const val RATE = 48_000

    @Volatile private var probedRoute: String? = null

    /** Its own, so a measurement outlives the screen that started it and finishes its log line. */
    // Lazy: the main dispatcher exists on a phone and not in a unit test of the arithmetic below.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * Watch the position after a start and log when it first moved, then measure the route's
     * downstream latency if this route has not been measured yet.
     *
     * In that order: the silent track joins the mixer only after the start has been watched, so
     * it cannot be what delayed it.
     *
     * @param position the player's position now, or null when it cannot say.
     */
    fun watchStart(context: Context, position: () -> Long?) {
        val app = context.applicationContext
        val route = routeOf(app)
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val samples = ArrayList<Pair<Long, Long>>(64)
            while (true) {
                val now = SystemClock.elapsedRealtime() - started
                position()?.let { samples += now to it }
                if (now >= WATCH_MS || firstMove(samples) != null) break
                delay(SAMPLE_MS)
            }
            val gap = firstMove(samples)
            dev.niccc2007.filet.log.FiletLog.i(
                "beat",
                "lat start gap=${gap?.let { "${it}ms" } ?: "none in ${WATCH_MS}ms"} " +
                    "from=${samples.firstOrNull()?.second}ms samples=${samples.size} $route",
            )
            probeOnce(app)
        }
    }

    /**
     * Measure the downstream latency once for each route, on a silent track, and log it.
     *
     * Once per route because it is a property of the route, and each measurement is most of a
     * second of an extra (silent) track in the mixer.
     */
    fun probeOnce(context: Context) {
        val route = routeOf(context)
        if (probedRoute == route) return
        probedRoute = route
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { downstream() }.getOrNull() }
            dev.niccc2007.filet.log.FiletLog.i(
                "beat",
                "lat downstream=${result?.let { "%.1fms".format(java.util.Locale.US, it.first) } ?: "unmeasured"} " +
                    "readings=${result?.second ?: 0} $route",
            )
        }
    }

    /** What the light is told the output costs, and what it is made of, for the same log. */
    fun routeOf(context: Context): String = BeatLatency.describe(context)

    /** The median of the settled readings and how many there were, or null with none. */
    private fun downstream(): Pair<Double, Int>? {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) return null
            // Twenty milliseconds of silence a write. Silent so nothing is heard; a real track so
            // the route, the mixer and the output are the ones the music is going through.
            val chunk = ShortArray(RATE / 50 * 2)
            val stamp = AudioTimestamp()
            val readings = ArrayList<Double>()
            track.play()
            val until = SystemClock.elapsedRealtime() + PROBE_MS
            while (SystemClock.elapsedRealtime() < until) {
                track.write(chunk, 0, chunk.size)
                if (track.getTimestamp(stamp)) {
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    val presented = presentedAt(stamp.framePosition, stamp.nanoTime, System.nanoTime(), RATE)
                    readings += downstreamMs(head, presented, RATE)
                }
            }
            // The first readings are from before the output had started; only the settled half counts.
            val settled = readings.drop(readings.size / 2)
            val m = median(settled) ?: return null
            return m to settled.size
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Where the output is now, in frames, from a timestamp taken at [stampNanos]. */
    fun presentedAt(framePosition: Long, stampNanos: Long, nowNanos: Long, rate: Int): Double =
        framePosition + (nowNanos - stampNanos) * rate / 1e9

    /** Frames taken by the mixer but not yet presented, in milliseconds. */
    fun downstreamMs(head: Long, presented: Double, rate: Int): Double = (head - presented) * 1000.0 / rate

    /**
     * When the position first moved, in ms after the start, from (ms after start, position)
     * samples - or null if it never did.
     */
    fun firstMove(samples: List<Pair<Long, Long>>): Long? {
        val from = samples.firstOrNull()?.second ?: return null
        return samples.firstOrNull { it.second > from + MOVED_MS }?.first
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** For a test that needs the probe to measure again, as a route change would. */
    internal fun forget() { probedRoute = null }
}
