package dev.niccc2007.filet.handlers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The viewers' bottom bar, in pieces: the row, its buttons, and the rows its More menu shares.
 *
 * Lifted out of the picture viewer, where it was private, when the video's tap-to-play preview
 * was given the same bar. Two viewers each drawing "a row of named buttons along the bottom" is
 * two bars that drift - a different gradient, a label a size off, one of them under the
 * three-key bar - so both draw this one.
 *
 * What each button does stays with the viewer: the picture has Rotate and Edit, a video does
 * not. What is the same everywhere is the row, the look of a button, and the two More rows that
 * mean the same thing in every viewer.
 */

/**
 * The row along the bottom of a viewer, each button with its name under it.
 *
 * Clear of the navigation bar itself: a viewer draws to the screen's edge, so nothing else
 * stops this at the bar. `tools/check-insets.mjs` holds it to that.
 */
@Composable
internal fun ViewerActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val ground = MaterialTheme.colorScheme.background
    Row(
        modifier
            .fillMaxWidth()
            // The top bar's gradient, upside down: dense behind the labels, gone by the picture.
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to ground.copy(alpha = 0.55f),
                    1f to ground.copy(alpha = 0.92f),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One of [ViewerActionBar]'s buttons: an icon and its name, bright enough to read over a
 * photograph. A name under a button is the cheapest way to stop it being guessed at.
 */
@Composable
internal fun ViewerBarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = if (enabled) colors.fg2 else colors.fg3)
    }
}

/** More's way to the file's folder. Out of every viewer, not back one: see the callers. */
@Composable
internal fun ShowInFolderRow(onClick: () -> Unit) {
    PopRow(FiletIcons.Folder, "Show in folder", onClick = onClick)
}

/**
 * More's switch between a black and a grey ground behind the picture, one setting for every
 * viewer that has it. Named for what it switches TO, as a light switch is: the row is the action.
 */
@Composable
internal fun ViewerGroundRow(grey: Boolean, onClick: () -> Unit) {
    PopRow(FiletIcons.Brightness, if (grey) "Black background" else "Grey background", onClick = onClick)
}
