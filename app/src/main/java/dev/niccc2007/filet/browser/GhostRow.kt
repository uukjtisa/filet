package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.pick.PickPreview
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A row for something that does not exist yet, drawn as the row it is going to be.
 *
 * ## Why it is not a dashed outline
 *
 * The first version framed each ghost in a dashed accent border on an accent ground, on the
 * reasoning that it must read as not-yet-real at a glance. It did, and it read as a *widget*
 * rather than as a file: a column of outlined boxes interrupting a file list looks like an
 * error state, not like a preview of what will be there.
 *
 * So it is the same shape as a real row - the same icon well, the same name size, the same
 * second line - and the one thing that marks it as pending is a quiet "new" tag and a slight
 * accent wash. What it is previewing is what this folder will LOOK like, so it has to look
 * like the folder.
 *
 * It is not clickable. A ghost that answered a tap would be claiming to be a file.
 */
@Composable
fun GhostRow(ghost: PickPreview.Ghost) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            // The same wash a selected row gets, at a third of the strength: present, clearly
            // not an ordinary row, and nothing like a frame.
            .background(colors.accent.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (ghost.isDir) FiletIcons.Folder else FiletIcons.File,
                null,
                tint = colors.accent.copy(alpha = 0.75f),
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // A name, so never dotted: it gives up size, then fades before its extension. See ListName.
            ListNameText(
                ghost.name,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                startSp = 13f,
                isDir = ghost.isDir,
                fontWeight = if (ghost.invented) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                ghost.note.ifEmpty { if (ghost.isDir) "folder" else "file" },
                fontSize = 10.sp,
                color = if (ghost.note.isEmpty()) colors.fg3 else colors.warn,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        // The tag, where a real row shows its size. Small, lower case, and the only thing on
        // the row that says this has not happened yet.
        Text(
            "new",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.accent.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
