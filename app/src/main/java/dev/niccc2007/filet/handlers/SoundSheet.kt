package dev.niccc2007.filet.handlers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.playback.MAX_SPEED
import dev.niccc2007.filet.playback.MIN_SPEED
import dev.niccc2007.filet.playback.PitchMode
import dev.niccc2007.filet.playback.Sound
import dev.niccc2007.filet.playback.SoundMaths
import dev.niccc2007.filet.playback.SoundPart
import dev.niccc2007.filet.playback.SoundSettings
import dev.niccc2007.filet.playback.SoundState
import dev.niccc2007.filet.playback.SoundTarget
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.theme.Filet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frame by frame's switch, which only the video player has. The steps themselves are on the
 * bar it puts under the picture: see `FrameBar`.
 */
class FrameBarSwitch(val on: Boolean, val onChange: (Boolean) -> Unit)

/**
 * The sound sheet: one dialogue for everything about how a file sounds, on both players.
 *
 * Speed and pitch, the app's own volume, and the equaliser. The video player adds the switch
 * for frame by frame, because "slower" and "one frame at a time" are the same question asked
 * harder.
 *
 * ## Each player's own sound
 *
 * The volume and the equaliser are read here rather than passed in, from [SoundState] for the
 * [target] player: the music's and the video's are apart, so a curve put on a song is not on
 * the next video. A file with "Remember for this track" on has a sheet of its own, and the
 * edits go to it. The SPEED comes in from the player: the music's is shared across the queue
 * and a video's is its own.
 *
 * ## What each player shows of the equaliser
 *
 * Music gets all of it - the presets, the five bands, the bass. The video player gets the
 * switch, the curve's name and its reset, and not the bands: a video is watched, and the
 * curve is something set once while listening, not re-tuned for every clip.
 *
 * ## Why the speed rail is logarithmic
 *
 * The range runs from [MIN_SPEED] to whatever the device will actually take, and on a linear
 * rail that puts 1.0x a quarter of the way along with everything useful crammed into the left
 * of it: half the travel would be between 2x and 4x, where nobody needs resolution, and the
 * 0.9-1.1 anybody actually cares about would be four pixels wide. A log rail gives every
 * DOUBLING the same distance, which is how speed is heard.
 *
 * ## The speed's hard limit, stated rather than hidden
 *
 * Asked for 0.01x to 100x. Those are not refusals Filet is making: Android resamples through
 * `AudioTrack`, and a rate far outside a quarter to four times throws rather than playing -
 * at 100x a 48 kHz track is 4.8 MHz of samples a second into a sink that runs at 48. The
 * player starts optimistic and walks its ceiling down on the first refusal, so the rail ends
 * where this phone actually ends.
 *
 * @param equaliser true on the music player, for the bands and presets. See above.
 * @param frames frame by frame's switch, or null where there are no frames.
 */
