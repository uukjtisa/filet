package dev.niccc2007.filet.playback

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
            // 360dp is the narrow-phone line: below it the title has under 120dp once four
            // controls and a thumbnail are placed, which is a word and a half.
            val roomForPrev = maxWidth >= 360.dp
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
                    val subtitle = PlaybackNotice.subtitle(state)
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
