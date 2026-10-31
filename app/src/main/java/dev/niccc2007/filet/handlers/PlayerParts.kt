package dev.niccc2007.filet.handlers

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import dev.niccc2007.filet.playback.FaceShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.playback.Medium
import dev.niccc2007.filet.playback.NowPlaying
import dev.niccc2007.filet.playback.Repeat
import dev.niccc2007.filet.playback.Track
import dev.niccc2007.filet.playback.marqueeFade
import dev.niccc2007.filet.playback.rememberMarquee
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * The pieces every player face is built from.
 *
 * ## Why this file exists
 *
 * There are four faces. Four faces written as four screens is four transports, four scrub
 * bars and four queue panels, and the fourth copy of a control is the one that silently stops
 * matching the others - the player ends up with a repeat button that cycles the wrong way on
 * one layout and nobody notices for a month.
 *
 * So a face is only an ARRANGEMENT. Every control in it comes from here, and the only thing
 * `AudioScreen` does per face is decide what goes where and how big. That is also what keeps a
 * face cheap enough to be worth having: adding one is a layout, not a player.
 *
 * Measurements are the mock's, taken off the CSS rather than matched by eye - see
 * `filet-bar-dock-mock.html` section 3. Where a number appears here with no comment it is that
 * file's value at `423dp`.
 */

// ── the top bar ───────────────────────────────────────────────────────────────

/**
 * The bar across the top: close, where this is playing from, and the mode.
 *
 * Replaces `ViewerBar` on the three ambient faces. The difference is not decoration: a
 * `ViewerBar` states the FILE, and on a player the file's name is already the title in the
 * middle of the screen. What is missing there - and what the mock promotes to the bar - is the
 * CONTEXT: which folder this queue came from, and whether anything is allowed to interrupt it.
 *
 * `.pltop{display:flex;align-items:center;gap:10px;padding:12px 12px 2px}` with 36dp icons.
 *
 * @param eyebrow the small upper line. The word "Uninterruptible" when that is on, because a lit
 *   glyph is a state somebody has to learn, and otherwise what the queue is.
 * @param context the line under it: the folder, as a path tail.
 */
@Composable
fun PlayerTopBar(
    eyebrow: String,
    eyebrowLit: Boolean,
    context: String,
    uninterrupted: Boolean,
    onClose: () -> Unit,
    onUninterrupted: () -> Unit,
    tint: Color,
    overflow: @Composable () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A chevron DOWN, not a back arrow. The player is a thing that comes up over the app
        // and goes back down to a strip; an arrow pointing left promises the file list.
        BarIcon(FiletIcons.Down, "Minimise the player", onClick = onClose)

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                eyebrow,
                fontSize = 8.5.sp,
                letterSpacing = 1.5.sp,
                fontWeight = if (eyebrowLit) FontWeight.Bold else FontWeight.Normal,
                color = if (eyebrowLit) tint else colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                context,
                fontSize = 11.5.sp,
                color = colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Uninterrupted lives HERE and not in the chip row. It is a mode, and a mode belongs
        // in the chrome where its state can be read without anything being opened - and it is
        // not trivia: it is the switch that decides whether a phone call takes the sound away.
        BarIcon(
            FiletIcons.Shield,
            if (uninterrupted) "Uninterruptible — nothing will pause this" else "Uninterruptible playback",
            lit = uninterrupted,
            tint = tint,
            onClick = onUninterrupted,
        )
        overflow()
    }
}

/** One 36dp round button in the top bar. `.pltop .ico{width:36px;height:36px;border-radius:18px}` */
@Composable
fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    lit: Boolean = false,
    tint: Color = Filet.colors.accent,
    /** Something behind this button is working. See `ActivityDots`. */
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            // `.ico.mode.on{color:var(--pal);background:var(--palSoft)}`
            .background(if (lit) tint.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = if (lit) tint else colors.fg2,
            modifier = Modifier.size(19.dp),
        )
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

