package dev.niccc2007.filet.handlers

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.playback.MAX_SPEED
import dev.niccc2007.filet.playback.MIN_SPEED
import dev.niccc2007.filet.playback.PitchMode
import dev.niccc2007.filet.playback.PrefsTextStore
import dev.niccc2007.filet.playback.RememberStore
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

/** A glyph in the stroke idiom of `FiletIcons`: 1.7 wide, round ends, on a 24 grid. */
private fun strokeGlyph(name: String, vararg d: String): androidx.compose.ui.graphics.vector.ImageVector {
    val b = androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (path in d) {
        b.addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    }
    return b.build()
}

/**
 * The sound sheet's own glyph, on the button that opens it in both players: three vertical
 * sliders, which is what the sheet is.
 *
 * Bug identified here: the button wore the speed dial, from when the sheet was only a speed.
 * It has held the volume, the equaliser and the effects since, and a dial on the button said
 * none of that, so the sheet read as a speed control that happened to have more in it.
 *
 * Each slider is its track broken by a ring for the knob. A bar across the track read as a
 * cross at this size: the round ends closed the gap either side of it.
 */
val SoundSheetGlyph: androidx.compose.ui.graphics.vector.ImageVector = strokeGlyph(
    "soundsheet",
    "M6 4v6.1", "M6 15.9V20", "M8 13a2 2 0 1 1-4 0 2 2 0 0 1 4 0",
    "M12 4v1.1", "M12 10.9V20", "M14 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0",
    "M18 4v9.1", "M18 18.9V20", "M20 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0",
)

/** The how-to: a question mark in a ring. */
private val HowToGlyph = strokeGlyph(
    "howto",
    "M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0",
    "M9.6 9.6a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1.1.9-1.1 1.6v.5",
    "M12 16.9v.1",
)

/**
 * Frame by frame's switch, which only the video player has. The steps themselves are on the
 * bar it puts under the picture: see `FrameBar`.
 */
class FrameBarSwitch(val on: Boolean, val onChange: (Boolean) -> Unit)

/**
 * The sheet's four tabs. The three that ride on effects the phone provides say BETA, and so do
 * their rows in Settings: some phones drop or refuse those effects, and the tag is the
 * difference between "the app is broken" and "that part is new".
 */
enum class SheetTab(val id: String, val label: String, val beta: Boolean) {
    PLAYBACK("playback", "Playback", false),
    LOUDNESS("loudness", "Loudness", true),
    EFFECTS("effects", "Effects", true),
    EQ("eq", "EQ", true);

    companion object {
        fun of(id: String?): SheetTab = entries.firstOrNull { it.id == id } ?: PLAYBACK
    }
}

/**
 * Where the sheet was left, per player: the tab it was on and the sections folded shut.
 *
 * In `filet.sheet`, not the main store: it is where somebody left the sheet, not a setting, and
 * it does not travel in an export.
 */
class SheetMemory(private val store: RememberStore) {

    fun tab(target: SoundTarget): SheetTab = SheetTab.of(store.all()[target.id + TAB])

    fun setTab(target: SoundTarget, tab: SheetTab) = store.put(target.id + TAB, tab.id)

    fun folded(target: SoundTarget): Set<String> =
        store.all()[target.id + FOLDED]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun setFolded(target: SoundTarget, folded: Set<String>) {
        if (folded.isEmpty()) store.remove(target.id + FOLDED)
        else store.put(target.id + FOLDED, folded.sorted().joinToString(","))
    }

    companion object {
        private const val TAB = ".tab"
        private const val FOLDED = ".folded"

        @Volatile private var instance: SheetMemory? = null

        fun of(context: Context): SheetMemory =
            instance ?: synchronized(this) {
                instance ?: SheetMemory(PrefsTextStore(context.applicationContext, "filet.sheet")).also { instance = it }
            }
    }
}