@Composable
fun SoundSheet(
    speed: Float,
    maxSpeed: Float,
    pitch: PitchMode,
    tint: Color,
    onTint: Color,
    onSpeed: (Float) -> Unit,
    onPitch: (PitchMode) -> Unit,
    onDismiss: () -> Unit,
    /** Whose sound this sheet sets. Each player's own: see [SoundTarget]. */
    target: SoundTarget,
    equaliser: Boolean,
    frames: FrameBarSwitch? = null,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    val state = remember { SoundState.of(context) }
    val sound by state.sound(target).collectAsStateWithLifecycle()
    val remembered by state.rememberedNow(target).collectAsStateWithLifecycle()
    // The pitch goes with the file while it is remembered, and to the general setting otherwise.
    val pitchTo: (PitchMode) -> Unit = { mode -> state.setPitch(target, mode, onPitch) }
    val engine = remember { Sound.of(context) }
    val refused by engine.refused.collectAsStateWithLifecycle()
    Dlg(onDismiss = onDismiss) {
        Box(
            Modifier
                .padding(top = 9.dp, bottom = 2.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.high),
        )
        // Scrolls: with the equaliser open this is taller than a landscape phone, and a sheet
        // whose bottom rows cannot be reached is a sheet with a setting missing.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            if (state.fileOf(target) != null) {
                RememberRow(target.rememberLabel, remembered != null, tint, onTint) { on ->
                    state.remember(target, on, speed, pitch)
                }
                Gap()
            }
            Heading(
                "PLAYBACK SPEED",
                speedLabel(speed),
                tint,
                reset = if (abs(speed - 1f) > 0.001f) ({ onSpeed(1f) }) else null,
            )
            Spacer(Modifier.height(4.dp))
            SpeedRail(speed, maxSpeed, tint, onTint, onSpeed)
            Spacer(Modifier.height(14.dp))
            PitchModeRow(pitch, tint, onTint, pitchTo)

            if (frames != null) {
                Gap()
                FrameRow(frames, tint, onTint)
            }

            Gap()
            VolumeSection(sound.volume, SoundPart.BOOST in refused, tint, onTint) { v -> state.editSound(target) { it.copy(volume = v) } }

            Gap()
            EffectsSection(
                sound = sound,
                full = equaliser,
                offers = engine.offers,
                refused = refused,
                tint = tint,
                onTint = onTint,
                video = target == SoundTarget.VIDEO,
                edit = { change -> state.editSound(target, change) },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * "Remember for this track": this file's whole sheet kept for it, or the player's own settings.
 *
 * Off by default and per file. On keeps what is heard now, so nothing jumps; off lets the file
 * go back to the player's settings. One line under the label says which of the two is playing.
 */
@Composable
private fun RememberRow(label: String, on: Boolean, tint: Color, onTint: Color, onChange: (Boolean) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!on) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (on) "Plays with these settings every time" else "Uses the player's settings",
                fontSize = 10.sp,
                color = colors.fg3,
            )
        }
        SoundSwitch(on, tint, onTint) { onChange(!on) }
    }
}

/** The small capitals over a section, its value in large, and its reset when it has one. */
@Composable
private fun Heading(caption: String, value: String, tint: Color, reset: (() -> Unit)?, valueColor: Color = tint) {
    val colors = Filet.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Caption(caption)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = valueColor,
            )
        }
        if (reset != null) ResetChip(reset)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 9.sp, letterSpacing = 1.4.sp, color = Filet.colors.fg3)
}

/** A line saying what a setting costs or why it is not heard. */
@Composable
private fun Note(text: String, color: Color = Filet.colors.fg3) {
    Text(text, fontSize = 10.sp, lineHeight = 13.5.sp, color = color)
}

@Composable
private fun ResetChip(onReset: () -> Unit) {
    val colors = Filet.colors
    Text(
        "Reset",
        fontSize = 12.sp,
        color = colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.high)
            .clickable(onClick = onReset)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Between sections: a hairline with room either side, so four sections read as four. */
@Composable
private fun Gap() {
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Filet.colors.lineSoft))
    Spacer(Modifier.height(12.dp))
}

/**
 * The speed rail: a bubble over the thumb, a log track, and tappable stops under it.
 *
 * All eight stops are snapped to, and only some are named: eight labels across a 380dp rail
 * collide at the top end where the log scale bunches them, so the five worth reading get the
 * text and the rest keep their tick. Naming them also makes 1x one press, which is the single
 * most-wanted value on the whole control.
 */