/** The overflow, with the actions that are neither modes nor frequent. */
@Composable
fun PlayerOverflow(
    face: dev.niccc2007.filet.playback.PlayerFace,
    onFace: (dev.niccc2007.filet.playback.PlayerFace) -> Unit,
    onExternal: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onSaveQueue: (() -> Unit)? = null,
    /** Aurora's own switch. Null on every other face, where it does nothing. */
    scratch: Boolean? = null,
    onScratch: (() -> Unit)? = null,
    /** How much track a turn of the disc is worth, 0 to 1, with its own readout. */
    travel: Float? = null,
    travelLabel: String? = null,
    onTravel: ((Float) -> Unit)? = null,
    /** The background glow, which every face has. */
    glow: Boolean? = null,
    onGlow: (() -> Unit)? = null,
    /** How bright the mist round the DISC is, 0 to 1. Null on a face with no disc. */
    glowStrength: Float? = null,
    onGlowStrength: ((Float) -> Unit)? = null,
    /**
     * How vibrant the coloured background is, 0 to 1. Null on Classic, which has none.
     *
     * A separate control from [glowStrength] on purpose - see `Prefs.vibrancy`. They were one
     * number, and that is why a slider called "glow strength" appeared on looks where nothing
     * it changed was visible.
     */
    vibrancy: Float? = null,
    onVibrancy: ((Float) -> Unit)? = null,
    /** The glow answering the music. Null when the glow is off - it is its sub-switch. */
    pulse: Boolean? = null,
    onPulse: (() -> Unit)? = null,
    /**
     * This track's own beat correction in ms, positive is later, with its step and its reset.
     * Null when there is no pulse to correct. See `BeatNudge`.
     */
    nudge: Int? = null,
    onNudge: ((Int) -> Unit)? = null,
    onNudgeReset: (() -> Unit)? = null,
    /** What counts as a beat, 0 to 1 across the allowed gain. Null when the pulse is off. */
    sense: Float? = null,
    onSense: ((Float) -> Unit)? = null,
    /** How far a beat swings the glow, 0 to 1. Null when the pulse is off. */
    depth: Float? = null,
    onDepth: ((Float) -> Unit)? = null,
    /**
     * How far the beat wave sits off the artwork, and how deep its peaks are.
     *
     * Null on any face with no wave on it, which is every face except Aurora. These were
     * offered everywhere and did nothing on four looks out of five.
     */
    waveGap: Float? = null,
    onWaveGap: ((Float) -> Unit)? = null,
    waveSize: Float? = null,
    onWaveSize: ((Float) -> Unit)? = null,
    waveRest: Float? = null,
    onWaveRest: ((Float) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(PanelPage.MAIN) }
    val colors = Filet.colors
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    // The beat map's readout lives only where there is a wave to show for it - see
    // `PlayerFace.hasWave`. A bar and a button on a look that draws no wave would be a control
    // that does nothing visible, and the dot with them: a dot for work this panel cannot show.
    val beatShown = face.hasWave
    val beat by dev.niccc2007.filet.playback.BeatWork.progress.collectAsState()
    val working by dev.niccc2007.filet.playback.BeatWork.dot.collectAsState()
    // Opening the panel while it runs is having seen it, and the dot goes.
    if (open && beatShown) {
        LaunchedEffect(beat.run) { dev.niccc2007.filet.playback.BeatWork.panelSeen() }
    }
    Box {
        BarIcon(FiletIcons.More, "More", dot = beatShown && working) { open = true; page = PanelPage.MAIN }
        if (open) {
            Popup(
                popupPositionProvider = BelowPositionProvider(gap),
                onDismissRequest = { open = false; page = PanelPage.MAIN },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(254.dp)
                        .shadow(24.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.raised)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(22.dp))
                        .padding(vertical = 14.dp),
                ) {
                    when (page) {
                        PanelPage.FACES -> {
                            PanelBack("THE LOOK") { page = PanelPage.MAIN }
                            for (f in dev.niccc2007.filet.playback.PlayerFace.entries) {
                                FaceRow(f, f == face) {
                                    open = false
                                    page = PanelPage.MAIN
                                    onFace(f)
                                }
                            }
                        }

                        // ── the light, on its own page ──────────────────────────────
                        //
                        // Seven sliders used to sit on the panel's front page, under two
                        // switches, on a 254dp popup over the thing they were changing. Every
                        // one of them earns its place and all of them together are a wall -
                        // and the two controls somebody opens this panel for most often, the
                        // look and the queue, were below the wall.
                        //
                        // The panel already knew how to be two pages: the look picker has
                        // worked that way from the start. This is the same mechanism, so it
                        // costs no new concept, and the front page is now six rows.
                        PanelPage.GLOW -> {
                            PanelBack("GLOW AND BEAT") { page = PanelPage.MAIN }
                            // Three independent things, drawn as three. They were nested
                            // under one switch, which is how the strength control came to be
                            // hidden on four of the five faces and to disappear on the fifth
                            // whenever the disc's glow was turned off - the backdrop it
                            // governs is on every face and has no switch at all.
                            // The background first: it is the thing the whole player sits
                            // on, and it is the one every look here has.
                            if (vibrancy != null && onVibrancy != null) {
                                PanelSlider(
                                    "Background colour", vibrancy, onChange = onVibrancy,
                                )
                            }
                            // Then the disc's own light - switch, then how strong it is,
                            // in that order so the slider reads as belonging to the switch
                            // above it rather than to the background above that.
                            if (glow != null && onGlow != null) {
                                PanelSwitch("Glow around the disc", glow, onGlow)
                            }
                            if (glowStrength != null && onGlowStrength != null) {
                                PanelSlider(
                                    "How far the disc glows", glowStrength,
                                    onChange = onGlowStrength,
                                )
                            }
                            if (pulse != null && onPulse != null) {
                                PanelSwitch("Pulse on the beat", pulse, onPulse)
                                // First under the switch: it is the one row here that is about
                                // this record rather than every record.
                                if (pulse && nudge != null && onNudge != null && onNudgeReset != null) {
                                    PanelNudge(nudge, onNudge, onNudgeReset)
                                }
                                if (pulse && sense != null && onSense != null) {
                                    // Which beats fire, as against how far they move.
                                    // Both exist because neither can do the other's job.
                                    PanelSlider("Sensitivity", sense, onChange = onSense)
                                }
                                if (pulse && depth != null && onDepth != null) {
                                    PanelSlider("How much it reacts", depth, onChange = onDepth)
                                }
                                if (pulse && waveGap != null && onWaveGap != null) {
                                    PanelSlider("Wave distance", waveGap, onChange = onWaveGap)
                                }
                            }
                        }

                        // And the record's two, which only exist on a face that has one.
                        PanelPage.DISC -> {
                            PanelBack("THE RECORD") { page = PanelPage.MAIN }
                            if (travel != null && onTravel != null) {
                                PanelSlider(
                                    "Disc travel", travel, travelLabel, sub = false, onTravel,
                                )
                            }
                            if (scratch != null && onScratch != null) {
                                PanelSwitch("Sound follows the disc", scratch, onScratch)
                            }
                        }

                        PanelPage.MAIN -> {
                            PanelHead("NOW PLAYING")
                            if (beatShown) {
                                PanelBeatMap(beat) { dev.niccc2007.filet.playback.BeatWork.requestRebuild() }
                                PanelRule()
                            }
                            // The face switcher is here rather than only in Settings. Five
                            // looks are worth nothing if changing them means leaving the thing
                            // you are looking at - this is the one screen where it shows.
                            PanelAction(
                                "Look",
                                FiletIcons.Eye,
                                trailing = face.label,
                            ) { page = PanelPage.FACES }
                            if (onAddToPlaylist != null) {
                                PanelAction("Add to a playlist", FiletIcons.Rows) {
                                    open = false
                                    onAddToPlaylist()
                                }
                            }
                            if (onSaveQueue != null) {
                                // "Save the queue" rather than "new playlist": the queue is
                                // already a list somebody arranged by playing it, and this
                                // keeps THAT rather than opening an empty one to fill in.
                                PanelAction("Save the queue", FiletIcons.Save) {
                                    open = false
                                    onSaveQueue()
                                }
                            }
                            // One row each for the two groups of settings, with the state in
                            // the trailing label so the panel still says what is on without
                            // being opened.
                            // Reachable whenever the page has anything on it, which is not
                            // the same as "this face has a disc glow".
                            //
                            // Bug identified here: the switch on this page became the DISC's
                            // glow, which only Halo has, and the row was gated on that switch
                            // being offered - so on Ambient, Editorial and Immersive the whole
                            // page vanished, taking the pulse, the sensitivity and the
                            // strength with it. Those faces all pulse; they simply have no
                            // disc to ring. Strength applies to every face there is.
                            if (glow != null || pulse != null || onGlowStrength != null || onVibrancy != null) {
                                PanelRule()
                                PanelAction(
                                    "Glow and beat",
                                    FiletIcons.Eye,
                                    trailing = when {
                                        pulse == true -> "beat"
                                        glow == false -> "no disc glow"
                                        else -> "on"
                                    },
                                ) { page = PanelPage.GLOW }
                            }
                            if (travel != null || scratch != null) {
                                PanelAction(
                                    "The record",
                                    FiletIcons.Audio,
                                    trailing = travelLabel,
                                ) { page = PanelPage.DISC }
                            }
                            PanelRule()
                            PanelAction("Open in another app", FiletIcons.Open) {
                                open = false
                                onExternal()
                            }
                            PanelAction("Share", FiletIcons.Share) { open = false; onShare() }
                            PanelAction("Properties", FiletIcons.Info) {
                                open = false
                                onProperties()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which page of the player's panel is showing.
 *
 * The panel was one page with two switches and seven sliders on it, which is a wall of controls
 * over the very thing they change - and the two rows somebody opens it for most, the look and
 * the queue, sat above that wall and got lost in it. It already knew how to be two pages for
 * the look picker; this is the same mechanism with two more.
 */
private enum class PanelPage { MAIN, FACES, GLOW, DISC }

/**
 * A page's heading, with the way back out of it.
 *
 * The look picker had no way back: it was reached by tapping Look and left by dismissing the
 * whole panel, which with two more pages would mean three dead ends. One row, at the top, where
 * a heading already was.
 */
@Composable
private fun PanelBack(text: String, onBack: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Back,
            null,
            tint = colors.fg3,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
            color = colors.fg3,
        )
    }
}

/**
 * The player's panel, and the strip's, are the same object.
 *
 * It was a `DropdownMenu` - square corners, full-bleed rows, a divider drawn edge to edge -
 * hanging off a player whose every other surface is rounded. Asked for directly: make it look
 * like the one on the minimised player. So it is the same shape, the same 22dp radius, the
 * same hairline border and the same eyebrow, and the two now read as one app.
 */
@Composable
private fun PanelHead(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 17.dp, bottom = 9.dp),
    )
}

/**
 * A labelled slider, sized for the panel rather than for a settings page.
 *
 * Indented to the sub-switch's margin, because that is what it belongs to, and drawn with
 * the thumb small: at 254dp wide a Material slider's default furniture is most of the row.
 */
@Composable
private fun PanelSlider(
    label: String,
    value: Float,
    /** What the current value actually means, where that is not obvious from the name. */
    detail: String? = null,
    /** False for a control that is not a sub-setting of the switch above it. */
    sub: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier.padding(start = if (sub) 31.dp else 17.dp, end = 17.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.5.sp, color = colors.fg3, modifier = Modifier.weight(1f))
            if (detail != null) Text(detail, fontSize = 10.5.sp, color = colors.accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.height(22.dp),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.sunken,
            ),
        )
    }
}

