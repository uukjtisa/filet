package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * How far the sound is behind the number the player reports: by nothing the light has to take
 * off. The position is already where the speaker is.
 *
 * ## Why this subtracts nothing any more
 *
 * Bug identified here: over Bluetooth the light ran about 200 ms late on every track. It took
 * the output pipeline off the player's position - the mixer buffer doubled plus a Bluetooth
 * constant, 40 + 160 ms with a headset in - on the reading that `MediaPlayer.getCurrentPosition`
 * counts the frames that have left the app. It does not. The platform player anchors its clock
 * on the output's own timestamps: the media time it reports is what it has written less what
 * is still waiting to be played out, which is the frame being PRESENTED. The pipeline was in
 * the position already, and taking it off again drove the light from 200 ms of music ago.
 *
 * Measured on the phone rather than read off a source tree (`LatencyProbe`, A2DP, 2026-10-31):
 * after Play the position stood still for 280 to 369 ms over five starts, and a silent track on
 * the same route measured 283 ms between the mixer and the output. A position that waits for
 * the first sound to come out is a position at the speaker.
 *
 * Why it looked like one track's fault and not all of them: a light late by a fixed amount
 * still lands on a hit when the music has one every quarter of a second. Of an eighth-note
 * track at 118 bpm, 84 % of the late flashes landed on some hit anyway; of a sparse one at
 * 67 bpm, 11 % did, and that was the one that looked late.
 *
 * ## What is left
 *
 * - The analysis window's own offset, which is arithmetic: [BeatMap.biasMs].
 * - One record's own analysis error, which nothing can measure: its [BeatNudge], 0 unless it
 *   was set for that track.
 * - Whatever the platform does not know. An earbud that reports less delay than it has puts
 *   the position early by the difference, and no API says by how much. That is still not a
 *   timing control (D-02): a calibration by eye is wrong on every phone nobody calibrated.
 */
object BeatLatency {

    /**
     * Where in the track the light reads the map, for a position [reportedMs] the player gave
     * [ageMs] ago, read against a map whose buckets sit [biasMs] behind their content, with the
     * track's own [nudgeMs] (positive is later, so it reads an earlier part of the map).
     *
     * No output term, on purpose: see the class comment for the bug that was.
     */
    fun earMs(reportedMs: Long, ageMs: Long, biasMs: Long, nudgeMs: Long = 0L): Long =
        reportedMs + ageMs - biasMs - nudgeMs

    /** When the buffer cannot be read, which is a device being unusual rather than broken. */
    private const val BUFFER_FALLBACK_MS = 10L

    /**
     * The route the sound is going out of, for the log line a lateness report is read from.
     *
     * Logged and not subtracted: it is what a report is read AGAINST, since a delay the platform
     * does not know about shows up on one route and not another.
     */
    fun describe(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "no audio service"
        val routes = runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { it.type.toString() }
        }.getOrDefault("?")
        return "buffer=${bufferMs(audio)}ms wireless=${wireless(audio)} outputs=[$routes]"
    }

    /**
     * The fast mixer's own buffer, doubled, as logged. Doubled because a buffer is drained
     * while the next one is filled, so the sound leaving the mixer is one to two buffers behind.
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
