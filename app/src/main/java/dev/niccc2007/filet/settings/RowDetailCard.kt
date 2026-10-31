package dev.niccc2007.filet.settings

import dev.niccc2007.filet.data.Prefs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.DetailPart
import dev.niccc2007.filet.browser.EntryDisplay
import dev.niccc2007.filet.browser.RowDetails
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What the line under a name is made of, built by moving cards around.
 *
 * ## Why this replaced a switch
 *
 * The setting was one toggle: "Details under the name - size and date on a second line". The
 * fault in it was not that it was missing anything. It was that the line carried things
 * that were not wanted, and the only available answer was turning the whole line off - so a
 * person who wanted the date but not the size had no way to say so, and no way to say which
 * came first either.
 *
 * A column of switches would not have been enough, because order is half the answer. Switches
 * have no order but the enum's own, so "date, then size" is not expressible and a newly ticked
 * part lands wherever the declaration puts it. The bottom bar had the same problem and was
 * fixed the same way; this follows [dev.niccc2007.filet.browser.BottomBarConfig]'s shape and
 * reuses its row, rather than inventing a second idiom for one problem.
 *
 * ## Why the preview shows two rows
 *
 * A file and a folder read the SAME configuration differently - a folder has no size of its own
 * and a file has no item count or total - so one sample would make the other case a surprise
 * found later in a real list. Both go through [RowDetails.render], the function the list itself
 * calls, so the preview cannot drift from the rows: it is not a mock-up of the result, it is the
 * result.
 */
@Composable
fun RowDetailCard(entry: EntryDisplay, onChange: (EntryDisplay) -> Unit) {
    val colors = Filet.colors
    val parts = entry.parts

    fun write(next: List<DetailPart>) = onChange(entry.copy(parts = next))

    Column(Modifier.fillMaxWidth()) {
        // ── the live render ───────────────────────────────────────────────────────────────
        //
        // Fixed sample values, and a date twelve days back so the "recent dates in words"
        // setting has something to be relative to without the sample changing under the reader.
        //
        // Both the line AND the column on the right go through the functions the list calls -
        // `RowDetails.render` and `RowDetails.one` - so the side choosers below this card change
        // the preview too. The right-hand value used to be a hard-coded "123 KB", which made the
        // preview a picture of the default rather than of the configuration.
        val at = remember { System.currentTimeMillis() - 12L * 86_400_000L }
        val now = at + 12L * 86_400_000L
        val folderTotal = dev.niccc2007.filet.browser.FolderTotal.Settled(4_938_100_000L)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            SamplePreview(
                name = "Report.pdf",
                measure = entry.sideFile?.let {
                    RowDetails.one(it, false, 123_000L, at, null, "Report.pdf", entry, now = now)
                }.orEmpty(),
                detail = RowDetails.render(
                    parts = parts,
                    isDir = false,
                    size = 123_000L,
                    at = at,
                    count = null,
                    name = "Report.pdf",
                    display = entry,
                    now = now,
                ),
            )
            Spacer(Modifier.height(8.dp))
            SamplePreview(
                name = "New folder",
                measure = entry.sideFolder?.let {
                    RowDetails.one(it, true, 0L, at, 13, "New folder", entry, folderTotal, now)
                }.orEmpty(),
                detail = RowDetails.render(
                    parts = parts,
                    isDir = true,
                    size = 0L,
                    at = at,
                    count = 13,
                    name = "New folder",
                    display = entry,
                    total = folderTotal,
                    now = now,
                ),
            )
        }

        if (parts.isEmpty()) {
            // Said plainly rather than left as an empty space, because an empty list is a
            // legitimate choice here and an empty card looks like a screen that failed to load.
            Text(
                "Nothing under the name.",
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = colors.fg3,
                modifier = Modifier.padding(start = 16.dp, end = 14.dp, bottom = 6.dp),
            )
        }

        for ((i, part) in parts.withIndex()) {
            BarRow(
                label = part.label,
                detail = null,
                hint = part.note,
                key = Prefs.K_E_PARTS,
                what = "the line",
                onUp = if (i > 0) ({ write(RowDetails.moved(parts, i, i - 1)) }) else null,
                onDown = if (i < parts.size - 1) {
                    ({ write(RowDetails.moved(parts, i, i + 1)) })
                } else {
                    null
                },
                // Every one can come off, unlike the bottom bar. An empty bar is a screen with
                // no way out; an empty detail line is just a one-line row, which is what a
                // person asking to get rid of the clutter is asking for.
                onRemove = { write(RowDetails.toggled(parts, part)) },
            )
        }

        val off = remember(parts) { DetailPart.entries.filterNot { it in parts } }
        if (off.isNotEmpty()) {
            Text(
                "Not on the line",
                fontSize = 9.sp,
                letterSpacing = 1.3.sp,
                color = colors.fg3,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            for (part in off) {
                BarRow(
                    label = part.label,
                    detail = null,
                    hint = part.note,
                    key = Prefs.K_E_PARTS,
                    what = "the line",
                    onAdd = { write(RowDetails.toggled(parts, part)) },
                )
            }
        }
    }
}

/**
 * One sample row, laid out the way a real one is: name on the left, measure on the right, the
 * detail line underneath.
 */
@Composable
private fun SamplePreview(name: String, measure: String, detail: String) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            // Drawn the way a real row draws its name - faded, never dotted - or the preview
            // shows something the list does not do. See ListName.
            dev.niccc2007.filet.browser.ListNameText(
                name,
                MaterialTheme.colorScheme.onSurface,
                startSp = 12f,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                measure,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg2,
            )
        }
        // Only when there is something, which is also how the real row behaves - an empty line
        // still reserves height, and a preview that reserved it would not match the list.
        if (detail.isNotEmpty()) {
            Text(
                detail,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