/**
 * The beat map: what it is doing, how far it has got, and a way to make it again.
 *
 * Every figure here is read from the work itself - see `BeatProgress`. Decoding shows a bar only
 * when the decoder reports how far through the file it is; without that it says so in words.
 * A bar that moved on a timer would be worse than none, because this row exists for somebody
 * who does not trust that the work is happening.
 */
@Composable
private fun PanelBeatMap(p: dev.niccc2007.filet.playback.BeatProgress, onBuild: () -> Unit) {
    val colors = Filet.colors
    val pct = p.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    val status = when (p.phase) {
        dev.niccc2007.filet.playback.BeatPhase.IDLE -> "not running"
        dev.niccc2007.filet.playback.BeatPhase.DECODING -> if (pct == null) "reading the file" else "reading the file $pct%"
        dev.niccc2007.filet.playback.BeatPhase.MEASURING -> "measuring ${pct ?: 0}%"
        dev.niccc2007.filet.playback.BeatPhase.DONE -> if (p.cached) "ready, from the cache" else "ready"
    }
    Column(Modifier.padding(horizontal = 17.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Beat map", fontSize = 12.5.sp, color = colors.fg2)
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                fontSize = 10.5.sp,
                color = if (p.working) colors.accent else colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (p.working) "Building" else "Build",
                fontSize = 11.sp,
                color = if (p.working) colors.fg3 else colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (p.working) colors.sunken else colors.accent.copy(alpha = 0.12f))
                    .clickable(enabled = !p.working, onClick = onBuild)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        val shown = p.fraction
        if (p.working && shown != null) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { shown },
                color = colors.accent,
                trackColor = colors.sunken,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
            )
        }
        if (p.working) {
            Text(
                "This process can be canceled in the activity list.",
                fontSize = 9.5.sp,
                color = colors.fg3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * "This song · − +30 ms later + · ↺": the pulse earlier or later for this record alone.
 *
 * 10 ms a tap, quicker the longer a button is held ([BeatNudge.holdDelay]). The reset is drawn
 * only when there is something to reset. Steps are handed on as a difference rather than a new
 * value, so a held button stepping faster than the panel redraws still counts every step.
 */
@Composable
private fun PanelNudge(nudge: Int, onNudge: (Int) -> Unit, onReset: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 31.dp, end = 13.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This song", fontSize = 10.5.sp, color = colors.fg3, maxLines = 1, modifier = Modifier.weight(1f))
        NudgeButton("−", "Pulse 10 ms earlier") { onNudge(-dev.niccc2007.filet.playback.BeatNudge.STEP_MS) }
        Text(
            dev.niccc2007.filet.playback.BeatNudge.label(nudge),
            fontSize = 10.sp,
            color = if (nudge != 0) colors.accent else colors.fg3,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )
        NudgeButton("+", "Pulse 10 ms later") { onNudge(dev.niccc2007.filet.playback.BeatNudge.STEP_MS) }
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (nudge != 0) {
                Icon(
                    FiletIcons.Undo,
                    "Back to 0 ms for this song",
                    tint = colors.fg3,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onReset)
                        .padding(5.dp),
                )
            }
        }
    }
}

/** A step button for [PanelNudge] that repeats while held, faster the longer it is. */
@Composable
private fun NudgeButton(glyph: String, label: String, onStep: () -> Unit) {
    val colors = Filet.colors
    val step by rememberUpdatedState(onStep)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (down) colors.accent.copy(alpha = 0.24f) else colors.sunken)
            .semantics {
                contentDescription = label
                role = androidx.compose.ui.semantics.Role.Button
                onClick(label) { step(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    step()
                    val run = scope.launch {
                        var repeat = 0
                        while (true) {
                            kotlinx.coroutines.delay(dev.niccc2007.filet.playback.BeatNudge.holdDelay(repeat))
                            step()
                            repeat++
                        }
                    }
                    tryAwaitRelease()
                    run.cancel()
                    down = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.fg2)
    }
}

/** A hairline that stops short of the edge, so the panel's corners stay round. */
@Composable
private fun PanelRule() {
    Box(
        Modifier
            .padding(horizontal = 17.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Filet.colors.lineSoft),
    )
}

/** One action: a glyph, a name, and - where it has one - the state it is in. */
@Composable
private fun PanelAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 12.5.sp, color = colors.fg2, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, color = colors.accent)
            Spacer(Modifier.width(4.dp))
            Icon(
                FiletIcons.Forward, null,
                tint = colors.fg3, modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** A row that is on or off, in the same panel as the actions. */
@Composable
private fun PanelSwitch(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    /** A switch that belongs to the one above it: indented, and a shade quieter. */
    sub: Boolean = false,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (sub) 31.dp else 17.dp, end = 17.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (sub) 11.5.sp else 12.5.sp,
            color = if (on) colors.accent else colors.fg2,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(width = 34.dp, height = 19.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) colors.accent.copy(alpha = 0.3f) else colors.sunken),
        ) {
            Box(
                Modifier
                    .padding(start = if (on) 17.dp else 2.dp)
                    .align(Alignment.CenterStart)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.accent else colors.fg3),
            )
        }
    }
}

