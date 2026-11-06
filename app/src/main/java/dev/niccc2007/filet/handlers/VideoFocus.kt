package dev.niccc2007.filet.handlers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The video player's share of the sound: who else may play over it, and when it stops.
 *
 * ## Why this exists
 *
 * The video player had no audio-focus handling of its own. `VideoView` asks for focus every time
 * it starts, and asks with no listener - the platform source carries a TODO saying it should
 * have one. So a video took the sound from every other app, and nothing it lost ever paused it:
 * a call rang over the film, another app's audio played on top of it, and pulling the
 * headphones out carried on through the speaker. The uninterruptible setting could not change
 * any of that, because only the music player read it.
 *
 * Now `VideoView` is told to ask for nothing (`setAudioFocusRequest(AUDIOFOCUS_NONE)`), and the
 * screen asks through [VideoFocus], whose listener answers through [FocusSteps].
 *
 * ## The rules, the same as the music player's
 *
 * - Uninterruptible on: no request, so nothing else is stopped, and every loss is ignored.
 * - A loss for good (another player started): pause, and do not come back.
 * - A passing loss (a call, a voice assistant): pause if playing, and play again when the
 *   focus returns - unless something started or stopped it in between, which is a decision
 *   about this video that the return must not overrule.
 * - A loss that allows ducking: nothing here. From Android 8 the system lowers the sound
 *   itself, unless an app asks to be paused instead.
 * - While the sound is being handed to the music player, nothing: the music player's own
 *   request is the cause of that loss, and pausing here would cut the hand-off short.
 *
 * Headphones out pauses whatever the setting says, the same policy as `PlaybackService`: focus
 * is about which app owns the sound, and this is about where it comes out. See [noisyWatch].
 */
internal enum class FocusStep { NONE, PAUSE, RESUME }

/**
 * The decision, with no Android in it.
 *
 * One per screen. It remembers one thing: that the last pause was a passing loss's, so the
 * focus coming back should undo it.
 */
internal class FocusSteps {

    var pausedForFocus = false
        private set

    /**
     * What to do about [change], one of `AudioManager.AUDIOFOCUS_*`.
     *
     * @param ignoring uninterruptible is on, or the sound is being handed to the music player.
     * @param playing whether the video is playing now.
     */
    fun on(change: Int, ignoring: Boolean, playing: Boolean): FocusStep {
        if (ignoring) return FocusStep.NONE
        return when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocus = false
                if (playing) FocusStep.PAUSE else FocusStep.NONE
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!playing) return FocusStep.NONE
                pausedForFocus = true
                FocusStep.PAUSE
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val back = pausedForFocus && !playing
                pausedForFocus = false
                if (back) FocusStep.RESUME else FocusStep.NONE
            }
            else -> FocusStep.NONE
        }
    }

    /**
     * Drop a resume still pending from a passing loss.
     *
     * On every start, whatever started it: play pressed during the call has already decided,
     * and a pause after that is the last word. And on headphones out, so the end of a call does
     * not start the video again through the speaker.
     */
    fun forget() {
        pausedForFocus = false
    }
}

/**
 * The Android half: one focus request, made and given back by the screen.
 *
 * The listener reads [ignoring] and [playing] when a change arrives rather than when the request
 * was made, because either can change while the focus is held. Changes come on the main thread,
 * where the screen's state lives.
 */
internal class VideoFocus(
    context: Context,
    private val ignoring: () -> Boolean,
    private val playing: () -> Boolean,
    private val act: (FocusStep) -> Unit,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val steps = FocusSteps()

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(
            { change ->
                val step = steps.on(change, ignoring(), playing())
                if (step != FocusStep.NONE) act(step)
            },
            Handler(Looper.getMainLooper()),
        )
        .build()

    /** Ask for the sound, which stops other players. Asked again on every start. */
    fun request() {
        runCatching { audio?.requestAudioFocus(request) }
    }

    /** Give it back: the screen is going, or uninterruptible was turned on. */
    fun abandon() {
        runCatching { audio?.abandonAudioFocusRequest(request) }
        steps.forget()
    }
}

/**
 * Call [onNoisy] when the sound is about to come out of the phone's own speaker - headphones
 * out, Bluetooth gone - until the handle is closed.
 *
 * Registered in code because implicit broadcasts have not reached manifest receivers since
 * Android 8. A protected system broadcast, so nothing outside the platform can send it; from 33
 * the not-exported flag is required anyway.
 */
internal fun noisyWatch(context: Context, onNoisy: () -> Unit): AutoCloseable {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    val registered = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }.isSuccess
    return AutoCloseable { if (registered) runCatching { context.unregisterReceiver(receiver) } }
}