/**
 * The sound sheet: one dialogue for everything about how a file sounds, on both players, as
 * four tabs.
 *
 * - **Playback:** speed and pitch, and on the video player the switch for frame by frame,
 *   because "slower" and "one frame at a time" are the same question asked harder.
 * - **Loudness:** the app's own volume.
 * - **Effects:** what is laid over the sound, one chip each, each with its own switch and
 *   strength. No master switch: they stack.
 * - **EQ:** the equaliser, with its own switch, its presets, and the bands and the bass as
 *   vertical sliders a preset visibly moves.
 *
 * The tab it was left on and the sections folded shut come back with it, per player
 * ([SheetMemory]). The "?" in the header opens a how-to in place of the tabs.
 *
 * It was one long column of all of it, and the effects alone were taller than a landscape phone.
 *
 * ## Each player's own sound
 *
 * The volume, the equaliser and the effects are read here rather than passed in, from
 * [SoundState] for the [target] player: the music's and the video's are apart, so a curve put
 * on a song is not on the next video. A file with "Remember for this track" on has a sheet of
 * its own, and the edits go to it. The SPEED comes in from the player: the music's is shared
 * across the queue and a video's is its own.
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
    frames: FrameBarSwitch? = null,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    val state = remember { SoundState.of(context) }
    val memory = remember { SheetMemory.of(context) }
    val sound by state.sound(target).collectAsStateWithLifecycle()
    val remembered by state.rememberedNow(target).collectAsStateWithLifecycle()
    // The pitch goes with the file while it is remembered, and to the general setting otherwise.
    val pitchTo: (PitchMode) -> Unit = { mode -> state.setPitch(target, mode, onPitch) }
    val engine = remember { Sound.of(context) }
    val refused by engine.refused.collectAsStateWithLifecycle()
    var tab by remember(target) { mutableStateOf(memory.tab(target)) }
    var folded by remember(target) { mutableStateOf(memory.folded(target)) }
    var howTo by remember { mutableStateOf(false) }
    val edit: ((SoundSettings) -> SoundSettings) -> Unit = { change -> state.editSound(target, change) }
    val fold: (String) -> Unit = { id ->
        folded = if (id in folded) folded - id else folded + id
        memory.setFolded(target, folded)
    }
    Dlg(onDismiss = onDismiss) {
        Box(
            Modifier
                .padding(top = 9.dp, bottom = 2.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.high),
        )
        SheetHeader(target, howTo) { howTo = !howTo }
        // Scrolls: the EQ tab with both its sections open is taller than a landscape phone, and a
        // sheet whose bottom rows cannot be reached is a sheet with a setting missing.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            if (howTo) {
                HowTo(target, video = frames != null)
            } else {
                if (state.fileOf(target) != null) {
                    RememberRow(target.rememberLabel, remembered != null, tint, onTint) { on ->
                        state.remember(target, on, speed, pitch)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                TabBar(tab, tint, onTint) { picked ->
                    tab = picked
                    memory.setTab(target, picked)
                }
                Spacer(Modifier.height(14.dp))
                when (tab) {
                    SheetTab.PLAYBACK -> PlaybackTab(speed, maxSpeed, pitch, tint, onTint, onSpeed, pitchTo, frames, folded, fold)
                    SheetTab.LOUDNESS -> LoudnessTab(sound, engine.reverb, SoundPart.BOOST in refused, tint, onTint) { v ->
                        edit { it.copy(volume = v) }
                    }
                    SheetTab.EFFECTS -> EffectsTab(sound, engine.offers, refused, tint, onTint, edit)
                    SheetTab.EQ -> EqTab(sound, refused, tint, onTint, edit, folded, fold)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** "Sound · Music", and the "?" that opens the how-to, or closes it. */
@Composable
private fun SheetHeader(target: SoundTarget, howTo: Boolean, onHowTo: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (howTo) "How to use" else "Sound · ${target.label}",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (howTo) FiletIcons.Close else HowToGlyph,
            if (howTo) "Back to the sound" else "How to use",
            tint = colors.fg2,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onHowTo)
                .padding(8.dp),
        )
    }
}