/** One look, with the sentence that says what it is. */
@Composable
private fun FaceRow(
    f: dev.niccc2007.filet.playback.PlayerFace,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                f.label,
                fontSize = 12.5.sp,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (picked) colors.accent else colors.fg2,
            )
            Text(f.about, fontSize = 9.5.sp, lineHeight = 12.sp, color = colors.fg3)
        }
        if (picked) {
            Spacer(Modifier.width(10.dp))
            Icon(
                FiletIcons.Check, null,
                tint = colors.accent, modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Under the button and right-aligned to it: this one hangs off the top bar. */
private class BelowPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val room = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, room)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

// ── the artwork ───────────────────────────────────────────────────────────────

/**
 * The square: cover art, a looping peek of the video, or the placeholder.
 *
 * Shrinks when paused, and the shadow goes with it - the only feedback a player screen can
 * give about its own state without words. The shadow is animated rather than fixed because a
 * card that changes size under a constant shadow reads as a scaling bitmap rather than as an
 * object lifting.
 *
 * `.art{border-radius:20px;box-shadow:0 22px 50px rgba(0,0,0,.6)}`,
 * `.art.paused{transform:scale(.885)}`.
 */
/**
 * The outer column of a player face: measures the screen once and tells everything inside it
 * what shape it is.
 *
 * ## Why the measuring is here and not in [FaceLayout]
 *
 * Three groups need the answer and only one of them is the art-and-controls pair: the bar at the
 * top drops a label sideways, the queue handle at the bottom is dropped entirely because the
 * strip of height it needs is the room the transport is using, and [artSizing] caps the picture
 * on the axis that is scarce. Measured inside the layout, the parts outside it would have to
 * measure for themselves, from a different box, and could disagree. One measurement, one local.
 */
@Composable
fun FaceFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalFaceLandscape provides FaceShape.sideBySide(maxWidth.value, maxHeight.value),
        ) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * The two halves of a player - the picture, and everything done to it - arranged for the shape
 * of the screen.
 *
 * ## Why one primitive and not five landscape layouts
 *
 * There are five faces and each was a single vertical `Column`, so sideways each of them broke
 * the same way and would have been fixed the same way five times. Written once, a sixth face
 * gets a working landscape layout by construction rather than by somebody remembering. The
 * decision itself is not here - [FaceShape] owns it and is tested - so the five faces cannot
 * disagree about when to switch.
 *
 * ## What each face still decides
 *
 * Everything that makes it that face. This only says where the two groups GO: the artwork block
 * is whatever that face draws for a picture, halo and pulse and all, and the controls block is
 * its own title, scrubber, transport and chips in its own order. Nothing here draws anything.
 *
 * In two columns the artwork is sized by HEIGHT rather than width - `fillMaxHeight` with a
 * square ratio - because in landscape height is the scarce axis, and sizing a square by the
 * wide axis is what made the picture overflow the screen it was meant to fit inside.
 *
 * Reads the shape rather than measuring it. [FaceFrame] measures once, at the top of the face,
 * so the bar and the queue handle outside this layout get the same answer - measuring again
 * here would give a second answer from a smaller box and the parts would disagree.
 */
@Composable
fun FaceLayout(
    modifier: Modifier = Modifier,
    /** Padding inside the controls half, so a face keeps its own margins. */
    controlsPadding: Dp = 0.dp,
    art: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalFaceLandscape.current) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            // WEIGHTED, and that is the load-bearing part.
            //
            // Compose measures the unweighted children first, so the controls below claim the
            // height they need and the artwork is handed whatever is left. Without this the
            // picture sizes itself against the full screen and the controls are pushed off the
            // bottom - which is precisely the fault this layout was written to fix, reproduced
            // in the other orientation.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
            controls()
        }
        return
    }
    // Clear of the navigation bar, which sideways is a strip along the bottom (gestures) or the
    // side (buttons). Without it the picture was centred in a box that ran under the gesture
    // bar, so it sat low - touching the bottom edge with the top bar's height free above it.
    Row(
        modifier.windowInsetsPadding(WindowInsets.navigationBars),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(FaceShape.ART_SHARE).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) { art() }
        Box(
            Modifier
                .weight(1f - FaceShape.ART_SHARE)
                .fillMaxHeight()
                .padding(horizontal = controlsPadding),
            contentAlignment = Alignment.Center,
        ) {
            // Capped, so on a tablet the scrubber is a length a thumb can travel rather than the
            // width of half the screen.
            Column(
                Modifier.widthIn(max = FaceShape.CONTROLS_MAX_DP.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = controls,
            )
        }
    }
}

/**
 * The artwork's own modifier: the largest square that fits, whichever way up the screen is.
 *
 * ## Why it names no axis
 *
 * The first cut of this said `fillMaxWidth().aspectRatio(1f)` stacked and `fillMaxHeight(...)`
 * side by side. The stacked half is the shape the faces already had and it is WRONG, which is
 * how a round of "landscape support" broke portrait: `fillMaxWidth` pins the width to the
 * maximum, so the square's height becomes the full width of the screen, and on a tall phone
 * that pushed the title, the scrubber, the transport and the chips off the bottom. The
 * original faces got away with it only because each one also carried a `weight(1f, fill =
 * false)` in the same modifier chain, which is the part that bounded it - and that is exactly
 * what was dropped when the chains were replaced.
 *
 * `aspectRatio` on its own is the whole answer. Given a container that bounds both axes it
 * tries the full width first and falls back to the full height when that would not fit, which
 * is "the largest square that fits" in one call and in either orientation. Nothing here has to
 * know which way up the screen is; [FaceLayout] bounds the container and this fills it.
 */
@Composable
fun artSizing(inset: Dp = 0.dp): Modifier = Modifier.padding(inset).aspectRatio(1f)

/**
 * Whether the face being drawn is in its two-column arrangement.
 *
 * A composition local rather than a parameter threaded through every part, because the parts
 * that need to know are several layers down - the artwork, and whichever of the chips a face
 * drops for room - and most of the layers in between have no business carrying it.
 */
val LocalFaceLandscape = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun PlayerArt(
    art: android.graphics.Bitmap?,
    isVideo: Boolean,
    playing: Boolean,
    source: android.net.Uri?,
    plain: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    badge: Boolean = true,
) {
    val colors = Filet.colors
    val lift by animateFloatAsState(
        if (playing || plain) 1f else 0.885f,
        animationSpec = tween(380),
        label = "art lift",
    )
    val still: @Composable () -> Unit = {
        val picture = art
        if (picture != null) {
            Image(
                picture.asImageBitmap(), "Cover art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // No embedded art is normal, not a failure, so the placeholder is a piece of the
            // design rather than an error state.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (isVideo) FiletIcons.Video else FiletIcons.Audio, null,
                    tint = colors.accent.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxSize(0.32f),
                )
            }
        }
    }
    Box(
        modifier
            .then(
                if (plain) Modifier
                else Modifier
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .shadow(
                        elevation = (34 * lift * lift).dp,
                        shape = RoundedCornerShape(radius),
                        clip = false,
                    ),
            )
            .clip(RoundedCornerShape(if (plain) 14.dp else radius))
            .background(colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            dev.niccc2007.filet.playback.VideoPeekView(
                source = source,
                playing = playing,
                modifier = Modifier.fillMaxSize(),
                placeholder = still,
            )
        } else {
            still()
        }
    }
}

/**
 * The VIDEO pill on the artwork.
 *
 * Said on the picture rather than in a sentence under it, because the picture is the thing
 * that looks wrong: a music player holding a video is confusing until something names it.
 */
@Composable
fun BoxScope.VideoBadge(onWatch: () -> Unit, inset: Dp = 10.dp) {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(inset)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onWatch)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Video, null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            "WATCH",
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── the title block ───────────────────────────────────────────────────────────

