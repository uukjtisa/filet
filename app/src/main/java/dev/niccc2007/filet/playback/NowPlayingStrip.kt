package dev.niccc2007.filet.playback

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.PanelPopover
import dev.niccc2007.filet.ui.dialogs.PanelRow
import dev.niccc2007.filet.ui.dialogs.PanelRule
import dev.niccc2007.filet.ui.dialogs.PanelSide
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The minimised player: one row, above the bottom bar, everywhere in the app.
 *
 * Reported alongside the background-playback fault and it is the same feature: music that keeps
 * playing with the player closed needs somewhere to be controlled from, or the only way to
 * pause is to find the file again.
 *
 * ## Three decisions worth keeping
 *
 * **It is drawn from `active`, not from `playing`.** A paused track is still the thing being
 * listened to, and a strip that vanishes on pause takes its own Play button away - which is
 * the same mistake as a service that stops on pause, one layer up.
 *
 * **The progress line is a Canvas, not a `LinearProgressIndicator`.** It is a hairline along
 * the top edge rather than a component with its own padding and track colour, and drawing two
 * rectangles is less code than talking a Material component out of looking like one.
 *
 * **Previous appears only when there is room for it.** Four controls and a title in a 56dp row
 * do not fit a 360dp phone; they fit a 423dp one comfortably. The row measures itself rather
 * than picking one answer for both - and when it is dropped, Previous is still a tap away on
 * the full player, the notification and an earphone remote.
 *
 * ## What the round of use changed
 *
 * It was a grey row with a 40dp thumbnail and three flat glyphs, and the ask was to make the
 * minimised controls better. Three things:
 *
 *  1. **It takes the artwork's colour**, like the full player does - a wash behind the row, a
 *     tinted progress line and a filled play button in the same colour. The strip and the
 *     player are now recognisably the same object at two sizes, which is the whole point of a
 *     minimised anything.
 *  2. **The artwork is bigger and it reacts.** 44dp, and it shrinks a little when paused -
 *     the same tell the full player gives, so pause is visible without reading the glyph.
 *  3. **The title scrolls when it does not fit**, for the same reason it does on the player: a
 *     filename is the one string where the END is the part that differs from its neighbours.
 */
/** Whether the strip is up right now, by the same rule it draws by: see [stripShown]. */
@Composable
fun nowPlayingShown(vm: BrowserViewModel): Boolean {
    val state by rememberStatus(vm.playback)
    return stripShown(state)
}

/**
 * Whether [NowPlayingStrip] draws: a track is the one being listened to, paused or not.
 *
 * One rule for the strip and for [nowPlayingShown], which the browser reads to colour the strip
 * it lays under the navigation bar after whatever bar is last. Two copies of the rule would let
 * that colour disagree with the bar actually above it.
 */
private fun stripShown(state: NowPlaying): Boolean = state.active && state.current != null

