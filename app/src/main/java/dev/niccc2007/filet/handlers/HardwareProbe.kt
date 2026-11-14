package dev.niccc2007.filet.handlers

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Whether the phone's hardware decoder will really start on a file past its declared limits,
 * found by starting it once, outside the player - and how it has to be asked.
 *
 * ## Why
 *
 * Bug identified here: a 2160 x 3402, 60 fps file was given to the hardware decoder first (see
 * [HardwareFirst]), the decoder refused, and the picture fell to Google's software decoder, which
 * showed five frames a second on that file. Three things were wrong with that:
 *
 * - **The refusal said nothing.** Media3 cleans up a decoder that failed to start, the clean-up
 *   itself throws, and the platform's own reason is lost under an `IllegalStateException` with no
 *   message. Started here, the reason comes back whole and goes into the log.
 * - **It was asked every time.** The answer is the same for the same decoder and the same size and
 *   rate, so it is kept: one test start per kind of file (but see [keep]).
 * - **The wrong software decoder took over.** A refused hardware decoder is no longer read as
 *   "handled" (see [HardwareFirst.upgrade]), so where the build has FFmpeg, FFmpeg's renderer is
 *   the one that answers "handled" and the track selector gives it the picture.
 *
 * ## Asked the way VLC asks
 *
 * With the reason in the log, the refusal turned out to be `error 1100`, not enough resources,
 * from a decoder that VLC and the phone's own player run smoothly on the same file. The difference
 * is the setup. Media3 declares the frame rate, an operating rate equal to it and realtime
 * priority, and the chip budgets a session by its declared rate before it has decoded anything:
 * 2160 x 3402 at 60 is close to nine-tenths of what it declares in all, and it says no. VLC
 * declares none of the three, so the chip admits the session and then clocks itself to the frames
 * that actually arrive.
 *
 * So the test start is made twice when it has to be: as the player would ask ([Start.DECLARED]),
 * and, when that is refused, without the rate and the priority ([Start.UNDECLARED]). A decoder
 * that only starts the second way is set up the second way by the player too - see
 * [HardwareFirst.VideoRenderer]. Declaring the rate is still the first choice, because it tells
 * the chip what clocks to start at instead of leaving it to work that out over the first frames.
 *
 * The test start has no surface, so it is a test of the decoder and of its share of the chip, not
 * of the screen. A decoder that starts here and then fails beside a surface is told apart in the
 * log: the test says it started, and the player's own start says it did not.
 */
@OptIn(UnstableApi::class)
internal object HardwareProbe {

    /** How a decoder starts on a kind of file, if it does. */
    enum class Start {
        /** With the rate and priority declared, as Media3 sets a decoder up. */
        DECLARED,

        /** Only with neither declared, as VLC sets a decoder up. */
        UNDECLARED,

        /** Not at all. */
        REFUSED,
    }

    private class Answer(val start: Start, val busy: Boolean, val atMs: Long, val words: String)

    private val known = ConcurrentHashMap<String, Answer>()

    /** What makes two files the same question for one decoder. Pure. */
    fun key(decoder: String, mime: String?, width: Int, height: Int, fps: Float): String =
        "$decoder|$mime|${width}x$height|${if (fps > 0f) fps.roundToInt() else 0}"

    /**
     * Whether an answer [ageMs] old is still the answer. Pure.
     *
     * Decision: an answer is kept for the run, except one where the chip said it was busy, which
     * is kept for [BUSY_KEEP_MS]. Busy is about the moment, not the file - another app may have
     * been holding a decoder - and the process routinely lives for days behind its foreground
     * service, so a busy answer kept for the run would keep the hardware off this kind of file
     * until the phone restarted. Long enough to cover one opening, which asks several times over a
     * second or two; the next opening asks again.
     */
    fun keep(busy: Boolean, ageMs: Long): Boolean = !busy || ageMs in 0 until BUSY_KEEP_MS

    const val BUSY_KEEP_MS = 20_000L

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun fresh(key: String): Answer? = known[key]?.takeIf { keep(it.busy, now() - it.atMs) }

    private fun keyOf(decoder: String, format: Format) =
        key(decoder, format.sampleMimeType, format.width, format.height, format.frameRate)

    /** Whether [decoder] has been found to refuse files like [format]. Null when not tried lately. */
    fun refused(decoder: String, format: Format): Boolean? = fresh(keyOf(decoder, format))?.let { it.start == Start.REFUSED }

    /**
     * Whether [decoder] was last found to start on files like [format] only with no rate declared.
     * Read however old the answer is: a decoder set up that way also starts where it would have
     * started with the rate declared, so an answer gone stale costs a clock hint at worst, while a
     * stale "declared" sends the player back to the setup that was refused.
     */
    fun undeclared(decoder: String, format: Format): Boolean = known[keyOf(decoder, format)]?.start == Start.UNDECLARED

    /** Whether [decoder] starts on files like [format] one way or the other, trying when not tried lately. */
    fun starts(decoder: String, format: Format): Boolean = answer(decoder, format) != Start.REFUSED