@Composable
private fun SpeedRail(
    speed: Float,
    maxSpeed: Float,
    tint: Color,
    onTint: Color,
    onSpeed: (Float) -> Unit,
) {
    val live = rememberUpdatedState(onSpeed)
    val top = rememberUpdatedState(maxSpeed)
    val stops = SPEED_STOPS.filter { it <= maxSpeed + 0.001f }.map { stop ->
        RailStop(
            speedFraction(stop, maxSpeed),
            if (stop in SPEED_LABELLED) speedLabel(stop) else null,
            on = abs(stop - speed) < 0.005f,
        ) { onSpeed(stop) }
    }
    StopRail(
        fraction = speedFraction(speed, maxSpeed),
        bubble = speedLabel(speed),
        stops = stops,
        tint = tint,
        onTint = onTint,
    ) { f -> live.value(snapSpeed(speedAt(f, top.value), top.value)) }
}

/** The three answers to what the pitch does, as one segmented row. */
@Composable
private fun PitchModeRow(
    pitch: PitchMode,
    tint: Color,
    onTint: Color,
    onPitch: (PitchMode) -> Unit,
) {
    Column {
        Caption("PITCH")
        Spacer(Modifier.height(6.dp))
        Segments(PitchMode.entries.map { it.label }, PitchMode.entries.indexOf(pitch), tint, onTint) {
            onPitch(PitchMode.entries[it])
        }
        Spacer(Modifier.height(6.dp))
        // Says what the chosen one costs. Neither of the other two is wrong, which is why
        // there are three of them and why the row below is an explanation and not a warning.
        Note(pitch.about)
    }
}

/** One segmented row of exclusive choices; [picked] -1 for none of them. */
@Composable
private fun Segments(labels: List<String>, picked: Int, tint: Color, onTint: Color, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.sunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == picked
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) onTint else colors.fg2,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) tint else Color.Transparent)
                    .clickable { onPick(i) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Frame by frame: on puts a bar under the picture with the steps on it, and closes this sheet
 * so the bar is seen at once. It stays until it is switched off here again.
 */
@Composable
private fun FrameRow(frames: FrameBarSwitch, tint: Color, onTint: Color) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { frames.onChange(!frames.on) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Frame by frame", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (frames.on) {
                    "The bar is under the picture"
                } else {
                    "A bar under the picture: 1, 2, 5 or 10 frames a step, a second either way"
                },
                fontSize = 10.sp,
                lineHeight = 13.5.sp,
                color = colors.fg3,
            )
        }
        SoundSwitch(frames.on, tint, onTint) { frames.onChange(!frames.on) }
    }
}

/**
 * The app's own volume, 0 to 200% of the phone's.
 *
 * Linear, unlike the speed: a volume is read as a percentage, and 150 halfway between 100 and
 * 200 is what anybody would expect to find there.
 */
@Composable
private fun VolumeSection(volume: Int, refused: Boolean, tint: Color, onTint: Color, onVolume: (Int) -> Unit) {
    val live = rememberUpdatedState(onVolume)
    val top = SoundMaths.MAX_VOLUME.toFloat()
    Heading("APP VOLUME", "$volume%", tint, reset = if (volume != 100) ({ onVolume(100) }) else null)
    Spacer(Modifier.height(4.dp))
    StopRail(
        fraction = volume / top,
        bubble = "$volume%",
        stops = SoundMaths.VOLUME_STOPS.map { RailStop(it / top, "$it", on = it == volume) { onVolume(it) } },
        tint = tint,
        onTint = onTint,
    ) { f -> live.value(SoundMaths.snapVolume(f * top)) }
    Spacer(Modifier.height(6.dp))
    Note(
        when {
            volume > 100 && refused ->
                "This phone would not give this player a boost, so it is playing at 100%."
            volume > 100 ->
                "Above 100% a limiter keeps the loud parts under full scale. Back to 100% the next time Filet starts."
            else -> "Of the phone's own volume. Above 100%, a limiter comes in with the boost."
        },
        if (volume > 100 && refused) Filet.colors.warn else Filet.colors.fg3,
    )
}

/**
 * The effects: one switch for all of them, the one-tap looks, and on the music player the bands,
 * the bass, the space and the filter themselves.
 *
 * Touching any of it while the switch is off switches it on. A curve dragged with the switch off
 * is a change nobody can hear, and the next thing anybody does after that is decide the
 * equaliser is broken.
 *
 * Only what this phone has is offered: without a reverb, Room and Hall are not there to pick,
 * and the sheet says why rather than leaving a gap.
 */
