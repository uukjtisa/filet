package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.ResultGroups.State
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The header over one drive's results. SEARCH.md §13.5.
 *
 * One 32dp row and nothing around it - no fill of its own, no border, no elevation. A group whose
 * header needs a box is a group that should not exist. The hairline running out to the edge is
 * the only ornament, and it is what makes the row read as a section rather than as another item
 * in the list.
 *
 * The ground is the screen's own background, painted rather than left transparent, because the
 * header is sticky: rows scroll underneath it, and a transparent sticky header is a header with
 * file names showing through its label.
 */
@Composable
internal fun DriveHeader(group: ResultGroups.Group, modifier: Modifier = Modifier) {
    val colors = Filet.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same glyph the storage cards on Home use for the same drive, so a drive is known
        // by its shape before its label is read.
        Icon(
            if (group.remote) FiletIcons.Wifi else FiletIcons.Storage,
            contentDescription = null,
            tint = colors.fg3,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        // Never upper-cased. A share's name is somebody's own wording, and SPARE TABLET is a
        // different word from Spare tablet.
        Text(
            group.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            color = colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(" · ", fontSize = 12.sp, color = colors.fg3)
        if (group.state == State.SEARCHING) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            ResultGroups.caption(group),
            fontSize = 12.sp,
            color = when (group.state) {
                State.FOUND, State.NONE -> colors.fg3
                State.SEARCHING -> colors.accent
                State.UNREACHABLE -> colors.warn
            },
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.lineSoft),
        )
    }
}
