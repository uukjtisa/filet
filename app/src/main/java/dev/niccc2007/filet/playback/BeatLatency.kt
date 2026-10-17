package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * How far the sound is behind the number the player reports, in milliseconds.
 *
 * ## Why this is arithmetic and no longer a slider
 *
 * There used to be a Timing control: a number somebody moved by ear until the pulse sat on
 * the beat. It was the wrong shape of answer to a question that has a right one, and it was
 * honest about nothing - a light that needs calibrating by hand on every phone is a light
 * that is wrong on every phone it has not been calibrated on, which is all of them.
 *
 * The lateness is three terms, and only one of them is genuinely unknowable:
 *
 * | Term | Size | Where it comes from |
 * |---|---|---|
 * | The analysis window | ~36ms, computed | see [BeatMap.biasMs] - a property of the maths, not the device |
 * | The output buffer | 4-20ms, readable | [AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER] |
 * | A Bluetooth hop | 120-250ms, NOT readable | the codec's own pipeline, see [A2DP_MS] |
 *
 * The first two are derived here and need nobody's opinion. The third is the one that made a
 * slider look necessary, and it is better answered by asking the system what the sound is
 * coming OUT of than by asking a person to watch a light and guess.
 *
 * ## What it still cannot do
 *
 * A2DP latency is a property of the receiving earbud and is not reported to the phone at any
 * API level. [A2DP_MS] is a middle value for the SBC and AAC pipelines everything supports;
 * a low-latency gaming earbud will be early by about eighty milliseconds against it and an
 * old speaker late by about the same. There is no call that improves on this, and a wrong
 * constant that is right for most people beats a control that is wrong until somebody tunes
 * it.
 */
object BeatLatency {

    /**
     * A Bluetooth audio pipeline, in milliseconds.
     *
     * Measured ranges published for the common codecs: SBC lands around 150-250ms, AAC on
     * Android around 150-200, aptX around 100-150, LDAC around 200. The codec in use is not
     * reportable either, so this is one number for all of them, chosen at the low end of the
     * common range - the error from being slightly early reads as anticipation, and the error
     * from being late reads as broken.
     */
    const val A2DP_MS = 160L

    /** When the buffer cannot be read, which is a device being unusual rather than broken. */
    private const val BUFFER_FALLBACK_MS = 10L

    /**
     * The whole output-side delay for however the phone is playing sound right now.
     *
     * Read on every track change rather than once, because somebody puts their earbuds in
     * halfway through a song and the answer changes under them.
     */
    fun outputMs(context: Context): Long {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return BUFFER_FALLBACK_MS
        return bufferMs(audio) + if (wireless(audio)) A2DP_MS else 0L
    }

    /**
     * The fast mixer's own buffer, doubled.
     *
     * Doubled because a buffer is drained while the next one is filled, so the sound leaving
     * the mixer is between one and two buffers behind what was written into it; one buffer is
     * the optimistic reading and two is the one that does not run early.
     */
    private fun bufferMs(audio: AudioManager): Long {
        val frames = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull() ?: return BUFFER_FALLBACK_MS
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: return BUFFER_FALLBACK_MS
        if (frames <= 0 || rate <= 0) return BUFFER_FALLBACK_MS
        return (2L * frames * 1000L / rate).coerceIn(2L, 60L)
    }

    /**
     * Whether the sound is going out over Bluetooth.
     *
     * `getDevices` rather than the deprecated `isBluetoothA2dpOn`, which answers whether
     * routing is *allowed* rather than whether anything is connected - it returns true with
     * no earbud in the room on several manufacturers' builds.
     */
    private fun wireless(audio: AudioManager): Boolean = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
    }.getOrDefault(false)
}