@Composable
private fun EffectsSection(
    sound: SoundSettings,
    full: Boolean,
    offers: Set<SoundPart>,
    refused: Set<SoundPart>,
    tint: Color,
    onTint: Color,
    video: Boolean,
    /** One write per touch: a change to the settings this sheet's player plays with. */
    edit: ((SoundSettings) -> SoundSettings) -> Unit,
) {
    val colors = Filet.colors
    val on = sound.eqOn
    val look = SoundMaths.Look.of(sound)
    val hasSpace = SoundPart.SPACE in offers
    // A touch with the switch off switches it on, in the same write as the touch.
    fun touch(change: (SoundSettings) -> SoundSettings) = edit { change(it).copy(eqOn = true) }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { edit { it.copy(eqOn = !on) } },
        ) {
            Caption("EFFECTS")
            Spacer(Modifier.height(2.dp))
            Text(
                if (on) effectsName(sound) else "Off",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = if (on) tint else colors.fg3,
                maxLines = 1,
            )
        }
        if (look != SoundMaths.Look.FLAT) {
            ResetChip { edit { SoundMaths.Look.FLAT.on(it).copy(eqOn = it.eqOn) } }
            Spacer(Modifier.width(8.dp))
        }
        SoundSwitch(on, tint, onTint, Modifier.padding(bottom = 6.dp)) { edit { it.copy(eqOn = !on) } }
    }
    Spacer(Modifier.height(10.dp))
    LookChips(
        SoundMaths.Look.entries.filter { hasSpace || it.space == SoundMaths.Space.NONE },
        picked = if (on) look else null,
        tint = tint,
        onTint = onTint,
    ) { picked -> edit { picked.on(it) } }
    Spacer(Modifier.height(8.dp))
    val plan = SoundMaths.plan(sound)
    if (SoundPart.EQUALISER in refused && plan.equalises) {
        Note("This phone would not give this player an equalizer, so the curve and the filter are not heard.", colors.warn)
    }
    if (SoundPart.SPACE in refused && plan.spaces) {
        Note("This phone would not give this player a reverb, so the room is not heard.", colors.warn)
    }
    if (!hasSpace) Note("This phone has no reverb, so Room and Hall are not offered.")
    when {
        !full && video -> Note("On video the looks set the bands, the space and the filter. The music player's are its own.")
        !full -> Note("The bands, the space and the filter are set in the music player's sound sheet.")
        !on -> Note("Off: the sound is the file's own. Touching any of it switches it on.")
        else -> Note("Shapes the sound and never raises its level, so it cannot make it clip.")
    }
    if (!full) return

    Spacer(Modifier.height(10.dp))
    val range = SoundMaths.BAND_RANGE
    val span = (range.last - range.first).toFloat()
    SoundMaths.BANDS_HZ.forEachIndexed { i, hz ->
        val gain = sound.bands.getOrElse(i) { 0 }
        BandRow(
            label = hzLabel(hz),
            value = dbLabel(gain),
            fraction = (gain - range.first) / span,
            from = -range.first / span,
            dim = !on,
            tint = tint,
            onTint = onTint,
        ) { f ->
            val next = (range.first + f * span).roundToInt().coerceIn(range)
            touch { s -> if (next != gain) s.copy(bands = s.bands.toMutableList().also { it[i] = next }) else s }
        }
    }
    BandRow(
        label = "Bass",
        value = "${sound.bass}%",
        fraction = sound.bass / 100f,
        from = 0f,
        dim = !on,
        tint = tint,
        onTint = onTint,
    ) { f ->
        touch { it.copy(bass = ((f * 100f) / 5f).roundToInt() * 5) }
    }
    val segTint = if (on) tint else colors.fg3
    if (hasSpace) {
        Spacer(Modifier.height(12.dp))
        Caption("SPACE")
        Spacer(Modifier.height(6.dp))
        Segments(SoundMaths.Space.entries.map { it.label }, SoundMaths.Space.entries.indexOf(sound.space), segTint, onTint) {
            touch { s -> s.copy(space = SoundMaths.Space.entries[it]) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Caption("FILTER")
    Spacer(Modifier.height(6.dp))
    Segments(SoundMaths.Filter.entries.map { it.label }, SoundMaths.Filter.entries.indexOf(sound.filter), segTint, onTint) {
        touch { s -> s.copy(filter = SoundMaths.Filter.entries[it]) }
    }
}

/**
 * The looks, one tap each, on both players. Wrapping rather than scrolling sideways: nine
 * names do not fit across a phone, and a row that hides its last three hides Muffled and Radio.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LookChips(
    looks: List<SoundMaths.Look>,
    picked: SoundMaths.Look?,
    tint: Color,
    onTint: Color,
    onPick: (SoundMaths.Look) -> Unit,
) {
    val colors = Filet.colors
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (look in looks) {
            val lit = look == picked
            Text(
                look.label,
                fontSize = 11.5.sp,
                fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                color = if (lit) onTint else colors.fg2,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (lit) tint else colors.sunken)
                    .clickable { onPick(look) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** A band of the equaliser: its frequency, a rail, and its gain. */
@Composable
private fun BandRow(
    label: String,
    value: String,
    fraction: Float,
    from: Float,
    dim: Boolean,
    tint: Color,
    onTint: Color,
    onAt: (Float) -> Unit,
) {
    val colors = Filet.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.5.sp, color = colors.fg2, maxLines = 1, modifier = Modifier.width(46.dp))
        StopRail(
            fraction = fraction,
            bubble = null,
            stops = listOf(RailStop(from, null, on = false) {}),
            tint = tint,
            onTint = onTint,
            from = from,
            dim = dim,
            modifier = Modifier.weight(1f),
            onAt = onAt,
        )
        Text(
            value,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (dim) colors.fg3 else colors.fg2,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(42.dp),
        )
    }
}

