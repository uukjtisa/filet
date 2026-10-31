package dev.niccc2007.filet.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A Home feed card's own settings, under its row in the edit mode: how many rows it shows, and
 * how tightly - see [FeedCard].
 *
 * Indented to the card's name in the row above, so it reads as that card's and not the next
 * one's. The [preview] is the card's first rows at the density being set: the cards are not on
 * screen while the editor is, and a slider whose effect cannot be seen is a guess.
 */
@Composable
internal fun FeedCardSettings(
    count: Int,
    onCount: (Int) -> Unit,
    density: Float,
    onDensity: (Float) -> Unit,
    /** Whether the two feed cards share one density. Shown on both, the same switch. */
    linked: Boolean,
    onLinked: (Boolean) -> Unit,
    hasPreview: Boolean,
    preview: @Composable () -> Unit,
) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(start = INDENT, end = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Show")
            val shape = RoundedCornerShape(8.dp)
            Row(Modifier.clip(shape).border(1.dp, colors.lineSoft, shape)) {
                for (n in FeedCard.COUNTS) {
                    val on = n == count
                    Text(
                        n.toString(),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                        modifier = Modifier
                            .background(if (on) colors.accent else Color.Transparent)
                            .clickable { onCount(n) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Label("Density", Modifier.padding(top = 8.dp))
            Column(Modifier.weight(1f)) {
                DensitySlider(density, onDensity)
                Row {
                    Text("Comfortable", fontSize = 9.5.sp, color = colors.fg3, modifier = Modifier.weight(1f))
                    Text("Compact", fontSize = 9.5.sp, color = colors.fg3)
                }
            }
        }
        // Under the slider it governs. The whole line is the target, as a toggle row is in
        // Settings, so the switch can be drawn at the size of the words beside it.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onLinked(!linked) }
                .padding(start = LABEL_W, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Same density for both feeds",
                fontSize = 11.sp,
                color = colors.fg2,
                modifier = Modifier.weight(1f),
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                androidx.compose.material3.Switch(
                    checked = linked,
                    onCheckedChange = onLinked,
                    modifier = Modifier.requiredSize(52.dp, 32.dp).scale(0.7f),
                )
            }
        }
        if (hasPreview) {
            Spacer(Modifier.height(8.dp))
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.raised)
                    .border(1.dp, colors.lineSoft, shape),
            ) { preview() }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.5.sp, color = Filet.colors.fg2, modifier = modifier.width(LABEL_W))
}

/** The Settings page's compact slider, at the size of the text beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DensitySlider(value: Float, onChange: (Float) -> Unit) {
    val colors = Filet.colors
    val sliderColors = SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.raised,
    )
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = sliderColors,
            interactionSource = interaction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = sliderColors,
                    thumbSize = DpSize(4.dp, 24.dp),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(10.dp),
                    colors = sliderColors,
                )
            },
        )
    }
}

/** The editor row's handle and gap: the settings start under the card's name. */
private val INDENT = 48.dp

/** Wide enough for "Density", so both controls start on one line. */
private val LABEL_W = 62.dp