/**
 * The title, what it came from, and the heart.
 *
 * Left-aligned on the ambient faces and centred on Classic. A centred title under a centred
 * square is a poster; a left-aligned one beside an action is a player, and the mock chose the
 * second because the row then has somewhere for the heart to live that is not a floating
 * button.
 *
 * `.meta{padding:24px 28px 0}`, `.ttl{font-size:22px;font-weight:750;letter-spacing:-.5px}`.
 *
 * ## There is no Watch here any more
 *
 * There used to be, and it meant Ambient, Editorial and Aurora each carried TWO - a badge on
 * the artwork and a chip beside the artist - which is what got reported. The badge is the one
 * that stays: it is on the thing it acts on, so pressing the picture to get the picture moving
 * is the obvious gesture, and it also marks the artwork as a still frame rather than a cover.
 * [WatchChip] exists for Immersive alone, where the artwork is the whole screen and has no
 * corner to put a badge in, and Classic keeps its own "playing as audio - Watch" line because
 * that face explains itself in words. One per face, and this block does one job.
 */
@Composable
fun PlayerTitleBlock(
    title: String,
    sub: String,
    isVideo: Boolean,
    centred: Boolean,
    titleSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    bookmarked: Boolean,
    tint: Color,
    onHeart: () -> Unit,
    /** Whether a title too long to fit may scroll. False holds it at its start. */
    scrolling: Boolean = true,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A centred title with a 38dp button only on its right is not centred: the text sits
        // half a button to the left of the middle, which is just enough to read as a mistake
        // rather than as a choice. An empty one of the same size on the other side fixes it
        // with no control nobody asked for.
        if (centred) Spacer(Modifier.size(38.dp))
        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            MarqueeTitle(title, centred = centred, size = titleSize, running = scrolling)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isVideo) {
                    Icon(
                        FiletIcons.Video, null,
                        tint = colors.fg3, modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    sub,
                    fontSize = 13.sp,
                    color = colors.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        // Bookmarks, not a second list of favourites. Filet already has one place that means
        // "keep this where I can find it", and a heart that fed a different one would be two
        // answers to the same question - with the second invisible everywhere else in the app.
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onHeart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (bookmarked) FiletIcons.HeartFilled else FiletIcons.Heart,
                if (bookmarked) "In Favourites \u2014 tap to remove" else "Add to Favourites",
                tint = if (bookmarked) tint else colors.fg3,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/**
 * A title that scrolls only when it does not fit.
 *
 * A filename is not a song title: it is long, it has no spaces and it cannot wrap, so ellipsis
 * always lost the end of it - which on `VID_20261009_120104` is the only part that differs
 * from its neighbours. Measured off the laid-out text rather than estimated from a character
 * count, because the answer depends on the font and on how wide the phone is.
 */
@Composable
fun MarqueeTitle(
    text: String,
    centred: Boolean,
    size: androidx.compose.ui.unit.TextUnit,
    colour: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * Whether it may move at all. The callers pass "playing, and on screen": a marquee is a
     * frame-rate clock with an offscreen layer under it, and it ran on every screen the strip
     * is on - including underneath the full player and with the track paused.
     */
    running: Boolean = true,
) {
    var overflow by remember(text) { mutableFloatStateOf(0f) }
    val travel = rememberMarquee(overflow, running)
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .marqueeFade(overflow > 2f),
        contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = colour,
            // Read INSIDE the graphicsLayer block, so a moving title invalidates the draw
            // phase rather than recomposing this text on every frame. Same fault as the glow
            // had, and it has been here longer - a title that does not fit is the common
            // case, not the rare one. See `rememberMarquee`.
            modifier = Modifier.graphicsLayer { translationX = travel.value },
            onTextLayout = { layout ->
                overflow = maxOf(
                    0f,
                    layout.multiParagraph.maxIntrinsicWidth - layout.size.width,
                )
            },
        )
    }
}

// ── scrub and transport ───────────────────────────────────────────────────────

/** The bar and its two clocks. `.bar{margin:22px 28px 0}` + `.times{margin:6px 28px 0}`. */
@Composable
fun PlayerScrub(
    /**
     * Where the playhead is drawn, read HERE. A function rather than a value, so the five
     * ticks a second recompose this bar and its two clocks and not the face around them.
     */
    shownPosition: () -> Long,
    state: NowPlaying,
    tint: Color,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    val colors = Filet.colors
    val shownPosition = shownPosition()
    ScrubBar(
        positionMs = shownPosition,
        durationMs = state.durationMs,
        enabled = state.durationMs > 0,
        fill = tint,
        onScrub = onScrub,
        onScrubEnd = onScrubEnd,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            clockOf(shownPosition),
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
        )
        Spacer(Modifier.weight(1f))
        Text(
            remainingOf(shownPosition, state.durationMs),
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
        )
    }
}

/** Shuffle, previous, play, next, repeat. `.tb.sm 40 / .tb.mid 48 / .tb.big 68`. */
@Composable
fun PlayerTransport(
    state: NowPlaying,
    tint: Color,
    onTint: Color,
    scale: Float = 1f,
    playback: dev.niccc2007.filet.playback.Playback,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            FiletIcons.Shuffle, "Shuffle", size = (40 * scale).dp,
            active = state.shuffle, enabled = state.size > 2,
            fill = tint, onFill = onTint,
        ) { playback.setShuffle(!state.shuffle) }

        // Restart-then-jump is the player's rule, not the screen's. It used to be a
        // `position > 3_000L` branch written here, which meant the notification's Previous
        // button and this one behaved differently.
        TransportButton(FiletIcons.Prev, "Previous", size = (48 * scale).dp) { playback.previous() }

        TransportButton(
            if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
            if (state.playing) "Pause" else "Play",
            filled = true,
            size = (68 * scale).dp,
            enabled = state.current != null,
            // Borrowed from the artwork with a contrast floor forced onto it, so a dark track
            // cannot produce an invisible button. See ArtPalette.
            fill = tint,
            onFill = onTint,
        ) { playback.toggle() }

        TransportButton(FiletIcons.Next, "Next", size = (48 * scale).dp) { playback.next() }

        TransportButton(
            // The glyph carries the state. See FiletIcons.RepeatOne.
            if (state.repeat == Repeat.ONE) FiletIcons.RepeatOne else FiletIcons.Repeat,
            when (state.repeat) {
                Repeat.OFF -> "Repeat off"
                Repeat.ALL -> "Repeating the queue"
                Repeat.ONE -> "Repeating this track"
            },
            size = (40 * scale).dp,
            active = state.repeat != Repeat.OFF,
            fill = tint, onFill = onTint,
        ) {
            // The cycle lives on the player, because the notification's repeat button runs
            // the same one. Two copies of off-all-one-off is two places for the order to drift.
            playback.cycleRepeat()
        }
    }
}

// ── the chip row ──────────────────────────────────────────────────────────────

/**
 * The sound sheet and the sleep timer.
 *
 * The genuinely secondary switches, and the row exists so they are not in the transport. Both
 * are real: the speed is `MediaPlayer.playbackParams` on the shared player, the volume and the
 * equaliser are `Sound`, and the timer pauses it. A chip that only looks like a control is
 * worse than no chip.
 *
 * `.chip{font-size:10.5px;padding:6px 11px;border-radius:50px}`.
 */