/** On or off, in the sheet's own colour. The same one on both players, so it reads the same. */
@Composable
private fun SoundSwitch(on: Boolean, tint: Color, onTint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Filet.colors
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) tint else colors.high)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .padding(start = if (on) 20.dp else 2.dp)
                .align(Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (on) onTint else colors.fg3),
        )
    }
}

/** A point under a rail: named, or a tick only when [label] is null. */
private class RailStop(val fraction: Float, val label: String?, val on: Boolean, val onPick: () -> Unit)

/**
 * A rail: an optional bubble over the thumb, the track, and its stops.
 *
 * Hand-drawn rather than Material's `Slider`, for the same reasons `ScrubBar` is: the touch
 * target has to be a thumb tall while the drawn track is a few pixels, the position has to be
 * absolute from the first touch rather than a delta applied to a thumb you had to catch
 * first, and it has to take the artwork's colour. A Material `Slider` gives none of those
 * without fighting a theme, and its own tick marks are evenly spaced, which a log rail's
 * are not.
 *
 * @param from where the fill starts: 0 for a level, the middle for a gain that cuts or boosts.
 * @param onAt a touch, as a point 0 to 1 along the travel. The caller snaps it.
 */
@Composable
private fun StopRail(
    fraction: Float,
    bubble: String?,
    stops: List<RailStop>,
    tint: Color,
    onTint: Color,
    modifier: Modifier = Modifier,
    from: Float = 0f,
    dim: Boolean = false,
    onAt: (Float) -> Unit,
) {
    val colors = Filet.colors
    val density = LocalDensity.current
    val knobPx = with(density) { KNOB.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val live = rememberUpdatedState(onAt)
    val fill = if (dim) colors.fg3 else tint
    val f = fraction.coerceIn(0f, 1f)

    fun at(x: Float) {
        if (width <= 0f) return
        val span = (width - knobPx * 2f).coerceAtLeast(1f)
        live.value(((x - knobPx) / span).coerceIn(0f, 1f))
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val full = maxWidth
        // The travel in dp, so the bubble and the labels can be placed with `offset` instead
        // of a second canvas. Same inset the canvas uses, or the two disagree by a knob.
        val span = full - KNOB * 2
        Column {
            if (bubble != null) {
                // The value above the thumb as well as in the heading: while dragging, the
                // thumb is where the eye is and the heading is 60dp away under the hand.
                Box(Modifier.fillMaxWidth().height(24.dp)) {
                    Box(
                        Modifier
                            .offset(
                                x = (KNOB + span * f - BUBBLE / 2)
                                    .coerceIn(0.dp, (full - BUBBLE).coerceAtLeast(0.dp)),
                            )
                            .width(BUBBLE)
                            .clip(RoundedCornerShape(7.dp))
                            .background(fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            bubble,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = onTint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .onSizeChanged { width = it.width.toFloat() }
                    .pointerInput(Unit) { detectTapGestures { at(it.x) } }
                    // After the tap detector so it wins the main pass: a drag that starts as
                    // a press has to become a drag, not a tap that fires on lift somewhere
                    // else entirely. Same ordering, same reason, as ScrubBar.
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            at(change.position.x)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                    val mid = size.height / 2f
                    val track = 4.dp.toPx()
                    // Inset by the knob's radius at both ends, or the thumb is half off the
                    // canvas at each limit - which are the two values a rail is dragged to
                    // most, and a control that loses its handle there reads as clipped.
                    val x0 = knobPx
                    val run = (size.width - knobPx * 2f).coerceAtLeast(1f)
                    drawRoundRect(
                        colors.high,
                        topLeft = Offset(x0, mid - track / 2f),
                        size = Size(run, track),
                        cornerRadius = CornerRadius(track, track),
                    )
                    val lo = min(from, f)
                    val hi = max(from, f)
                    drawRoundRect(
                        fill,
                        topLeft = Offset(x0 + run * lo, mid - track / 2f),
                        size = Size(run * (hi - lo), track),
                        cornerRadius = CornerRadius(track, track),
                    )
                    for (stop in stops) {
                        val lit = stop.fraction in lo..hi
                        drawCircle(
                            if (lit) onTint.copy(alpha = 0.4f) else colors.fg3.copy(alpha = 0.65f),
                            radius = 1.8.dp.toPx(),
                            center = Offset(x0 + run * stop.fraction, mid),
                        )
                    }
                    drawCircle(fill, radius = knobPx, center = Offset(x0 + run * f, mid))
                }
            }

            val named = stops.filter { it.label != null }
            if (named.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(18.dp)) {
                    for (stop in named) {
                        Text(
                            stop.label!!,
                            fontSize = 9.5.sp,
                            fontWeight = if (stop.on) FontWeight.Bold else FontWeight.Normal,
                            color = if (stop.on) fill else colors.fg3,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .offset(
                                    x = (KNOB + span * stop.fraction - LABEL / 2)
                                        // Kept inside the rail: the first and last labels are
                                        // centred on points that are themselves at the edge.
                                        .coerceIn(0.dp, (full - LABEL).coerceAtLeast(0.dp)),
                                )
                                .width(LABEL)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable(onClick = stop.onPick),
                        )
                    }
                }
            }
        }
    }
}

private val KNOB = 9.dp
private val BUBBLE = 46.dp
private val LABEL = 34.dp

/** What the effects are set to, in a word: the look they match, or "Custom". */
fun effectsName(s: SoundSettings): String = SoundMaths.Look.of(s)?.label ?: "Custom"

/** "60 Hz", "910 Hz", "3.6 kHz", "14 kHz". */
fun hzLabel(hz: Int): String =
    if (hz < 1000) "$hz Hz"
    else String.format(java.util.Locale.US, "%.1f", hz / 1000.0).removeSuffix(".0") + " kHz"

/** "+3 dB", "0 dB", "-6 dB". */
fun dbLabel(db: Int): String = (if (db > 0) "+$db" else "$db") + " dB"

/**
 * What the sound chip says: whatever is not at its default, or the speed when nothing is.
 *
 * A lit chip reading "1×" would be lit for a reason it does not give.
 */
fun soundChipLabel(speed: Float, sound: SoundSettings): String {
    val parts = listOfNotNull(
        if (abs(speed - 1f) > 0.001f) speedLabel(speed) else null,
        if (sound.volume != 100) "${sound.volume}%" else null,
        if (effectsHeard(sound)) effectsName(sound) else null,
    )
    return if (parts.isEmpty()) speedLabel(speed) else parts.joinToString(" · ")
}

/** Whether the sound chip is lit: anything in the sheet away from its default. */
fun soundChipLit(speed: Float, sound: SoundSettings): Boolean =
    abs(speed - 1f) > 0.001f || sound.volume != 100 || effectsHeard(sound)

/** The effects are on AND set to something: the switch on over a flat curve changes nothing. */
private fun effectsHeard(sound: SoundSettings): Boolean =
    sound.eqOn && SoundMaths.Look.of(sound) != SoundMaths.Look.FLAT

/**
 * Where a speed sits on the rail, 0 to 1, on a log scale.
 *
 * Every doubling gets the same distance, which is how speed is heard and is the only way a
 * 0.1-to-4 range leaves usable resolution around 1.0: linearly, 1.0 would sit at a quarter of
 * the travel with half the rail spent between 2x and 4x.
 */
fun speedFraction(speed: Float, maxSpeed: Float): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val v = speed.coerceIn(MIN_SPEED, top)
    return (ln(v / MIN_SPEED) / ln(top / MIN_SPEED)).toFloat().coerceIn(0f, 1f)
}

/** The inverse: what speed a point on the rail means. */
fun speedAt(fraction: Float, maxSpeed: Float): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val f = fraction.coerceIn(0f, 1f)
    return (MIN_SPEED * exp(f * ln(top / MIN_SPEED))).toFloat().coerceIn(MIN_SPEED, top)
}