/** The four tabs, as one segmented row; a BETA under the three that have it. */
@Composable
private fun TabBar(tab: SheetTab, tint: Color, onTint: Color, onTab: (SheetTab) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.sunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (t in SheetTab.entries) {
            val on = t == tab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) tint else Color.Transparent)
                    .clickable { onTab(t) }
                    .semantics {
                        selected = on
                        role = Role.Tab
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t.label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) onTint else colors.fg2,
                    maxLines = 1,
                )
                // Every cell keeps the line, so the four are one height whichever is picked.
                Text(
                    if (t.beta) "BETA" else "",
                    fontSize = 7.5.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = if (on) onTint.copy(alpha = 0.75f) else tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A section that folds shut on its caption, and stays folded the next time the sheet opens.
 * Folded, the caption keeps the section's value beside it, so nothing set is hidden.
 */
@Composable
private fun Fold(
    id: String,
    title: String,
    summary: String?,
    folded: Set<String>,
    onFold: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Filet.colors
    val open = id !in folded
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onFold(id) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(title)
        Spacer(Modifier.weight(1f))
        if (!open && summary != null) Text(summary, fontSize = 11.sp, color = colors.fg2, maxLines = 1)
        Icon(
            FiletIcons.Down,
            if (open) "Fold $title" else "Open $title",
            tint = colors.fg3,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(16.dp)
                .rotate(if (open) 180f else 0f),
        )
    }
    if (open) {
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun PlaybackTab(
    speed: Float,
    maxSpeed: Float,
    pitch: PitchMode,
    tint: Color,
    onTint: Color,
    onSpeed: (Float) -> Unit,
    onPitch: (PitchMode) -> Unit,
    frames: FrameBarSwitch?,
    folded: Set<String>,
    onFold: (String) -> Unit,
) {
    Fold("speed", "PLAYBACK SPEED", speedLabel(speed), folded, onFold) {
        Heading(
            null,
            speedLabel(speed),
            tint,
            reset = if (abs(speed - 1f) > 0.001f) ({ onSpeed(1f) }) else null,
        )
        Spacer(Modifier.height(4.dp))
        SpeedRail(speed, maxSpeed, tint, onTint, onSpeed)
    }
    Spacer(Modifier.height(10.dp))
    Fold("pitch", "PITCH", pitch.label, folded, onFold) {
        PitchModeRow(pitch, tint, onTint, onPitch)
    }
    if (frames != null) {
        Spacer(Modifier.height(10.dp))
        Fold("frames", "FRAME BY FRAME", if (frames.on) "On" else "Off", folded, onFold) {
            FrameRow(frames, tint, onTint)
        }
    }
}

/**
 * The volume, and one honest line on what the effects do to it today: the whole sound lowered
 * to leave them room, so nothing clips, which is why a bass boost can sound quieter overall.
 */
@Composable
private fun LoudnessTab(
    sound: SoundSettings,
    reverb: SoundMaths.Reverb,
    refused: Boolean,
    tint: Color,
    onTint: Color,
    onVolume: (Int) -> Unit,
) {
    VolumeSection(sound.volume, refused, tint, onTint, onVolume)
    val inputDb = SoundMaths.plan(sound, reverb).eqInputDb
    if (inputDb < 0f) {
        Spacer(Modifier.height(6.dp))
        Note(
            "With the EQ or an effect on, the whole sound is lowered by " +
                String.format(java.util.Locale.US, "%.1f dB", -inputDb) +
                " to leave room for them, so nothing can clip. That is why a bass boost can sound " +
                "quieter overall.",
        )
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

/**
 * The small capitals over a section, its value in large, and its reset when it has one. No
 * caption inside a [Fold], whose own line is the caption.
 */
@Composable
private fun Heading(caption: String?, value: String, tint: Color, reset: (() -> Unit)?, valueColor: Color = tint) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (caption != null) {
                Caption(caption)
                Spacer(Modifier.height(2.dp))
            }
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
 * The effects laid over the sound: one chip each, and the one picked opened under them with its
 * switch, what it does, and its strength.
 *
 * No master switch: each effect is its own, and they stack. A chip tapped only opens its effect;
 * the effect's own switch, in the panel, is the one thing that turns it on or off (D-19). Its
 * strength can be set while it is off, and is what plays when it is switched on.
 *
 * Bug identified here: a tap on a chip turned the effect on as it opened it, so looking at
 * Reverb, or getting its size ready, started it playing. The chip now shows which of three it
 * is - off, open but off (ringed), on (filled) - so silence after a tap is explained rather than
 * looking broken.
 *
 * Only what this phone has is offered: without a reverb there is no Reverb chip, and without a
 * virtualizer no Wide, and a line says why rather than leaving a gap. Nightcore, Vaporwave and 8D
 * are the player's own rate and volume, so every phone has them. The effects that need the
 * samples in hand join with the engine that can make them; until then there is no chip for them
 * at all, dead or otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsTab(
    sound: SoundSettings,
    offers: Set<SoundPart>,
    refused: Set<SoundPart>,
    tint: Color,
    onTint: Color,
    /** One write per touch: a change to the settings this sheet's player plays with. */
    edit: ((SoundSettings) -> SoundSettings) -> Unit,
) {
    val colors = Filet.colors
    val shown = SoundMaths.Overlay.entries.filter { it.part == null || it.part in offers }
    var opened by remember { mutableStateOf(shown.firstOrNull { it.of(sound).on } ?: shown.firstOrNull()) }
    val picked = opened?.takeIf { it in shown } ?: shown.firstOrNull()
    if (picked == null) {
        Note("This phone gives this player no effects to lay over the sound.")
        return
    }

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (fx in shown) {
            val level = fx.of(sound)
            Chip(
                fx.label,
                lit = level.on,
                tint = tint,
                onTint = onTint,
                ringed = fx == picked,
                state = if (level.on) "On" else "Off",
            ) { opened = fx }
        }
    }
    Spacer(Modifier.height(14.dp))

    val level = picked.of(sound)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(picked.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                // Off, it still says what it is set to: that is what switching it on will play.
                if (level.on) picked.amountLabel(level.amount) else "Off · " + picked.amountLabel(level.amount),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (level.on) tint else colors.fg3,
                maxLines = 1,
            )
        }
        SoundSwitch(level.on, tint, onTint) { edit { picked.switched(it, !picked.of(it).on) } }
    }
    Spacer(Modifier.height(4.dp))
    Note(picked.about)
    Spacer(Modifier.height(10.dp))
    Caption(if (picked.stops != null) "SIZE" else "STRENGTH")
    Spacer(Modifier.height(2.dp))
    val live = rememberUpdatedState(picked)
    // A touch on the rail sets the strength and leaves the switch alone (D-19).
    val at: (Float) -> Unit = { f -> edit { live.value.withAmount(it, live.value.snap(f * 100f)) } }
    if (picked.stops != null) {
        // Five sizes as five ticks, and only the two ends named: "Large room" under a tick a
        // fifth of the rail wide runs into its neighbours, and the size is named above anyway.
        StopRail(
            fraction = level.amount / 100f,
            bubble = null,
            stops = SoundMaths.Space.SIZES.map { size ->
                RailStop(size.amount / 100f, null, on = size.amount == level.amount) {}
            },
            tint = tint,
            onTint = onTint,
            dim = !level.on,
            onAt = at,
        )
        Row(Modifier.fillMaxWidth()) {
            Note(picked.stops.first())
            Spacer(Modifier.weight(1f))
            Note(picked.stops.last())
        }
    } else {
        StopRail(
            fraction = level.amount / 100f,
            bubble = picked.amountLabel(level.amount),
            stops = STRENGTH_STOPS.map { s ->
                RailStop(s / 100f, "$s", on = s == level.amount) {
                    edit { live.value.withAmount(it, s) }
                }
            },
            tint = tint,
            onTint = onTint,
            dim = !level.on,
            onAt = at,
        )
    }

    if (picked == SoundMaths.Overlay.NIGHTCORE || picked == SoundMaths.Overlay.VAPORWAVE) {
        Spacer(Modifier.height(4.dp))
        Note("On top of the speed on the Speed tab, which stays as it is. Only one of the two at a time.")
    }
    if (!level.on) {
        Spacer(Modifier.height(4.dp))
        Note("Off: set it here first if you like. It plays when its switch is on.")
    }
    Spacer(Modifier.height(10.dp))
    val heard = SoundMaths.Overlay.entries.filter { it.heard(it.of(sound)) }
    // Each part the phone refused, with the effects it leaves unheard by name.
    val deaf = heard.filter { it.part != null && it.part in refused }
    val noEq = deaf.filter { it.part == SoundPart.EQUALISER }
    if (noEq.isNotEmpty()) {
        Note("This phone would not give this player an equalizer, so ${spokenList(noEq.map { it.label })} not heard.", colors.warn)
    }
    if (SoundMaths.Overlay.REVERB in deaf) {
        Note("This phone would not give this player a reverb, so the room is not heard.", colors.warn)
    }
    if (SoundMaths.Overlay.WIDE in deaf) {
        Note("This phone would not give this player a virtualizer, so Wide is not heard.", colors.warn)
    }
    if (SoundPart.SPACE !in offers) Note("This phone has no reverb, so there is no Reverb to pick.")
    if (SoundPart.WIDE !in offers) Note("This phone has no virtualizer, so there is no Wide to pick.")
    // Wide's headroom is a margin, not a figure from the phone's library: said, not hidden.
    Note(
        if (SoundMaths.Overlay.WIDE in heard) "They stack, and none raises the level. Wide keeps a margin against clipping, not a guarantee."
        else "Each effect is its own and they stack. None of them raises the level, so they cannot make it clip.",
    )
}

/** "Muffled is", "Muffled and Radio are", "Muffled, Radio and Even out are". */
internal fun spokenList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0] + " is"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last() + " are"
}