@Composable
fun PlayerChips(
    speed: Float,
    maxSpeed: Float,
    sleepMinutesLeft: Int,
    pitch: dev.niccc2007.filet.playback.PitchMode,
    tint: Color,
    onTint: Color,
    playback: dev.niccc2007.filet.playback.Playback,
    /**
     * Whether the row centres itself.
     *
     * Aurora centres its title, its scrub bar and its transport, and this row was the one
     * thing on that face still ranged left - two small chips hugging the left gutter under a
     * centred stack, which is what "off placed" meant and it was right. The left-aligned
     * faces pass false and are unchanged.
     */
    centred: Boolean = false,
    onPitch: (dev.niccc2007.filet.playback.PitchMode) -> Unit,
) {
    var speedOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sound by remember {
        dev.niccc2007.filet.playback.SoundState.of(context).sound(dev.niccc2007.filet.playback.SoundTarget.MUSIC)
    }.collectAsStateWithLifecycle()
    Row(
        modifier = if (centred) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (centred) {
            Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.spacedBy(7.dp)
        },
    ) {
        PlayerChip(
            SoundSheetGlyph,
            soundChipLabel(speed, sound),
            lit = soundChipLit(speed, sound),
            tint = tint,
        ) {
            // A sheet with sliders, not a cycle.
            //
            // The first cut cycled through five speed presets on tap, on the reasoning that
            // the values anybody uses fit in one button. Tapping a chip four times to go from
            // 1.0 to 0.75 is a control that makes you count, so a speed is SET on a rail -
            // and the volume and the equaliser joined it there, as the rest of "how it sounds".
            speedOpen = true
        }
        PlayerChip(
            FiletIcons.Timer,
            if (sleepMinutesLeft > 0) "${sleepMinutesLeft}m" else "Sleep",
            lit = sleepMinutesLeft > 0,
            tint = tint,
        ) {
            // 15, 30, 60, off. Same reasoning as the speed chip, and the same reason there is
            // no "end of track": a queue of two-second clips makes that meaningless.
            val next = when (sleepMinutesLeft) {
                0 -> 15
                in 1..15 -> 30
                in 16..30 -> 60
                else -> 0
            }
            playback.setSleepTimer(next)
        }
    }
    if (speedOpen) {
        // The pitch switch lives inside the sheet rather than beside the speed chip: it is a
        // question ABOUT the speed and is meaningless without one, since at 1.0x all three
        // modes leave the audio untouched. Where the speed is set is also where somebody
        // notices the warble and goes looking for the reason.
        SoundSheet(
            speed = speed,
            maxSpeed = maxSpeed,
            pitch = pitch,
            tint = tint,
            onTint = onTint,
            onSpeed = { playback.setSpeed(it) },
            onPitch = onPitch,
            onDismiss = { speedOpen = false },
            target = dev.niccc2007.filet.playback.SoundTarget.MUSIC,
            equaliser = true,
        )
    }
}



@Composable
private fun PlayerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    lit: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (lit) tint.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (lit) tint else colors.fg3,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.5.sp, color = if (lit) tint else colors.fg2)
    }
}

// ── the queue ─────────────────────────────────────────────────────────────────

/** The strip that pulls the queue up. `.qhandle{padding:13px 22px 16px}`. */
@Composable
fun QueueHandle(state: NowPlaying, onClick: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Rows, null, tint = colors.fg2, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text("Up next", fontSize = 12.5.sp, color = colors.fg2)
        Spacer(Modifier.weight(1f))
        Text(
            "${state.position} of ${state.size}",
            fontSize = 11.sp, color = colors.fg3,
        )
    }
}

/**
 * The queue, as a sheet that comes up over the player rather than a panel that pushes it.
 *
 * It used to be an `AnimatedVisibility` Column at the bottom of the layout, which shoved the
 * artwork and the transport up by 190dp every time it opened - so opening the queue moved the
 * Play button out from under the thumb that was about to press it. A sheet covers; it does not
 * rearrange.
 *
 * `.qsheet{top:30%;border-radius:20px 20px 0 0}`.
 */