/**
 * Round to something sayable, and to a stop when close enough.
 *
 * 1.03x is nobody's intention and is audible, so the window around a stop is wide enough to
 * land on with a thumb. Away from a stop the step is proportional rather than fixed: a
 * twentieth is the right grain at 1x and far too coarse at 0.2x, where it is a fifth of the
 * whole value.
 */
fun snapSpeed(raw: Float, maxSpeed: Float = MAX_SPEED): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val clamped = raw.coerceIn(MIN_SPEED, top)
    val stop = SPEED_STOPS.filter { it <= top + 0.001f }.minByOrNull { abs(it - clamped) }
    if (stop != null && abs(speedFraction(stop, top) - speedFraction(clamped, top)) <= SNAP_F) {
        return stop
    }
    val step = if (clamped < 1f) 0.01f else 0.05f
    return ((clamped / step).roundToInt() * step).coerceIn(MIN_SPEED, top)
}

/** `0.5×`, `0.75×`, `1×` - the shortest true spelling of a speed. */
fun speedLabel(speed: Float): String {
    val text = String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return text + "×"
}

/** The stops the rail snaps to. Doublings, plus the ones anybody actually asks for. */
val SPEED_STOPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** The ones that get a label under the rail. The rest keep their tick and their snap. */
val SPEED_LABELLED = setOf(0.25f, 0.5f, 1f, 2f, 4f)

/** How close to a stop counts as on it, measured in rail travel rather than in speed. */
const val SNAP_F = 0.018f
