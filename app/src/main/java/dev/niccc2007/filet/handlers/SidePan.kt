package dev.niccc2007.filet.handlers

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 8D in Filet's player: a level for each side, applied to the sound on its way out.
 *
 * The phone's player takes a volume per side, and 8D's sweep moves the two. Media3's player takes
 * one volume for both, so in Filet's player 8D had nothing to move and the sound stayed in the
 * middle. This sits in Media3's audio chain and scales each side by [set]'s levels.
 *
 * - Mono is made two-sided on the way through, so a mono video pans too - what the phone's mixer
 *   does for its own player.
 * - A change of level is ramped across the buffer it arrives in, so a sweep moving every few
 *   milliseconds never steps or clicks.
 * - Anything else - more than two channels, or a sample format other than 16-bit or float - goes
 *   through untouched.
 */
@OptIn(UnstableApi::class)
internal class SidePan : BaseAudioProcessor() {

    @Volatile private var left = 1f
    @Volatile private var right = 1f
    private var wasLeft = 1f
    private var wasRight = 1f

    /** Each side's level, 0 to 1. From any thread; the next buffer takes it. */
    fun set(left: Float, right: Float) {
        this.left = left.coerceIn(0f, 1f)
        this.right = right.coerceIn(0f, 1f)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!pcm || inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        val frames = inputBuffer.remaining() / (bytes * channels)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * bytes * 2)
        val l1 = left
        val r1 = right
        val l0 = wasLeft
        val r0 = wasRight
        for (i in 0 until frames) {
            val t = if (frames > 1) i.toFloat() / (frames - 1) else 1f
            val gl = l0 + (l1 - l0) * t
            val gr = r0 + (r1 - r0) * t
            if (float) {
                val a = inputBuffer.getFloat()
                val b = if (channels == 2) inputBuffer.getFloat() else a
                out.putFloat(a * gl)
                out.putFloat(b * gr)
            } else {
                val a = inputBuffer.getShort()
                val b = if (channels == 2) inputBuffer.getShort() else a
                out.putShort(scale(a, gl))
                out.putShort(scale(b, gr))
            }
        }
        // A part frame cannot be panned; PCM buffers never carry one, and it is not left to loop on.
        inputBuffer.position(inputBuffer.limit())
        wasLeft = l1
        wasRight = r1
        out.flip()
    }

    override fun onReset() {
        left = 1f
        right = 1f
        wasLeft = 1f
        wasRight = 1f
    }

    companion object {
        fun scale(s: Short, gain: Float): Short =
            (s * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        /** Media3's audio output as its factory builds it, with [pan] in the chain. */
        fun sink(context: Context, floatOutput: Boolean, playbackParameters: Boolean, pan: SidePan): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(floatOutput)
                .setEnableAudioOutputPlaybackParameters(playbackParameters)
                .setAudioProcessors(arrayOf(pan))
                .build()
    }
}