/** The strengths a filter's rail names. */
private val STRENGTH_STOPS = listOf(0, 25, 50, 75, 100)

/**
 * The equalizer: its own switch, its presets, and the five bands and the bass as vertical
 * sliders.
 *
 * A preset moves the sliders, visibly: they slide to it rather than jump, so a tap on Rock shows
 * what Rock is. Touching a slider afterwards makes the curve "Custom" on its own, because the
 * name is whichever preset all six match exactly.
 *
 * Touching any of it while the switch is off switches it on. A curve dragged with the switch off
 * is a change nobody can hear, and the next thing anybody does after that is decide the
 * equaliser is broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqTab(
    sound: SoundSettings,
    refused: Set<SoundPart>,
    tint: Color,
    onTint: Color,
    edit: ((SoundSettings) -> SoundSettings) -> Unit,
    folded: Set<String>,
    onFold: (String) -> Unit,
) {
    val colors = Filet.colors
    val on = sound.eqOn
    val preset = SoundMaths.EqPreset.of(sound)
    // A touch with the switch off switches it on, in the same write as the touch.
    val touch: ((SoundSettings) -> SoundSettings) -> Unit = { change -> edit { change(it).copy(eqOn = true) } }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { edit { it.copy(eqOn = !on) } },
        ) {
            Caption("EQUALIZER")
            Spacer(Modifier.height(2.dp))
            Text(
                if (on) eqName(sound) else "Off",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = if (on) tint else colors.fg3,
                maxLines = 1,
            )
        }
        if (preset != SoundMaths.EqPreset.FLAT) {
            ResetChip { edit { SoundMaths.EqPreset.FLAT.on(it).copy(eqOn = it.eqOn) } }
            Spacer(Modifier.width(8.dp))
        }
        SoundSwitch(on, tint, onTint, Modifier.padding(bottom = 6.dp)) { edit { it.copy(eqOn = !on) } }
    }
    Spacer(Modifier.height(6.dp))
    if (SoundPart.EQUALISER in refused && SoundMaths.plan(sound).equalises) {
        Note("This phone would not give this player an equalizer, so the curve is not heard.", colors.warn)
    }
    Note(
        if (on) "Shapes the sound and never raises its level, so it cannot make it clip."
        else "Off: the sound is the file's own. Touching any of it switches it on.",
    )
    Spacer(Modifier.height(10.dp))

    Fold("presets", "EQ PRESETS", eqName(sound), folded, onFold) {
        // Wrapping rather than scrolling sideways: seventeen names do not fit across a phone,
        // and a row that hides its last few hides them from anybody who does not think to swipe.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (p in SoundMaths.EqPreset.entries) {
                Chip(p.label, lit = on && p == preset, tint = tint, onTint = onTint) { edit { p.on(it) } }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Fold("bands", "BANDS AND BASS", null, folded, onFold) {
        Spacer(Modifier.height(4.dp))
        EqSliders(sound, dim = !on, tint = tint, touch = touch)
    }
}

/**
 * A chip, lit when its thing is on. [ringed] is the one opened under the chips, which on the
 * Effects tab is not the same as on: off, open but off, and on are three looks.
 *
 * @param state said to a screen reader with the label, for a chip whose on and open differ.
 */
