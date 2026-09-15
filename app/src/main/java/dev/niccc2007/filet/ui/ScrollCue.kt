package dev.niccc2007.filet.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A row that scrolls sideways **and looks like it does**.
 *
 * Twice. Round 8, about the update sheet's chips: the row gave no sign it scrolled
 * sideways and he only found out by dragging it, and the design calls for the affordance to become the
 * standard for every such row rather than a fix in one place.
 *
 * The second report is the important one, because the first had already been "fixed" - the
 * selection bar did have a fade. A 7% gradient into the panel colour is not a cue; it reads as
 * the edge of the panel. Content that is off-screen with nothing pointing at it may as well not
 * exist, and a phone has no hover, no scrollbar and no wheel to discover it with.
 *
 * So this draws three things, not one:
 *
 *  1. **A wider fade**, so a half-visible chip dissolves instead of being sliced mid-glyph. A
 *     control cut off mid-icon reads as a broken layout rather than as more content.
 *  2. **A chevron** on whichever side has more. This is the actual cue: a mark that means "that
 *     way", which a gradient is not.
 *  3. **Nothing at all** when everything fits. An affordance that is always there teaches
 *     people to ignore it, and then it is not an affordance.
 *
 * Both cues cross-fade rather than appearing, because a chevron that pops on at the moment your
 * finger lifts looks like a glitch.
 *
 * This is the one implementation. `tools/check-scrollcue.mjs` fails if another horizontal
 * scroller appears without either using it or recording why it does not need a cue.
 */
@Composable
fun HScroll(
    modifier: Modifier = Modifier,
    /** The colour the edges dissolve into: whatever is painted behind the row. */
    ground: Color = Filet.colors.raised,
    state: ScrollState = rememberScrollState(),
    contentPadding: Dp = 0.dp,
    spacing: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(state)
                .padding(horizontal = contentPadding),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )

        // `maxValue` is Int.MAX_VALUE until the row has been measured. Treating that as "there
        // is more to the right" flashes a chevron on the first frame of every screen that has
        // one of these.
        val measured = state.maxValue != Int.MAX_VALUE && state.maxValue > 0
        Cue(visible = measured && state.value > EDGE_SLOP, ground = ground, atStart = true)
        Cue(
            visible = measured && state.maxValue - state.value > EDGE_SLOP,
            ground = ground,
            atStart = false,
        )
    }
}

/** Within this many pixels of an end, call it the end. A one-pixel chevron is just noise. */
private const val EDGE_SLOP = 2

/** Wide enough to dissolve a chip rather than clip it, narrow enough not to hide one. */
private val CUE_WIDTH = 38.dp

@Composable
private fun BoxScope.Cue(visible: Boolean, ground: Color, atStart: Boolean) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scroll cue")
    if (alpha <= 0.01f) return

    val transparent = ground.copy(alpha = 0f)
    val brush = Brush.horizontalGradient(
        if (atStart) listOf(ground, ground.copy(alpha = 0.86f), transparent)
        else listOf(transparent, ground.copy(alpha = 0.86f), ground)
    )
    // TWO boxes, and the outer one is the whole point.
    //
    // `matchParentSize` measures against the parent's RESOLVED size and contributes nothing to
    // deciding it. `fillMaxHeight` resolves against the incoming maximum, which inside a column
    // whose height is still being decided is the whole screen - so the overlay grows to fill
    // the pane, pushes the file list out and strands a chevron in the middle of nowhere.
    //
    // That is not hypothetical. This file's first draft had `fillMaxHeight` here with a comment
    // claiming it was safe because the width was fixed. It was not; the listing vanished and a
    // lone arrow floated mid-screen. The width being fixed constrains the WIDTH.
    //
    // So: the outer box takes the parent's real size without influencing it, and the inner one
    // may then fill a height that is actually known.
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .width(CUE_WIDTH)
                .alpha(alpha)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            // fg2, not fg3. The fault was that the cue was barely visible, and one drawn in the
            // same grey as disabled text is a cue somebody has to look for.
            Icon(
                imageVector = if (atStart) FiletIcons.Back else FiletIcons.Forward,
                contentDescription = if (atStart) "More to the left" else "More to the right",
                tint = Filet.colors.fg2,
                modifier = Modifier.size(17.dp).padding(start = if (atStart) 0.dp else 5.dp),
            )
        }
    }
}
