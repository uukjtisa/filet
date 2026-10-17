package dev.niccc2007.filet.playback

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How far along the reading of a track is, so the screen can say so instead of looking broken.
 *
 * ## Why a state and not a boolean
 *
 * Both of the things that need the samples take a moment to get them, and both of them were
 * silent about it. The wave simply was not there yet; the record made no sound when a finger
 * turned it. Neither is a failure and both look exactly like one, so each got reported as
 * broken rather than as slow - and a progress state is the whole fix, because "reading the
 * beat" is an answer and an inert control is not.
 *
 * [PARTIAL] is its own state because a map arrives in pieces (see [BeatMap.from]): the opening
 * of the track is measured within a frame or two and the rest follows. The wave is already
 * running at that point, so saying "reading" would be a lie, but a beat near the end of the
 * track has not been found yet either.
 */
enum class TrackReady {
    /** Nothing is wanted, so nothing is being read. */
    IDLE,

    /** The file is being decoded, or measured, and there is nothing usable yet. */
    READING,

    /** Usable and still improving. */
    PARTIAL,

    /** Measured to the end. */
    READY,
}

/**
 * Everything one track's samples are needed for, read ONCE.
 *
 * @param map the onsets and the loudness, or null when there is nothing to show yet.
 * @param ready how far along the measuring is.
 * @param record whether there are enough samples in memory for the disc to make a sound.
 */
class TrackBeat(
    val map: BeatMap?,
    val ready: TrackReady,
    val record: Boolean,
)

/**
 * Read the track once, for the beat and for the record both.
 *
 * ## One decode, two consumers - and the bug that made this a function
 *
 * The map was loaded inside the Aurora face and the record was preloaded a few lines below it,
 * each with its own `LaunchedEffect` over the same URI. [TrackPcm] does deduplicate concurrent
 * decodes, so this was no longer two codecs on one file - but it was still two places that had
 * to agree about when to read, what to key on, and what to do with a partial answer, and only
 * one of them ever reported progress.
 *
 * It also meant the beat existed only on the face that happened to load it. The glow on the
 * other four faces was wired for a pulse it was never given, which is why turning the pulse on
 * did nothing anywhere except Aurora. Hoisting the read to the screen is what lets every face
 * answer the music.
 *
 * @param pulse whether a beat map is wanted at all.
 * @param record whether the samples are wanted for the disc as well. The map is the expensive
 *   half; this only asks for the decode, which the map needs anyway.
 */
@Composable
fun rememberTrackBeat(source: Uri?, pulse: Boolean, record: Boolean): TrackBeat {
    val context = LocalContext.current
    var map by remember(source) { mutableStateOf<BeatMap?>(null) }
    var ready by remember(source) { mutableStateOf(TrackReady.IDLE) }
    var samples by remember(source) { mutableStateOf(false) }

    LaunchedEffect(source, pulse, record) {
        if (source == null || (!pulse && !record)) {
            ready = TrackReady.IDLE
            samples = false
            return@LaunchedEffect
        }
        ready = if (map == null) TrackReady.READING else ready
        // ONE read of the file, shared by the wave and the record. The map is arithmetic over
        // the samples rather than a second pass at the file, which is what stopped two
        // MediaCodec instances competing over one track - the fault behind the pulse coming
        // back slowly on a reopen and sometimes not at all.
        //
        // Partial maps are taken as they come, so the wave is running within a frame or two of
        // the samples arriving rather than after the whole track is measured. Nothing is set
        // back to null on the way: that threw away a good map for the length of the next read.
        val pcm = TrackPcm.of(context, source) { early ->
            if (pulse && map == null) {
                map = BeatMap.from(early)
                ready = TrackReady.PARTIAL
            }
        } ?: run {
            ready = TrackReady.IDLE
            return@LaunchedEffect
        }
        // The decode is done, so the disc can make a sound now whether or not the measuring
        // has finished - they are independent and used to be reported as one.
        samples = true
        if (!pulse) {
            ready = TrackReady.IDLE
            return@LaunchedEffect
        }
        if (map == null) ready = TrackReady.READING
        val stamp = withContext(Dispatchers.IO) { AudioCache.keyFor(context, source) }
        val key = source.toString()
        map = withContext(Dispatchers.Default) {
            BeatMap.of(context, stamp, key, pcm) { partial ->
                map = partial
                ready = TrackReady.PARTIAL
            }
        }
        ready = TrackReady.READY
    }

    return TrackBeat(map, ready, samples)
}