    @Synchronized
    private fun answer(decoder: String, format: Format): Start {
        val key = keyOf(decoder, format)
        fresh(key)?.let { return it.start }
        val began = now()
        val declared = tryStart(decoder, declaredFormat(format))
        val undeclared = if (declared != null && Build.VERSION.SDK_INT >= 29) tryStart(decoder, undeclaredFormat(format)) else null
        val start = when {
            declared == null -> Start.DECLARED
            Build.VERSION.SDK_INT >= 29 && undeclared == null -> Start.UNDECLARED
            else -> Start.REFUSED
        }
        val busy = listOfNotNull(declared, undeclared).any { it.busy }
        val ms = now() - began
        val words = when (start) {
            Start.DECLARED -> "started, $ms ms"
            Start.UNDECLARED -> "refused with the rate declared (${declared?.words}), started without it, $ms ms"
            Start.REFUSED -> "refused (${declared?.words})" +
                (undeclared?.let { "; without the rate declared too (${it.words})" } ?: "") + ", $ms ms"
        }
        known[key] = Answer(start, busy, now(), words)
        dev.niccc2007.filet.log.FiletLog.i("video") {
            "test start of $decoder for ${format.width}x${format.height} @${format.frameRate}: $words"
        }
        return start
    }

    /** The words of the last test start of [decoder] on files like [format], or null if none. */
    fun words(decoder: String, format: Format): String? = known[keyOf(decoder, format)]?.words

    /**
     * What the last look for a hardware decoder found for each kind of file, for Playback stats.
     *
     * Bug identified here: a picture on FFmpeg said only "FFmpeg, software", and that one line
     * reads the same for three different causes - no hardware decoder knows the file's profile, one
     * knows it and refused to start, or none was asked because the phone's renderer declared the
     * file within its limits and lost to FFmpeg some other way. A screenshot of the stats from a
     * second phone could not say which, so the fix could only be guessed. This says which.
     */
    private val looks = ConcurrentHashMap<String, String>()

    /** Kept for Playback stats. See [looks]. */
    fun noteLook(format: Format, words: String) {
        looks[key("", format.sampleMimeType, format.width, format.height, format.frameRate)] = words
    }

    /** The look for a file of this kind, if one was made. See [looks]. */
    fun lookFor(mime: String?, width: Int, height: Int, fps: Float): String? = looks[key("", mime, width, height, fps)]

    /** The line [noteLook] keeps, from what was found. Pure. */
    fun lookWords(profile: String?, tried: List<Pair<String, String?>>): String =
        if (tried.isEmpty()) {
            "no hardware decoder knows " + (profile ?: "this file's profile")
        } else {
            tried.joinToString("; ") { (name, words) -> name + ": " + (words ?: "not tried") }
        }

    /** [format] as Media3 hands it to the decoder: the frame rate, an operating rate equal to it, realtime priority. */
    private fun declaredFormat(format: Format): MediaFormat =
        MediaFormatUtil.createMediaFormatFromFormat(format).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (format.frameRate > 0f) setFloat(MediaFormat.KEY_OPERATING_RATE, format.frameRate)
        }

    /** [format] as VLC hands it to the decoder: none of the three. API 29 and up, where a key can be taken out. */
    private fun undeclaredFormat(format: Format): MediaFormat =
        MediaFormatUtil.createMediaFormatFromFormat(format).apply { undeclare(this) }

    /** Takes out of [format] everything that declares a rate or a priority. API 29 and up. */
    fun undeclare(format: MediaFormat) {
        if (Build.VERSION.SDK_INT < 29) return
        format.removeKey(MediaFormat.KEY_FRAME_RATE)
        format.removeKey(MediaFormat.KEY_OPERATING_RATE)
        format.removeKey(MediaFormat.KEY_PRIORITY)
    }

    /** Why a test start failed: at which step, in words, and whether the chip said it was busy. */
    private class Failure(val words: String, val busy: Boolean)

    /** Null when [decoder] configured and started on [format]; else why it did not. */
    private fun tryStart(decoder: String, format: MediaFormat): Failure? {
        var codec: MediaCodec? = null
        var stage = "create"
        return try {
            codec = MediaCodec.createByCodecName(decoder)
            stage = "configure"
            codec.configure(format, null, null, 0)
            stage = "start"
            codec.start()
            runCatching { codec.stop() }
            null
        } catch (e: Exception) {
            val code = (e as? MediaCodec.CodecException)?.let { runCatching { it.errorCode }.getOrDefault(0) } ?: 0
            Failure("$stage: " + describe(e, code), busy = code == BUSY || code == TAKEN_BACK)
        } finally {
            runCatching { codec?.release() }
        }
    }

    /** `MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE` and `ERROR_RECLAIMED`. */
    private const val BUSY = 1100
    private const val TAKEN_BACK = 1101

    /** The exception with what the platform attached to it: the code and the diagnostic. */
    private fun describe(e: Exception, code: Int): String {
        if (e is MediaCodec.CodecException) {
            return HardwareFirst.reasonWords("CodecException", e.diagnosticInfo ?: e.message, code)
        }
        return HardwareFirst.reasonWords(e.javaClass.simpleName, e.message, null).let {
            if (it == e.javaClass.simpleName) it else "${e.javaClass.simpleName}: $it"
        }
    }
}