@Composable
private fun Chip(
    label: String,
    lit: Boolean,
    tint: Color,
    onTint: Color,
    ringed: Boolean = false,
    state: String? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(50)
    Text(
        label,
        fontSize = 11.5.sp,
        fontWeight = if (lit || ringed) FontWeight.SemiBold else FontWeight.Normal,
        color = if (lit) onTint else colors.fg2,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(shape)
            .background(if (lit) tint else colors.sunken)
            .then(if (ringed) Modifier.border(1.5.dp, if (lit) onTint.copy(alpha = 0.7f) else tint, shape) else Modifier)
            .semantics {
                selected = if (state != null) ringed else lit
                role = Role.Button
                if (state != null) stateDescription = state
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * The five bands and the bass, side by side, as an equaliser has always been drawn.
 *
 * The bass is its own slider and its own scale, a boost from nothing to all of it rather than
 * a gain either way, so a gap sets it apart from the five.
 */
@Composable
private fun EqSliders(
    sound: SoundSettings,
    dim: Boolean,
    tint: Color,
    touch: ((SoundSettings) -> SoundSettings) -> Unit,
) {
    val range = SoundMaths.BAND_RANGE
    val span = (range.last - range.first).toFloat()
    Row(Modifier.fillMaxWidth()) {
        SoundMaths.BANDS_HZ.forEachIndexed { i, hz ->
            val gain = sound.bands.getOrElse(i) { 0 }
            BandColumn(
                label = hzLabel(hz),
                value = dbLabel(gain),
                fraction = (gain - range.first) / span,
                from = -range.first / span,
                dim = dim,
                tint = tint,
                modifier = Modifier.weight(1f),
            ) { f ->
                val next = (range.first + f * span).roundToInt().coerceIn(range)
                touch { s ->
                    if (next != s.bands.getOrElse(i) { 0 }) s.copy(bands = s.bands.toMutableList().also { it[i] = next }) else s
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        BandColumn(
            label = "Bass",
            value = "${sound.bass}%",
            fraction = sound.bass / 100f,
            from = 0f,
            dim = dim,
            tint = tint,
            modifier = Modifier.weight(1f),
        ) { f ->
            touch { it.copy(bass = ((f * 100f) / 5f).roundToInt() * 5) }
        }
    }
}

/** One slider of the equaliser: its value over it, its name under it. */
@Composable
private fun BandColumn(
    label: String,
    value: String,
    fraction: Float,
    from: Float,
    dim: Boolean,
    tint: Color,
    modifier: Modifier,
    onAt: (Float) -> Unit,
) {
    val colors = Filet.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (dim) colors.fg3 else colors.fg2,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        VerticalSlider(fraction, from, dim, tint, label, value, Modifier.fillMaxWidth().height(SLIDER_H), onAt)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = colors.fg3, maxLines = 1)
    }
}

/**
 * A vertical rail: the track, the fill from [from], and the knob.
 *
 * The knob eases to where it is set, so a preset is seen moving the curve rather than the curve
 * being replaced. Not while a finger is on it: a knob that trails the finger reads as a heavy
 * control.
 *
 * Its own pointer handling consumes every move it sees. The sheet scrolls, and without that a
 * vertical drag that starts on a band goes to the scroll: dragging a band up would move the
 * sheet and leave the band where it was.
 *
 * @param onAt a touch, as a point 0 (bottom) to 1 (top) along the travel. The caller snaps it.
 */
@Composable
private fun VerticalSlider(
    fraction: Float,
    from: Float,
    dim: Boolean,
    tint: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onAt: (Float) -> Unit,
) {
    val colors = Filet.colors
    val density = LocalDensity.current
    val knobPx = with(density) { KNOB.toPx() }
    var height by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val live = rememberUpdatedState(onAt)
    val shown by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        animationSpec = if (dragging) snap() else tween(PRESET_EASE_MS),
        label = "band",
    )
    val fill = if (dim) colors.fg3 else tint

    fun at(y: Float) {
        if (height <= 0f) return
        val run = (height - knobPx * 2f).coerceAtLeast(1f)
        live.value((1f - (y - knobPx) / run).coerceIn(0f, 1f))
    }

    Canvas(
        modifier
            .onSizeChanged { height = it.height.toFloat() }
            .semantics {
                contentDescription = label
                stateDescription = value
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    at(down.position.y)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            at(change.position.y)
                        }
                    } finally {
                        dragging = false
                    }
                }
            },
    ) {
        val cx = size.width / 2f
        val track = 4.dp.toPx()
        // Inset by the knob's radius at both ends, as on the flat rails, or the knob is half off
        // at +12 and -12, the two values a band is dragged to most.
        val y0 = knobPx
        val run = (size.height - knobPx * 2f).coerceAtLeast(1f)
        fun yOf(f: Float) = y0 + run * (1f - f)
        drawRoundRect(
            colors.high,
            topLeft = Offset(cx - track / 2f, y0),
            size = Size(track, run),
            cornerRadius = CornerRadius(track, track),
        )
        val top = yOf(max(from, shown))
        val bottom = yOf(min(from, shown))
        drawRoundRect(
            fill,
            topLeft = Offset(cx - track / 2f, top),
            size = Size(track, bottom - top),
            cornerRadius = CornerRadius(track, track),
        )
        if (from > 0f) {
            // The flat line, so +2 and -2 read as either side of something.
            val half = 7.dp.toPx()
            drawLine(
                colors.fg3.copy(alpha = 0.65f),
                Offset(cx - half, yOf(from)),
                Offset(cx + half, yOf(from)),
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawCircle(fill, radius = knobPx, center = Offset(cx, yOf(shown)))
    }
}

private val SLIDER_H = 150.dp

/** How long a slider takes to reach a preset: long enough to be seen moving, short enough not to wait for. */
private const val PRESET_EASE_MS = 320

/**
 * The how-to: what each tab is for, and why three of them say BETA. It takes the tabs' place
 * while it is open, so the sheet keeps its place and the same button goes back.
 */
@Composable
private fun HowTo(target: SoundTarget, video: Boolean) {
    val parts = listOf(
        "Playback" to
            "Speed from a tenth to as fast as this phone will play, snapping to the marked stops; " +
            "tap a stop's number for it at once. Pitch says what the speed does to voices." +
            if (video) " Frame by frame puts a bar under the picture for stepping through it." else "",
        "Loudness" to
            "The app's own volume, on top of the phone's. Above 100% a limiter keeps the loud parts " +
            "from clipping.",
        "Effects" to
            "One chip per effect. A tap opens it; its own switch turns it on, and its strength can " +
            "be set first. They stack.",
        "EQ" to
            "Pick a preset and the sliders move to it, or drag the sliders yourself; the curve is " +
            "Custom once it matches no preset. Bass has its own slider. Reset puts it back flat.",
        target.rememberLabel to
            "Keeps this whole sheet for this one file. Off, the file plays with the player's own " +
            "settings. ${target.label} and ${otherOf(target).label.lowercase()} each keep their own.",
    )
    for ((title, text) in parts) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Note(text)
        Spacer(Modifier.height(12.dp))
    }
    Caption("WHY BETA")
    Spacer(Modifier.height(4.dp))
    Note(
        "Loudness, Effects and EQ ride on sound effects the phone itself provides, and phones differ: " +
            "some refuse them or drop them. When this phone refuses one, the tab says so rather than " +
            "going quiet.",
    )
}

private fun otherOf(target: SoundTarget): SoundTarget =
    if (target == SoundTarget.MUSIC) SoundTarget.VIDEO else SoundTarget.MUSIC

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

/** What the equalizer is set to, in a word: the preset its curve matches, or "Custom". */
fun eqName(s: SoundSettings): String = SoundMaths.EqPreset.of(s)?.label ?: "Custom"

/** "60 Hz", "910 Hz", "3.6 kHz", "14 kHz". */
fun hzLabel(hz: Int): String =
    if (hz < 1000) "$hz Hz"
    else String.format(java.util.Locale.US, "%.1f", hz / 1000.0).removeSuffix(".0") + " kHz"

/** "+3 dB", "0 dB", "-6 dB". */
fun dbLabel(db: Int): String = (if (db > 0) "+$db" else "$db") + " dB"

/**
 * What the sound chip says with room for all of it: whatever is not at its default, or the speed
 * when nothing is. The first of [soundChipLadder].
 *
 * A lit chip reading "1×" would be lit for a reason it does not give.
 */
fun soundChipLabel(speed: Float, sound: SoundSettings): String = soundChipLadder(speed, sound).first()

/**
 * Everything the sound chip can say, longest first, down to nothing: the chip shows the first
 * that fits the width it is left (see [fitLadder]).
 *
 * Bug identified here: the chip said everything off its default on one line, and with a speed, a
 * boost, a preset and three effects on it took the whole row. The Sleep chip beside it was
 * squeezed to a column and wrapped two letters at a time. The chip now gets only the width the
 * row's other buttons leave, and gives way in this order:
 *
 * 1. every part by name: `0.75× · 200% · Spoken word · Large hall · Muffled · Radio`;
 * 2. the effects counted after the curve's name: `0.75× · 200% · Spoken word · 3 effects`;
 * 3. the curve counted with them: `0.75× · 200% · 4 effects`;
 * 4. the speed and the volume: `0.75× · 200%`;
 * 5. the first of those alone: `0.75×`;
 * 6. nothing: the lit glyph alone, which is never cut mid-word.
 *
 * The speed outlives the rest because it is what the chip was first; the volume is next because
 * a boost is the setting most worth seeing. A rung no shorter than the one above it is left out,
 * so every step down saves width.
 */
fun soundChipLadder(speed: Float, sound: SoundSettings): List<String> {
    val head = listOfNotNull(
        if (abs(speed - 1f) > 0.001f) speedLabel(speed) else null,
        if (sound.volume != 100) "${sound.volume}%" else null,
    )
    val eq = if (eqHeard(sound)) eqName(sound) else null
    val effects = effectsHeard(sound)
    val rungs = mutableListOf<String>()
    fun rung(vararg parts: String?) {
        val text = parts.filterNotNull().joinToString(" · ")
        if (text.isNotEmpty() && (rungs.isEmpty() || text.length < rungs.last().length)) rungs += text
    }
    if (head.isEmpty() && eq == null && effects.isEmpty()) return listOf(speedLabel(speed), "")
    rung(*(head + listOfNotNull(eq) + effects).toTypedArray())
    if (effects.size > 1) rung(*(head + listOfNotNull(eq, effectCount(effects.size))).toTypedArray())
    val all = effects.size + (if (eq != null) 1 else 0)
    if (all > 0) rung(*(head + effectCount(all)).toTypedArray())
    rung(*head.toTypedArray())
    rung(head.firstOrNull())
    return rungs + ""
}

/** "1 effect", "4 effects". */
private fun effectCount(n: Int): String = if (n == 1) "1 effect" else "$n effects"

/** The first of [ladder] that [fits], or nothing when none does. */
fun fitLadder(ladder: List<String>, fits: (String) -> Boolean): String = ladder.firstOrNull(fits) ?: ""

/** Whether the sound chip is lit: anything in the sheet away from its default. */
fun soundChipLit(speed: Float, sound: SoundSettings): Boolean =
    abs(speed - 1f) > 0.001f || sound.volume != 100 || eqHeard(sound) || effectsHeard(sound).isNotEmpty()

/** The equalizer is on AND set to something: the switch on over a flat curve changes nothing. */
private fun eqHeard(sound: SoundSettings): Boolean =
    sound.eqOn && SoundMaths.EqPreset.of(sound) != SoundMaths.EqPreset.FLAT

/** The effects that are heard, by name: the reverb by its size. */
fun effectsHeard(sound: SoundSettings): List<String> =
    SoundMaths.Overlay.entries.filter { it.heard(it.of(sound)) }.map {
        if (it == SoundMaths.Overlay.REVERB) SoundMaths.Space.sized(sound.reverb.amount).label else it.label
    }

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