@Composable
fun NowPlayingStrip(vm: BrowserViewModel) {
    val colors = Filet.colors
    val playback = vm.playback
    // Without the position: see `Playback.status`. Reading the whole state here recomposed
    // this strip - on every screen of the app - five times a second for as long as anything
    // played. The two things that draw the playhead read `position` inside their draw.
    val state by rememberStatus(playback)
    val art by playback.art.collectAsStateWithLifecycle()

    if (!stripShown(state)) return
    val track = state.current ?: return
    val position = rememberPosition(playback)
    val fraction = remember(position, state.durationMs) {
        { playheadFraction(position.value, state.durationMs) }
    }

    /**
     * Whether anything here may move.
     *
     * A viewer is a layer over the browser rather than a destination, so this strip stays
     * composed underneath the full player, the video screen and every other viewer. Its clocks
     * kept running there, behind an opaque screen - and each of their redraws was a redraw of
     * the window. Covered, or in the background, or paused, the strip shows its resting picture
     * and runs nothing. See [shouldAnimate].
     */
    val covered = vm.openRequest.collectAsStateWithLifecycle().value != null
    val onScreen = rememberOnScreen()
    val moving = state.playing && onScreen && !covered

    // The same sampler the full player uses, so the two never disagree about what colour this
    // track is. Falls back to the theme accent when the artwork has none to give.
    val (tint, onTint) = rememberArtPalette(
        art = art,
        fallback = colors.accent,
        onFallback = MaterialTheme.colorScheme.onPrimary,
    )

    // No switch over it any more. The strip's coloured band IS the strip - with it gone the
    // row was a grey panel - so the question is how much, which `glowStrength` answers.
    val glow = true
    val strength by vm.prefs.glowStrength.collectAsStateWithLifecycle()
    val spill by vm.prefs.glowSpill.collectAsStateWithLifecycle()

    /**
     * The beat, but only if something else has already paid for it.
     *
     * `BeatMap.cached` never measures - see the note there. The strip is in every folder, and
     * a sixty-times-a-second envelope running while somebody browses files is a cost worth
     * paying only when the answer is already sitting in memory, which after one play of the
     * track it is.
     */
    val pulseOn by vm.prefs.beatPulse.collectAsStateWithLifecycle()
    //
    // Bug identified here: this asked for `track.id` - the track's VFS path - while the player
    // stores every map under the URI the track resolved to, so the lookup never matched and
    // the strip's pulse never fired on any track. Both sides now build the key with
    // `BeatMap.keyFor` from the same resolved source. Re-asked when a viewer closes, because
    // that is the moment the player has usually just finished measuring.
    val source by playback.source.collectAsStateWithLifecycle()
    val map = remember(source, pulseOn, glow, covered) {
        val s = source
        if (!pulseOn || !glow || s == null) null else BeatMap.cached(BeatMap.keyFor(s.toString()))
    }
    val depth by vm.prefs.pulseDepth.collectAsStateWithLifecycle()
    val sense by vm.prefs.pulseSense.collectAsStateWithLifecycle()
    val signal = rememberBeatSignal(
        map, vm.playback,
        on = pulseOn && glow && map != null && !covered,
        playing = state.playing,
        sensitivity = sense,
    )
    val beating = if (map == null) null else signal
    val hit: () -> Float = { if (beating == null) 0f else beating.swing.value * depth }

    // The strip's ground, from its Background glow panel. See `StripLook`.
    val look = StripLook.of(vm.prefs.stripLook.collectAsStateWithLifecycle().value)
    val solid = look == StripLook.SOLID
    // The mist's own clock, in seconds. It runs only while the mist is animated, the track is
    // playing and the strip is on screen; with animations off at the OS it holds still. Read in
    // the draw, so a frame redraws the band and recomposes nothing.
    val drift = look == StripLook.ANIMATED && !dev.niccc2007.filet.ui.reduceMotion()
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(drift, moving) {
        if (!drift || !moving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                clock.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    // On a solid bar the words and buttons take the colour that reads on the track's colour.
    val ink = if (solid) onTint else colors.fg2

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (solid) {
                    // Solid: the whole bar in the track's colour, nothing grey under it. A beat
                    // bounces it brighter and it eases back with the envelope.
                    Modifier.drawBehind {
                        drawRect(tint.brighter(1f + hit().coerceIn(0f, 1f) * SOLID_BOUNCE))
                    }
                } else {
                    // The inner mist: at rest a light wash; on a beat the base at the thumbnail
                    // goes solid and the mist reaches further across. Drifting when animated.
                    // See `innerMist`.
                    Modifier
                        .background(colors.raised)
                        .innerMist(
                            tint,
                            level = glowScale(strength),
                            clock = if (look == StripLook.ANIMATED) ({ clock.floatValue }) else null,
                            swing = hit,
                        )
                },
            )
            // And the same drifting lights the full player has, over that wash, so the strip
            // is the player it minimises into rather than a different widget. Weaker here:
            // this is a 56dp band under a file list, and the lights are nearly the whole
            // surface of it. See `LivingGlow`.
            // ── the light comes off the progress bar ──
            //
            // This used `livingGlow`, the same three drifting lights the full player has. On a
            // screen-sized face that is weather; on a 56dp band it is three circles of colour
            // wandering behind a row of buttons with no relationship to anything on screen, and
            // unclipped it bled upward in a shape that had nothing to do with the strip.
            //
            // The spill was worth keeping. What was wrong is that the light had no source. See
            // `stripRadiance`: it now radiates upward off the bar, brightest at the playhead,
            // so it travels with the track.
            //
            // The CONTINUOUS strength, too. This read `GlowLevel.nearest(strength).scale`,
            // which quantised the slider to three values - so moving it between Faint and Soft
            // changed the full player and left the strip where it was.
            .stripRadiance(
                base = tint,
                fraction = fraction,
                on = glow,
                running = moving,
                strength = GLOW_OVER_STRIP * glowScale(strength),
                spill = spill,
                pulse = beating?.swing,
                pulseDepth = depth,
            ),
    ) {
        HorizontalDivider(color = colors.lineSoft)

        // The hairline. Full width, above the row, so it reads as the strip's own edge filling
        // up rather than as a widget sitting inside it. The position is read inside the draw,
        // so a tick redraws two device pixels and recomposes nothing.
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(colors.lineSoft, size = size)
            // The hairline brightens on the beat. Read inside the draw lambda, so a beat
            // invalidates two device pixels and recomposes nothing - the whole strip is a
            // BoxWithConstraints subcomposition and reading the beat above here would put all
            // of it on the sixty-hertz clock. See the same note in `livingGlow`.
            //
            // The strip is the surface that is actually on screen while the phone is being
            // used, so it is worth it having any answer to the music at all; and brightness
            // is the only move available to a 2dp line.
            val swing = if (beating == null) 0f else beating.swing.value * depth
            // BRIGHTER, not more opaque. The tint arrives fully opaque from the palette,
            // so raising its alpha is arithmetic with no visible result - the first cut of
            // this did exactly that and the line never moved.
            val lift = (1f + swing.coerceAtLeast(0f) * HAIRLINE_PUNCH).coerceAtMost(2.2f)
            drawRect(
                if (solid) onTint else tint.brighter(lift),
                size = size.copy(width = size.width * fraction()),
            )
        }

        BoxWithConstraints {
            // 400dp is the narrow-phone line: below it the title has under 120dp once the
            // glow switch, four controls and a thumbnail are placed, which is a word and a
            // half. Previous is what gives way, because it is the one control here that the
            // full player and the notification both also carry.
            val roomForPrev = maxWidth >= 400.dp
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    // The whole row opens the player, except the buttons, which stop the press
                    // reaching here by having their own clickable.
                    .clickable { vm.maximisePlayer() }
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StripArt(art, track.medium == Medium.VIDEO, state.playing, tint)

                Spacer(Modifier.size(10.dp))

                Column(Modifier.weight(1f)) {
                    // Scrolled rather than clipped, same measurement and same reason as the
                    // full player's title. 13sp here against the player's 22.
                    dev.niccc2007.filet.handlers.MarqueeTitle(
                        PlaybackNotice.title(state),
                        centred = false,
                        size = 13.sp,
                        colour = if (solid) onTint else MaterialTheme.colorScheme.onSurface,
                        running = moving,
                    )
                    val subtitle = PlaybackNotice.strip(state)
                    if (subtitle.isNotEmpty()) {
                        dev.niccc2007.filet.browser.ListNameText(
                            name = subtitle,
                            color = if (solid) onTint.copy(alpha = 0.75f) else colors.fg3,
                            startSp = 10.5f,
                            isDir = true,
                        )
                    }
                }

                // Here rather than only in Settings, because this is where the glow is
                // noticed and the thing it changes is an inch from the finger. It opens a
                // menu rather than cycling: four states read off one unlabelled button is a
                // guessing game, and the menu stays beside the strip so the strip itself is
                // visible while it is being judged.
                GlowMenu(vm, GlowLevel.nearest(strength), spill, look, pulseOn, if (solid) onTint else tint)

                if (roomForPrev && state.canStep) {
                    StripButton(FiletIcons.Prev, "Previous", ink = ink) { playback.previous() }
                }

                // Filled, in the track's colour. The one control in this row that is pressed
                // without looking, and the only one worth giving a ground to.
                StripButton(
                    if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                    PlaybackNotice.toggleLabel(state),
                    // On a solid bar the ground and the glyph swap, or the button would vanish.
                    ground = if (solid) onTint else tint,
                    ink = if (solid) tint else onTint,
                    size = 36.dp,
                    glyph = 17.dp,
                ) { playback.toggle() }

                if (state.canStep) {
                    StripButton(FiletIcons.Next, "Next", ink = ink) { playback.next() }
                }
                StripButton(FiletIcons.Close, "Stop", ink = ink, size = 34.dp) { playback.stop() }
            }
        }
    }
}

