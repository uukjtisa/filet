package dev.niccc2007.filet.playback

/** What repeat is doing. Off, the whole queue, or this one track. */
enum class Repeat { OFF, ALL, ONE }

/** Whether a track is being played for its pictures as well as its sound. */
enum class Medium { AUDIO, VIDEO }

/**
 * Everything the player is doing, as one value.
 *
 * ## Why this is a state object and not fields on a screen
 *
 * It used to be eleven `remember(node.path)` variables inside `AudioScreen`: the queue, the
 * tags, the duration, the position, whether it was playing, shuffle, repeat. Compose state
 * belongs to the composition that declared it, so leaving the screen destroyed all of it and
 * released the player with it. That is the whole of the reported fault - the playlist exists,
 * and the moment the screen is closed there is nothing left to play it.
 *
 * Moving it here is not a tidying exercise. Three separate things need the same answer at the
 * same time and only one of them is a screen: the full player, the strip that sits above the
 * bottom bar everywhere in the app, and the notification a phone with its screen off is driven
 * from. One of those being the owner means the other two read a copy, and copies of playback
 * state disagree within seconds.
 *
 * @param position where the track is now. Written by the ticker and NOT by the scrub handle -
 *   a scrub in progress is the screen's own state, because committing every intermediate value
 *   would make the player chase the finger.
 */
data class NowPlaying(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val repeat: Repeat = Repeat.OFF,
    val shuffle: Boolean = false,
    /** A sentence to show, or null. A refusal is an answer, so it lives in the state. */
    val error: String? = null,
    /**
     * True once something has been opened, whether or not it is playing.
     *
     * Distinct from `playing`, and the strip reads this one: a paused track is still the thing
     * you are listening to, and a strip that vanishes on pause takes the Play button with it.
     */
    val active: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)

    /** Human position, one-based, for "3 of 12". */
    val position: Int get() = index + 1

    val size: Int get() = queue.size

    /** Whether stepping is worth offering at all. A queue of one has nowhere to go. */
    val canStep: Boolean get() = size > 1
}

/**
 * One thing in the queue.
 *
 * Carries the display fields rather than a `VNode`, because the service and the notification
 * need a title and an artist and have no business holding a filesystem node - and because a
 * track whose tags have been read should not have to be re-read to be drawn again.
 */
data class Track(
    /** `VPath.toString()`, so a track is identifiable without depending on the VFS types here. */
    val id: String,
    val name: String,
    val medium: Medium = Medium.AUDIO,
    val title: String = name.substringBeforeLast('.', name),
    val artist: String? = null,
    val album: String? = null,
)

/**
 * What the strip and the notification say, derived once rather than twice.
 *
 * Pure, and tested, because it is a decision that can be wrong in a way nobody would notice
 * for a while: the two surfaces are looked at in different places - one on screen, one on a
 * lock screen - so two implementations drifting apart produces a notification that says
 * something the app does not, and neither looks wrong on its own.
 */
object PlaybackNotice {

    /** The top line. The track's title, or a holding line when there is nothing. */
    fun title(state: NowPlaying): String = state.current?.title ?: "Nothing playing"

    /**
     * The second line: the artist, or the queue position when there is no artist.
     *
     * Falling back to the position rather than to the filename or to nothing. A tagless folder
     * of recordings is the common case on a phone, and "4 of 37" is the fact that is actually
     * useful there - the filename is already the title line.
     */
    fun subtitle(state: NowPlaying): String {
        val t = state.current ?: return ""
        t.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            return if (state.canStep) "$artist  ·  ${state.position} of ${state.size}" else artist
        }
        return if (state.canStep) "${state.position} of ${state.size}" else ""
    }

    /** What the play/pause control does next, as a label for a content description. */
    fun toggleLabel(state: NowPlaying): String = if (state.playing) "Pause" else "Play"

    /**
     * Whether the foreground service has any business still running.
     *
     * The rule is `active`, not `playing`. A service that stops the moment playback pauses
     * takes its own notification down, which is the one place the Play button exists once the
     * app is in the background - so pausing from the lock screen would leave no way to resume
     * and look exactly like a crash. It stops when the queue is finished or cleared.
     */
    fun shouldKeepService(state: NowPlaying): Boolean = state.active && state.current != null
}

/**
 * What a media button means.
 *
 * Earphone and Bluetooth buttons arrive as key events, and the mapping is a decision that can
 * be wrong, so it is a pure function with a test rather than a `when` inside a receiver.
 *
 * The one that is genuinely easy to get wrong is `KEYCODE_HEADSETHOOK`: a cheap wired remote has
 * ONE button and sends that code for everything, with the count of taps carrying the meaning.
 * Treating it as play/pause regardless - which is what a naive mapping does - means a single
 * button that can never skip a track, on precisely the hardware where skipping matters most
 * because there is no screen in your hand.
 */
object MediaButtons {

    enum class Action { TOGGLE, NEXT, PREVIOUS, PLAY, PAUSE, STOP, NONE }

    /**
     * @param taps how many times the hook was pressed in quick succession. Ignored for every
     *   other key, because a dedicated Next button means Next however often it is pressed.
     */
    fun actionOf(keyCode: Int, taps: Int = 1): Action = when (keyCode) {
        KEYCODE_MEDIA_PLAY -> Action.PLAY
        KEYCODE_MEDIA_PAUSE -> Action.PAUSE
        KEYCODE_MEDIA_PLAY_PAUSE -> Action.TOGGLE
        KEYCODE_MEDIA_NEXT -> Action.NEXT
        KEYCODE_MEDIA_PREVIOUS -> Action.PREVIOUS
        KEYCODE_MEDIA_STOP -> Action.STOP
        KEYCODE_HEADSETHOOK -> when {
            taps >= 3 -> Action.PREVIOUS
            taps == 2 -> Action.NEXT
            else -> Action.TOGGLE
        }
        else -> Action.NONE
    }

    /**
     * How long a tap waits for another one before it is acted on.
     *
     * Long enough that a deliberate double tap is not split, short enough that a single tap
     * does not feel laggy. A pause that arrives 500 ms late is noticeable and forgivable; a
     * double tap read as two single taps pauses and resumes instead of skipping, which is not.
     */
    const val TAP_WINDOW_MS = 450L

    // The framework constants, written out so this file is pure and testable on a plain JVM.
    // `android.view.KeyEvent` is not on a unit-test classpath, and a mapping that can only be
    // checked on a device is a mapping that does not get checked.
    const val KEYCODE_HEADSETHOOK = 79
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_PLAY = 126
    const val KEYCODE_MEDIA_PAUSE = 127
}
