package dev.niccc2007.filet.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * **Previous is not on it.** Four controls and a title in a 56dp row on a 360dp phone leaves
 * nothing legible, and the queue runs forwards: Next is the one that gets pressed. Previous is
 * a tap away on the full player, and on the notification, and on an earphone remote.
 *
 * **The progress line is a Canvas, not a `LinearProgressIndicator`.** It is a hairline along
 * the top edge rather than a component with its own padding and track colour, and drawing two
 * rectangles is less code than talking a Material component out of looking like one.
 */
@Composable
fun NowPlayingStrip(vm: BrowserViewModel) {
    val colors = Filet.colors
    val playback = vm.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val art by playback.art.collectAsStateWithLifecycle()

    if (!state.active) return
    val track = state.current ?: return

    Column(Modifier.fillMaxWidth().background(colors.raised)) {
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
            drawRect(colors.accent, size = size.copy(width = size.width * fraction))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                // The whole row opens the player, except the buttons, which stop the press
                // reaching here by having their own clickable.
                .clickable { vm.maximisePlayer() }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val picture = art
                if (picture != null) {
                    Image(
                        picture.asImageBitmap(), null,
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        if (track.medium == Medium.VIDEO) FiletIcons.Video else FiletIcons.Audio,
                        null,
                        tint = colors.accent.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.size(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    PlaybackNotice.title(state),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
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

            StripButton(
                if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                PlaybackNotice.toggleLabel(state),
            ) { playback.toggle() }

            if (state.canStep) {
                StripButton(FiletIcons.Next, "Next") { playback.next() }
            }
            StripButton(FiletIcons.Close, "Stop") { playback.stop() }
        }
    }
}

@Composable
private fun StripButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = Filet.colors
    Icon(
        icon, label,
        tint = colors.fg2,
        // 40dp of touch target around a 19dp glyph. The row is 56dp tall and these sit in it,
        // so the target is as tall as it can be without the ripple leaving the strip.
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    )
}