/**
 * The thumbnail, which shrinks when paused.
 *
 * The same tell the full player's artwork gives. It is worth repeating here rather than being
 * left to the glyph, because the strip is read at a glance from across a list - the shape
 * changing is noticed where a play triangle turning into two bars is not.
 */
@Composable
private fun StripArt(
    art: android.graphics.Bitmap?,
    isVideo: Boolean,
    playing: Boolean,
    tint: Color,
) {
    val colors = Filet.colors
    val side by animateDpAsState(
        if (playing) 44.dp else 38.dp,
        animationSpec = tween(260),
        label = "strip art",
    )
    Box(
        Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            val picture = art
            if (picture != null) {
                Image(
                    picture.asImageBitmap(), null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    if (isVideo) FiletIcons.Video else FiletIcons.Audio,
                    null,
                    tint = tint.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The glow's own panel, on the strip it is changing.
 *
 * ## Why this is not a DropdownMenu
 *
 * It was one, and it looked like a context menu out of a desktop file manager sitting under a
 * player that is trying to look like a music app - square corners, full-bleed rows, a divider
 * drawn edge to edge. The strip above it is rounded artwork, a filled circular transport and a
 * hairline progress bar.
 *
 * A menu is also the wrong object for "how much of this do you want". These are settings with
 * state, and a list of commands cannot show state without a tick in a column. So: a panel. The
 * three strengths are a segmented pill - one control, three positions, the current one filled
 * in the track's own colour, which answers "which am I on" without reading anything. The spill
 * is a switch, because it is on or off.
 *
 * Positioned by hand rather than by an alignment: above the button and right-aligned to it, so
 * it never covers the strip it is describing. Being able to watch the thing change is the
 * whole reason the control is down here instead of in Settings.
 */
@Composable
private fun GlowMenu(
    vm: BrowserViewModel,
    level: GlowLevel,
    spill: Boolean,
    look: StripLook,
    /** Beat pulse, from the full player's options. The spill row shows only while it is on. */
    pulseOn: Boolean,
    tint: Color,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripButton(
            FiletIcons.Brightness,
            "Background glow: ${level.label}",
            // The button's own brightness tracks the level it sets, which is the only state it
            // has left to show now that the glow cannot be off.
            ink = tint.copy(alpha = 0.45f + 0.275f * GlowLevel.entries.indexOf(level)),
            size = 32.dp,
            glyph = 15.dp,
        ) { open = true }

        if (open) {
            // Above the button, so the strip it is changing stays in view.
            PanelPopover(side = PanelSide.ABOVE, onDismiss = { open = false }, heading = "Background glow") {
                // Animated and Solid are one choice of three: turning one on turns the
                // other off, and both off is the mist held still. The strength belongs to
                // the mist, so it shows under Animated; the still mist keeps the last one.
                PanelRow("Animated", tint, on = look == StripLook.ANIMATED) {
                    vm.prefs.setStripLook((if (look == StripLook.ANIMATED) StripLook.STILL else StripLook.ANIMATED).id)
                }
                if (look == StripLook.ANIMATED) {
                    LevelPill(level, tint, Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)) {
                        vm.prefs.setGlowLevel(it.id)
                    }
                }
                PanelRow("Solid", tint, on = look == StripLook.SOLID) {
                    vm.prefs.setStripLook((if (look == StripLook.SOLID) StripLook.STILL else StripLook.SOLID).id)
                }
                // The spill is the beat's light leaving the strip, so it goes with Beat pulse.
                if (pulseOn) {
                    PanelRule()
                    PanelRow("Spill past the strip", tint, on = spill) {
                        vm.prefs.setGlowSpill(!spill)
                    }
                }
            }
        }
    }
}

/** Three strengths as one control, the current one filled. */
@Composable
private fun LevelPill(level: GlowLevel, tint: Color, modifier: Modifier = Modifier, onPick: (GlowLevel) -> Unit) {
    val colors = Filet.colors
    Row(
        modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.sunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (step in GlowLevel.entries) {
            val picked = step == level
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (picked) tint.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onPick(step) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    step.label,
                    fontSize = 11.5.sp,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (picked) tint else colors.fg2,
                )
            }
        }
    }
}