@Composable
fun BoxScope.QueueSheet(
    tracks: List<Track>,
    index: Int,
    tint: Color,
    visible: Boolean,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    /** What the queue is, in words - a playlist's name or the folder. See `NowPlaying.from`. */
    from: String = "",
    /**
     * Milliseconds into the current track, for the time left in the header. A function, read
     * where the header is built, so a closed sheet costs nothing per tick.
     */
    positionMs: () -> Long = { 0L },
    durationMs: Long = 0L,
    shuffle: Boolean = false,
    onShuffle: (() -> Unit)? = null,
    repeat: Repeat = Repeat.OFF,
    onRepeat: (() -> Unit)? = null,
    /** Take one row out. Null where the queue cannot be edited. */
    onDrop: ((Int) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    // The scrim first, so a tap outside closes it. Without indication: it is a dismiss target,
    // not a button.
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Swallows the scrim's tap. `clickable(enabled = false)` still consumes.
                .clickable(enabled = false) {},
        ) {
            // `.grab{width:36px;height:4px;margin:9px auto 4px}`
            Box(
                Modifier
                    .padding(top = 9.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.high),
            )
            // ── the header says what the queue IS ─────────────────────────────────
            //
            // It said "N in this folder", which is wrong whenever the queue came from a
            // playlist - and it is the queue's own `from` line that knows. The rest is the
            // two facts somebody opens this to find out: how much is left, and how it is
            // going to be played.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Up next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        queueShape(tracks, index, positionMs(), durationMs, from),
                        fontSize = 11.5.sp, color = colors.fg3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Close",
                    fontSize = 11.5.sp, color = colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            // Shuffle, repeat and Clear. They are also in the transport, deliberately: the
            // transport is the row that gets pressed without looking, and taking two controls
            // out of it to make a point about which object they belong to would be a worse
            // trade than one duplicate. They are facts about the QUEUE, so they are offered
            // where the queue is, as well as where the hand already is.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onShuffle != null) {
                    QueueChip(
                        FiletIcons.Shuffle,
                        if (shuffle) "Shuffling" else "Shuffle",
                        lit = shuffle,
                        tint = tint,
                        onClick = onShuffle,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                if (onRepeat != null) {
                    QueueChip(
                        if (repeat == Repeat.ONE) FiletIcons.RepeatOne else FiletIcons.Repeat,
                        when (repeat) {
                            Repeat.OFF -> "Repeat"
                            Repeat.ALL -> "Repeating"
                            Repeat.ONE -> "This track"
                        },
                        lit = repeat != Repeat.OFF,
                        tint = tint,
                        onClick = onRepeat,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onClear != null) {
                    Text(
                        "Clear",
                        fontSize = 11.5.sp,
                        color = colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
            QueueRows(tracks, index, tint, onPick, onDrop)
        }
    }
}

@Composable
private fun QueueRows(
    tracks: List<Track>,
    index: Int,
    tint: Color,
    onPick: (Int) -> Unit,
    onDrop: ((Int) -> Unit)?,
) {
    val colors = Filet.colors
    val listState = rememberLazyListState()
    LaunchedEffect(index) {
        // Keep the playing row on screen as the queue advances, without yanking the list when
        // it has been scrolled somewhere else on purpose.
        val seen = listState.layoutInfo.visibleItemsInfo
        if (seen.isNotEmpty() && index !in seen.first().index..seen.last().index) {
            runCatching { listState.animateScrollToItem(index) }
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val here = i == index
            // ── swipe a row away to take it out of the queue ──────────────────────
            //
            // Sideways, and only sideways: this list scrolls vertically, so a drag has to be
            // claimed on the axis the list does not want. `awaitEachGesture` with a drag
            // claimed after the slop is crossed is the shape that cooperates with a
            // LazyColumn - `detectHorizontalDragGestures` takes the touch before the list has
            // had a chance to decide and makes the sheet feel like it is fighting the thumb.
            //
            // Taking the row out is the player's call rather than the list's, because
            // removing the row that is PLAYING has to load the next track. See
            // `Playback.dropFromQueue`.
            var slide by remember(t.id) { mutableFloatStateOf(0f) }
            val away = with(LocalDensity.current) { DROP_AWAY.toPx() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = slide
                        // Fading with the distance, so the row looks like it is leaving
                        // rather than like it has been nudged.
                        alpha = (1f - kotlin.math.abs(slide) / (away * 1.6f)).coerceIn(0.25f, 1f)
                    }
                    .background(if (here) tint.copy(alpha = 0.14f) else Color.Transparent)
                    .then(
                        if (onDrop == null || tracks.size <= 1) {
                            Modifier
                        } else {
                            // READ LIVE. `pointerInput` launches its block once per key and
                            // keeps whatever it closed over, so a lambda captured here would
                            // be the one from the composition that started the gesture - and
                            // this list is rebuilt every time the queue changes underneath it.
                            // Caught by check-deadswitch, which exists for exactly this.
                            val drop = rememberUpdatedState(onDrop)
                            val at = rememberUpdatedState(i)
                            Modifier.pointerInput(t.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var total = 0f
                                    var claimed = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.changedToUp()) break
                                        val dx = change.positionChange().x
                                        val dy = change.positionChange().y
                                        total += dx
                                        if (!claimed) {
                                            // Horizontal INTENT, not just horizontal motion:
                                            // a thumb flicking the list up moves sideways by
                                            // a few pixels on the way and must not start a
                                            // removal.
                                            if (kotlin.math.abs(total) > DROP_SLOP_PX &&
                                                kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                            ) {
                                                claimed = true
                                            } else if (kotlin.math.abs(dy) > DROP_SLOP_PX) {
                                                break
                                            }
                                        }
                                        if (claimed) {
                                            slide = total
                                            change.consume()
                                        }
                                    }
                                    if (claimed && kotlin.math.abs(slide) > away) {
                                        drop.value(at.value)
                                    }
                                    slide = 0f
                                }
                            }
                        },
                    )
                    .clickable { onPick(i) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    if (here) {
                        Icon(
                            FiletIcons.PlaySolid, null,
                            tint = tint, modifier = Modifier.size(12.dp),
                        )
                    } else {
                        Text(
                            "${i + 1}",
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                            color = colors.fg3,
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    dev.niccc2007.filet.browser.ListNameText(
                        t.title,
                        if (here) tint else MaterialTheme.colorScheme.onSurface,
                        startSp = 12.5f,
                        fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    // Which entry is the odd one out is the useful fact in a FOLDER queue, and
                    // a row of identical rows does not carry it.
                    if (t.medium == Medium.VIDEO) {
                        Text(
                            "video, playing as audio",
                            fontSize = 10.sp, color = colors.fg3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the queue is, in one line: where it came from, how many are left, and how long.
 *
 * ## Why the time is "left" and not the total
 *
 * The total is a fact about a folder; the time remaining is a fact about the evening. It is
 * also the only one of the two that changes while you look at it, which is what makes it worth
 * the width. The current track's own remainder is counted, not its whole length - a queue with
 * four minutes left should not claim seven because the first track is half played.
 *
 * Pure, so the arithmetic can be tested without a sheet to look at. A duration of zero means
 * the track has not been opened yet, and those are simply not counted rather than guessed at -
 * naming a figure built out of unknowns is worse than naming none.
 */
fun queueShape(
    tracks: List<Track>,
    index: Int,
    positionMs: Long,
    durationMs: Long,
    from: String,
): String {
    val ahead = (tracks.size - index - 1).coerceAtLeast(0)
    val parts = ArrayList<String>(3)
    if (from.isNotBlank()) parts += from
    parts += when (ahead) {
        0 -> "last one"
        1 -> "1 more"
        else -> "$ahead more"
    }
    // Only the current track's remainder is knowable from here: the queue holds paths, and
    // the lengths of tracks nobody has opened are not read until they play. So the figure is
    // honest about being about this track rather than pretending to total the queue.
    if (durationMs > 0L) {
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        parts += clockOf(left) + " left"
    }
    return parts.joinToString("  ·  ")
}

/** How far a row has to be dragged sideways before it is gone. */
private val DROP_AWAY = 96.dp

/** And how far it has to move before the drag is a removal rather than a scroll. */
private const val DROP_SLOP_PX = 14f

/** One of the queue header's little controls. */
@Composable
private fun QueueChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    lit: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (lit) tint.copy(alpha = 0.16f) else colors.sunken)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (lit) tint else colors.fg2,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = if (lit) tint else colors.fg2)
    }
}

// ── the immersive face's retractable controls ────────────────────────────────

/**
 * "Watch", for a face whose artwork has no corner to put a badge in.
 *
 * Same words and same job as [VideoBadge], drawn as a standalone chip: the immersive face's
 * picture fills the screen, so a badge on it would be a label floating in the middle of a
 * photograph rather than attached to a card.
 */
@Composable
fun WatchChip(onWatch: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onWatch)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Video, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Watch",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * The floating pill: title, transport, repeat, queue.
 *
 * A glass panel across the bottom is a second screen stuck to the first - it covers a third of
 * the picture permanently, which defeats the only thing the immersive face is for. These are
 * small, and tapping the picture retracts them.
 *
 * They FADE rather than unmount, so a tap always lands on something: a control that is gone
 * cannot be brought back by pressing where it was.
 *
 * `.minibar{padding:8px 10px;border-radius:50px}`, `.mb{34px}`, `.mb.pp{42px}`.
 */
@Composable
fun MiniControls(
    state: NowPlaying,
    title: String,
    sub: String,
    /** Read here, not by the caller. See [PlayerScrub]. */
    shownPosition: () -> Long,
    tint: Color,
    onTint: Color,
    playback: dev.niccc2007.filet.playback.Playback,
    onQueue: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    val shownPosition = shownPosition()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                clockOf(shownPosition),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.78f),
            )
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                ScrubBar(
                    positionMs = shownPosition,
                    durationMs = state.durationMs,
                    enabled = state.durationMs > 0,
                    fill = tint,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                )
            }
            Text(
                remainingOf(shownPosition, state.durationMs),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                // Its own ground, because it floats over a photograph: white text on an
                // unknown picture is unreadable about half the time.
                .background(Color.Black.copy(alpha = 0.46f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                dev.niccc2007.filet.browser.ListNameText(title, Color.White, startSp = 12.5f, fontWeight = FontWeight.SemiBold)
                if (sub.isNotEmpty()) {
                    dev.niccc2007.filet.browser.ListNameText(sub, Color.White.copy(alpha = 0.62f), startSp = 10f)
                }
            }
            MiniButton(FiletIcons.Prev, "Previous") { playback.previous() }
            MiniButton(
                if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                if (state.playing) "Pause" else "Play",
                size = 42.dp, ground = tint, ink = onTint,
            ) { playback.toggle() }
            MiniButton(FiletIcons.Next, "Next") { playback.next() }
            MiniButton(
                if (state.repeat == Repeat.ONE) FiletIcons.RepeatOne else FiletIcons.Repeat,
                when (state.repeat) {
                    Repeat.OFF -> "Repeat off"
                    Repeat.ALL -> "Repeating the queue"
                    Repeat.ONE -> "Repeating this track"
                },
                ink = if (state.repeat != Repeat.OFF) tint else Color.White,
            ) { playback.cycleRepeat() }
            MiniButton(FiletIcons.Rows, "Up next", onClick = onQueue)
        }
    }
}

@Composable
private fun MiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Dp = 34.dp,
    ground: Color = Color.Transparent,
    ink: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(ground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The immersive background: a blurred copy filling the screen, the sharp one contained on it.
 *
 * Filling a tall screen with one image crops it to a band, worst of all for a 9:16 video
 * frame. Two layers means nothing is stretched and nothing is lost - the blur is wallpaper and
 * the sharp copy is the picture.
 */
@Composable
fun ImmersiveBackdrop(
    art: android.graphics.Bitmap?,
    isVideo: Boolean,
    playing: Boolean,
    source: android.net.Uri?,
    modifier: Modifier = Modifier,
    /** The beat, SIGNED, or null. See [IMMERSIVE_BREATH]. */
    pulse: androidx.compose.runtime.State<Float>? = null,
    /** How far a beat may move it. See `Prefs.pulseDepth`. */
    pulseDepth: Float = 1f,
) {
    val picture = art ?: return
    val bitmap = picture.asImageBitmap()
    Box(
        modifier
            .fillMaxSize()
            // The picture breathes with the beat. On this face the artwork IS the screen, so
            // there is no frame around it to brighten and nothing else large enough to move -
            // and a backdrop that answers the music while the controls stay still is the whole
            // reason this face exists.
            //
            // `graphicsLayer` with a lambda, not a value: the lambda runs in the draw phase, so
            // a beat re-rasterises the layer and recomposes nothing. Passing a scale as a
            // parameter would put a full-screen image's composition on the sixty-hertz clock.
            .graphicsLayer {
                val swing = if (pulse == null) 0f else pulse.value * pulseDepth
                val breath = 1f + swing.coerceIn(-1f, 1f) * IMMERSIVE_BREATH
                scaleX = breath
                scaleY = breath
            },
    ) {
        // The wallpaper: a blurred STILL, even for a video.
        //
        // A `VideoView` is a SurfaceView, and a surface is composited by the display rather
        // than drawn into this layer - so `Modifier.blur` has nothing to blur and a second
        // decoder would be running a clip nobody can see through 44dp of blur anyway. The
        // frame is the right colour, which is all this layer is for.
        Image(
            bitmap, null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                .androidBlur(44.dp)
                .alpha(0.55f),
        )
        // The picture itself, contained so nothing is cropped - and MOVING when it is a
        // video. The other three faces loop the clip in their artwork square; this one drew
        // a frozen frame, which on the face whose whole point is the picture is the one place
        // it is most obviously wrong.
        Box(
            Modifier.fillMaxSize().padding(top = 56.dp, bottom = 128.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                dev.niccc2007.filet.playback.VideoPeekView(
                    source = source,
                    playing = playing,
                    modifier = Modifier.fillMaxSize(),
                    fit = true,
                    placeholder = {
                        Image(
                            bitmap, "Cover art",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                Image(
                    bitmap, "Cover art",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * `Modifier.blur`, which is a no-op below API 31 rather than a crash.
 *
 * Filet's minimum is below that, and a backdrop that throws on an older phone would take the
 * whole player down. The unblurred fill is still a reasonable background - it is cropped and
 * dimmed - so the degradation is visual and not functional.
 */
private fun Modifier.androidBlur(radius: Dp): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        this.then(Modifier.blur(radius))
    } else {
        this
    }

/**
 * The gradient the immersive face's chrome sits on.
 *
 * Top and bottom only: a veil across the whole frame would dim the picture, which is the one
 * thing this face is for. The two bands exist so white text has ground under it wherever the
 * picture happens to be pale.
 */
/**
 * How much the full-bleed artwork grows on a full-strength beat.
 *
 * Three quarters of one percent, which on a 1080-wide screen is eight pixels. Deliberately
 * below what can be measured by eye: a picture that visibly resizes on every drum reads as a
 * broken layout, where one that moves by a hair reads as the room breathing. The eye catches
 * motion far below the threshold at which it can name the amount.
 */
private const val IMMERSIVE_BREATH = 0.0075f

@Composable
fun ImmersiveVeil(modifier: Modifier = Modifier) {
    Box(
        // Built once per size. This sits in the same layer as the beat-driven backdrop, so
        // every redraw of that re-ran this lambda - and it built a new gradient each time.
        modifier.fillMaxSize().drawWithCache {
            val bands = androidx.compose.ui.graphics.Brush.verticalGradient(
                0.00f to Color.Black.copy(alpha = 0.55f),
                0.22f to Color.Transparent,
                0.62f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = 0.72f),
            )
            onDrawWithContent {
                drawContent()
                drawRect(bands)
            }
        },
    )
}

/** How many whole minutes the sleep timer has left, or 0. Pure, so the chip can be tested. */
fun sleepMinutesLeft(deadline: Long, now: Long): Int =
    if (deadline <= 0L || deadline <= now) 0
    else (((deadline - now) + 59_999L) / 60_000L).toInt()

/** The row heights the faces share, so a change lands in all four. */
object PlayerMetrics {
    /** `.artwrap{padding:14px 28px 0}` and `.meta{padding:24px 28px 0}`. */
    val GUTTER = 28.dp
    val CHIPS_TOP = 16.dp
    val TRANSPORT_TOP = 18.dp
    val SCRUB_TOP = 22.dp
}

/**
 * Name the queue you are keeping.
 *
 * Its own small dialogue rather than the playlist picker, because the question is different:
 * the picker asks which existing list a track joins, and this one makes a list out of what is
 * already arranged. Offering the picker here would mean choosing "New playlist" every time,
 * which is a step that only ever has one answer.
 */
@Composable
fun SaveQueueDialog(
    suggested: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(suggested) }
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(
            icon = FiletIcons.Rows,
            title = "Save the queue",
            sub = "Everything playing, in this order",
            onClose = onDismiss,
        )
        DlgBody {
            DlgField(
                label = "Name",
                value = text,
                onValue = { text = it },
            )
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgSpacer()
            DlgBtn(
                "Save",
                kind = BtnKind.PRIMARY,
            ) { onSave(text) }
        }
    }
}
