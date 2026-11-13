package dev.niccc2007.filet.handlers

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Whether the phone's hardware decoder will really start on a file past its declared limits,
 * found by starting it once, outside the player.
 *
 * ## Why
 *
 * Bug identified here: a 2160 x 3402, 60 fps file was given to the hardware decoder first (see
 * [HardwareFirst]), the decoder refused, and the picture fell to Google's software decoder, which
 * showed five frames a second on that file. Three things were wrong with that, and this answers
 * all three:
 *
 * - **The refusal said nothing.** Media3 cleans up a decoder that failed to start, the clean-up
 *   itself throws, and the platform's own reason is lost under an `IllegalStateException` with no
 *   message. Started here, the reason comes back whole and goes into the log.
 * - **It was asked every time.** The answer is the same for the same decoder and the same size and
 *   rate, so it is kept for the run: one test start per kind of file.
 * - **The wrong software decoder took over.** A refused hardware decoder is no longer read as
 *   "handled" (see [HardwareFirst.upgrade]), so where the build has FFmpeg, FFmpeg's renderer is
 *   the one that answers "handled" and the track selector gives it the picture. FFmpeg decodes
 *   on every core, as VLC's software decoder does; Google's decoder managed a tenth of the frames.
 *   Without FFmpeg (the F-Droid build) Google's decoder is still what is left.
 *
 * The test start has no surface, so it is a test of the decoder and of its share of the chip, not
 * of the screen. A decoder that starts here and then fails beside a surface is told apart in the
 * log: the test says it started, and the player's own start says it did not.
 */
@OptIn(UnstableApi::class)
internal object HardwareProbe {

    private val known = ConcurrentHashMap<String, Boolean>()

    /** What makes two files the same question for one decoder. Pure. */
    fun key(decoder: String, mime: String?, width: Int, height: Int, fps: Float): String =
        "$decoder|$mime|${width}x$height|${if (fps > 0f) fps.roundToInt() else 0}"

    /** Whether [decoder] has been found to refuse files like [format] in this run. Null when not tried. */
    fun refused(decoder: String, format: Format): Boolean? =
        known[key(decoder, format.sampleMimeType, format.width, format.height, format.frameRate)]?.not()

    /** Whether [decoder] starts on files like [format], trying once and keeping the answer. */
    fun starts(decoder: String, format: Format): Boolean =
        known.getOrPut(key(decoder, format.sampleMimeType, format.width, format.height, format.frameRate)) {
            val began = android.os.SystemClock.uptimeMillis()
            val failure = tryStart(decoder, MediaFormatUtil.createMediaFormatFromFormat(format))
            val ms = android.os.SystemClock.uptimeMillis() - began
            dev.niccc2007.filet.log.FiletLog.i("video") {
                "test start of $decoder for ${format.width}x${format.height} @${format.frameRate}: " +
                    (failure?.let { "refused ($it), $ms ms" } ?: "started, $ms ms")
            }
            if (failure != null) {
                // For the record only: whether the same decoder takes the size laid on its side,
                // which it declares it can. Never used to play.
                val turned = MediaFormatUtil.createMediaFormatFromFormat(format).apply {
                    setInteger(MediaFormat.KEY_WIDTH, format.height)
                    setInteger(MediaFormat.KEY_HEIGHT, format.width)
                }
                val sideways = tryStart(decoder, turned)
                dev.niccc2007.filet.log.FiletLog.i("video") {
                    "test start of $decoder turned to ${format.height}x${format.width}: " + (sideways?.let { "refused ($it)" } ?: "started")
                }
            }
            failure == null
        }

    /** Null when [decoder] configured and started on [format]; else why it did not, in words. */
    private fun tryStart(decoder: String, format: MediaFormat): String? {
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
            "$stage: " + describe(e)
        } finally {
            runCatching { codec?.release() }
        }
    }

    /** The exception with what the platform attached to it: the code and the diagnostic. */
    private fun describe(e: Exception): String {
        if (e is MediaCodec.CodecException) {
            val code = runCatching { e.errorCode }.getOrDefault(0)
            return HardwareFirst.reasonWords("CodecException", e.diagnosticInfo ?: e.message, code)
        }
        return HardwareFirst.reasonWords(e.javaClass.simpleName, e.message, null).let {
            if (it == e.javaClass.simpleName) it else "${e.javaClass.simpleName}: $it"
        }
    }
}