@Composable
private fun StripButton(
    icon: ImageVector,
    label: String,
    ground: Color = Color.Transparent,
    ink: Color = Filet.colors.fg2,
    size: Dp = 38.dp,
    glyph: Dp = 18.dp,
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
        Icon(icon, label, tint = ink, modifier = Modifier.size(glyph))
    }
}

/**
 * How far through the track the playhead is, 0 to 1. Pure.
 *
 * Zero for a track with no duration yet, rather than a division by zero that draws a full bar
 * for the instant between opening a file and learning how long it is.
 */
fun playheadFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/**
 * The strip's three grounds, picked in its Background glow panel. Stored as [id].
 */
enum class StripLook(val id: String) {
    /** The mist, drifting. The default. */
    ANIMATED("animated"),
    /** The whole bar in the track's colour, bouncing brighter on the beat. */
    SOLID("solid"),
    /** The mist, held still. */
    STILL("still");

    companion object {
        fun of(id: String?): StripLook = entries.firstOrNull { it.id == id } ?: ANIMATED
    }
}

/**
 * The strip's inner mist: one mist in the track's colour, strongest at the thumbnail and gone by
 * the far edge. At rest it is the light wash the strip always had, with no solid part, scaled by
 * the glow [level] (Soft is 1).
 *
 * A beat moves the whole mist at once, from the one hit value, so it reads as the mist jumping
 * rather than a second layer: the base under the thumbnail goes solid on a strong hit, the mist
 * past it thickens, and its falloff runs out from [REST_KNEE] to [HIT_KNEE] of the width, then it
 * all eases back with the beat's envelope.
 *
 * With a [clock] the mist drifts: two slanted bands of density flow across it at different
 * speeds and angles, cut to the mist's own shape, so it moves like mist rather than as circles
 * wandering behind the buttons. Without one it holds still.
 *
 * Inside the strip only: the light that spills up over the list is `stripRadiance`, unchanged.
 * The beat and the clock are read in the draw, so a frame redraws the band and recomposes nothing
 * (see the same note on `livingGlow`).
 */
