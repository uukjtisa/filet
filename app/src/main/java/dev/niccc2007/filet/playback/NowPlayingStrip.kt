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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
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
@Composable
fun NowPlayingStrip(vm: BrowserViewModel) {
    val colors = Filet.colors
    val playback = vm.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val art by playback.art.collectAsStateWithLifecycle()

    if (!state.active) return
    val track = state.current ?: return

    // The same sampler the full player uses, so the two never disagree about what colour this
    // track is. Falls back to the theme accent when the artwork has none to give.
    val (tint, onTint) = rememberArtPalette(
        art = art,
        fallback = colors.accent,
        onFallback = MaterialTheme.colorScheme.onPrimary,
    )

    val glow by vm.prefs.livingGlow.collectAsStateWithLifecycle()
    val level = GlowLevel.of(vm.prefs.glowLevel.collectAsStateWithLifecycle().value)
    val spill by vm.prefs.glowSpill.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            // A wash of the track's colour, left to right, faint. Enough that the strip
            // belongs to what is playing; not enough to compete with the list above it.
            .background(
                Brush.horizontalGradient(
                    0f to tint.copy(alpha = 0.16f),
                    0.65f to tint.copy(alpha = 0.04f),
                    1f to Color.Transparent,
                ),
            )
            // And the same drifting lights the full player has, over that wash, so the strip
            // is the player it minimises into rather than a different widget. Weaker here:
            // this is a 56dp band under a file list, and the lights are nearly the whole
            // surface of it. See `LivingGlow`.
            .livingGlow(
                base = tint,
                on = glow,
                running = state.playing,
                strength = GLOW_OVER_STRIP * level.scale,
                spread = GLOW_SPREAD_STRIP,
                clip = !spill,
            ),
    ) {
        HorizontalDivider(color = colors.lineSoft)

        // The hairline. Full width, above the row, so it reads as the strip's own edge filling
        // up rather than as a widget sitting inside it.
        val fraction = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(colors.lineSoft, size = size)
            drawRect(tint, size = size.copy(width = size.width * fraction))
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
                    )
                    val subtitle = PlaybackNotice.strip(state)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.fg3,
                        )
                    }
                }

                // Here rather than only in Settings, because this is where the glow is
                // noticed and the thing it changes is an inch from the finger. It opens a
                // menu rather than cycling: four states read off one unlabelled button is a
                // guessing game, and the menu stays beside the strip so the strip itself is
                // visible while it is being judged.
                GlowMenu(vm, glow, level, spill, tint)

                if (roomForPrev && state.canStep) {
                    StripButton(FiletIcons.Prev, "Previous") { playback.previous() }
                }

                // Filled, in the track's colour. The one control in this row that is pressed
                // without looking, and the only one worth giving a ground to.
                StripButton(
                    if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                    PlaybackNotice.toggleLabel(state),
                    ground = tint,
                    ink = onTint,
                    size = 36.dp,
                    glyph = 17.dp,
                ) { playback.toggle() }

                if (state.canStep) {
                    StripButton(FiletIcons.Next, "Next") { playback.next() }
                }
                StripButton(FiletIcons.Close, "Stop", size = 34.dp) { playback.stop() }
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
    glow: Boolean,
    level: GlowLevel,
    spill: Boolean,
    tint: Color,
) {
    var open by remember { mutableStateOf(false) }
    val colors = Filet.colors
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Box {
        StripButton(
            FiletIcons.Brightness,
            if (glow) "Background glow: ${level.label}" else "Background glow: off",
            // Dimmed when the glow is off rather than removed. The first version hid this
            // button whenever the glow was off - so the one item in its own panel that turns
            // the glow off also took away the only way back, leaving the switch three taps
            // deep in Settings and no sign of where it had gone.
            ink = if (glow) {
                tint.copy(alpha = 0.45f + 0.275f * GlowLevel.entries.indexOf(level))
            } else {
                colors.fg3.copy(alpha = 0.6f)
            },
            size = 32.dp,
            glyph = 15.dp,
        ) { open = true }

        if (open) {
            Popup(
                popupPositionProvider = AbovePositionProvider(gap),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(238.dp)
                        .shadow(22.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.raised)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(22.dp))
                        .padding(vertical = 14.dp),
                ) {
                    Text(
                        "BACKGROUND GLOW",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.3.sp,
                        color = colors.fg3,
                        modifier = Modifier.padding(start = 17.dp, bottom = 11.dp),
                    )
                    if (glow) {
                        LevelPill(level, tint) { vm.prefs.setGlowLevel(it.id) }
                        PanelRule()
                        PanelRow("Spill past the strip", tint, on = spill) {
                            vm.prefs.setGlowSpill(!spill)
                        }
                        PanelRule()
                        PanelRow("Turn it off", tint, on = null) {
                            open = false
                            vm.prefs.setLivingGlow(false)
                        }
                    } else {
                        PanelRow("Turn it on", tint, on = null) {
                            open = false
                            vm.prefs.setLivingGlow(true)
                        }
                    }
                }
            }
        }
    }
}

/** Above the button and right-aligned to it, so the strip underneath stays visible. */
private class AbovePositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val room = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, room)
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Three strengths as one control, the current one filled. */
@Composable
private fun LevelPill(level: GlowLevel, tint: Color, onPick: (GlowLevel) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
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

/** A hairline that stops short of the panel's edge, so the corners stay round. */
@Composable
private fun PanelRule() {
    Box(
        Modifier
            .padding(horizontal = 17.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Filet.colors.lineSoft),
    )
}

/** One row. [on] null makes it an action rather than a switch. */
@Composable
private fun PanelRow(label: String, tint: Color, on: Boolean?, onClick: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (on == true) tint else colors.fg2,
            modifier = Modifier.weight(1f),
        )
        if (on != null) MiniSwitch(on, tint)
    }
}

/** The smallest honest switch: a track, and a knob at one end of it. */
@Composable
private fun MiniSwitch(on: Boolean, tint: Color) {
    val colors = Filet.colors
    val slide by animateDpAsState(if (on) 17.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .size(width = 34.dp, height = 19.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) tint.copy(alpha = 0.3f) else colors.sunken),
    ) {
        Box(
            Modifier
                .padding(start = slide)
                .align(Alignment.CenterStart)
                .size(15.dp)
                .clip(CircleShape)
                .background(if (on) tint else colors.fg3),
        )
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