private fun Modifier.innerMist(
    tint: Color,
    level: Float,
    clock: (() -> Float)?,
    swing: () -> Float,
): Modifier = drawWithCache {
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    // Where the base ends, just past the thumbnail, and where the thick mist past it ends.
    val base = (INNER_BASE.toPx() / w).coerceIn(0.05f, 0.3f)
    val past = base + 0.12f
    val k = level.coerceIn(0.3f, 2.5f)
    fun at(rest: Float, hit: Float, e: Float) = lerp((rest * k).coerceAtMost(1f), hit, e)
    fun ink(a: Float) = tint.copy(alpha = a.coerceIn(0f, 1f))
    fun brush(e: Float, solidity: Float = 1f): Brush {
        val knee = lerp(REST_KNEE, HIT_KNEE, e)
        return Brush.horizontalGradient(
            0f to ink(at(restWash(0f), HIT_BASE, e) * solidity),
            base to ink(at(restWash(base), HIT_BASE, e) * solidity),
            past to ink(at(restWash(past), HIT_PAST, e) * solidity),
            knee to ink(at(restWash(REST_KNEE), HIT_KNEE_ALPHA, e) * solidity),
            1f to Color.Transparent,
        )
    }
    val rest = brush(0f)
    // The drift's two bands: soft stripes of the colour, repeated, at a slant.
    val band = DRIFT_BAND.toPx()
    fun bands(t: Float, speed: Float, slant: Float, phase: Float): Brush {
        val x = (t * speed.dp.toPx() + phase * band) % band
        return Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White,
            1f to Color.Transparent,
            start = Offset(x, 0f),
            end = Offset(x + band, band * slant),
            tileMode = TileMode.Repeated,
        )
    }
    onDrawBehind {
        val e = swing().coerceIn(0f, 1f)
        val t = clock?.invoke()
        if (t == null) {
            drawRect(if (e < 0.01f) rest else brush(e))
            return@onDrawBehind
        }
        // Most of the mist is a steady floor, so it never flickers thin; the rest of it is the
        // drifting bands, in a layer cut to the mist's shape. A hit raises the floor, so the
        // base goes solid through the drift rather than in stripes.
        val floor = lerp(DRIFT_FLOOR, 1f, e)
        drawRect(brush(e, floor))
        drawIntoCanvas { canvas ->
            canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), androidx.compose.ui.graphics.Paint())
            val wobble = kotlin.math.sin(t * 0.37f) * 0.18f
            drawRect(bands(t, 22f, 0.55f + wobble, 0f), alpha = 0.75f)
            drawRect(bands(t, -13f, -0.4f - wobble, 0.37f), alpha = 0.55f, blendMode = BlendMode.Plus)
            // Coloured and shaped by the mist itself, at the strength the floor leaves.
            // The bands average about 0.6, so this keeps the mist's mean where the still one is.
            drawRect(brush(e, (1f - floor) / 0.6f), blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
    }
}

/** The drift's band width: one stripe of mist and the gap after it. */
private val DRIFT_BAND = 170.dp

/** The share of the mist that stays steady under the drift. */
private const val DRIFT_FLOOR = 0.5f

/** How much brighter a strong beat makes the solid bar. */
private const val SOLID_BOUNCE = 0.35f

/** The wash the strip had before the beat reached it: 0.16 at the left, 0.04 by 65%, gone at the edge. */
private fun restWash(x: Float): Float =
    if (x <= REST_KNEE) 0.16f - 0.12f * x / REST_KNEE else 0.04f * (1f - (x - REST_KNEE) / (1f - REST_KNEE))

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Where the base ends: the thumbnail and its margin. */
private val INNER_BASE = 60.dp

/** The base on a strong hit: the full track colour. */
private const val HIT_BASE = 0.97f

/** The mist just past the base on a strong hit, still mist. */
private const val HIT_PAST = 0.5f

/** Where the falloff starts at rest, as a fraction of the width, and how far a strong hit pushes it. */
private const val REST_KNEE = 0.65f
private const val HIT_KNEE = 0.82f

/** The mist at the falloff on a strong hit. */
private const val HIT_KNEE_ALPHA = 0.18f

/**
 * How much brighter the hairline gets on a hard beat.
 *
 * Generous, because this is the smallest surface in the app that answers the music: two device
 * pixels, where the player has a whole screen. A change that reads as a pulse on a 2dp line has
 * to be most of the way to white.
 */
private const val HAIRLINE_PUNCH = 1.1f
