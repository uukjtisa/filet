package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.CollisionChoice
import dev.niccc2007.filet.vfs.provider.ExtractPlan

/**
 * What the extraction is about to do, before it does it.
 *
 * Nic asked for a confirmation showing what the result will look like, because an archive he
 * expected to produce `<archive name>/contents` scattered its contents straight into the folder
 * instead - and he only found out afterwards, with the mess already made.
 *
 * So this draws the destination AS IT WILL BE. A folder the plan invents is marked as added; a
 * redundant parent it lifted away is struck through. Both of his buttons are here, one tap
 * each, and everything on screen comes from the same [ExtractPlan] the extractor will walk - so
 * this cannot be a truthful drawing of something that then does not happen.
 *
 * Four things are said before you commit, all of which are otherwise discovered too late:
 * what would be overwritten, whether it fits, what the archive costs, and whether any entry
 * tried to write outside the folder.
 */
@Composable
fun ExtractSheet(
    plan: ExtractPlan,
    destinationName: String,
    busy: Boolean,
    onWrap: () -> Unit,
    onStripLess: () -> Unit,
    onCollision: (CollisionChoice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract ${plan.archiveName}", fontSize = 15.sp, maxLines = 2) },
        text = {
            Column {
                Text(
                    "into $destinationName",
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                // ── the tree ──
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    plan.strippedFolders.forEach { gone ->
                        item(key = "s:$gone") {
                            Text(
                                "$gone/",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.fg3,
                                // Struck through rather than absent: showing what was removed is
                                // what makes "move content out" legible as a thing that happened.
                                textDecoration = TextDecoration.LineThrough,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    items(plan.items.size.coerceAtMost(PREVIEW_ROWS), key = { plan.items[it].path }) { i ->
                        PlanRow(plan.items[i])
                    }
                    val hidden = plan.items.size - PREVIEW_ROWS
                    if (hidden > 0) {
                        item(key = "rest") {
                            Text(
                                "and $hidden more",
                                fontSize = 10.sp,
                                color = colors.fg3,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── his two buttons ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (plan.wrapFolder != null) "Don't add a folder" else "Put in a folder", onWrap)
                    if (plan.strippedFolders.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Pill("Keep ${plan.strippedFolders.last()}/", onStripLess)
                    }
                }

                // ── what you would otherwise find out afterwards ──
                Spacer(Modifier.height(10.dp))
                Note("${plan.entryCount} file${if (plan.entryCount == 1) "" else "s"} · ${bytes(plan.needsBytes)}")
                if (plan.alreadyCompressedBytes > plan.needsBytes / 2 && plan.needsBytes > 0) {
                    Note("mostly already-compressed media, so it will not shrink", dim = true)
                }

                if (!plan.fits) {
                    Warn("Not enough room: needs ${bytes(plan.needsBytes)}, ${bytes(plan.freeBytes)} free")
                }

                if (plan.collisions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Warn("${plan.collisions.size} file(s) already there")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        val chosen = plan.collisions.first().collides
                        for (c in CollisionChoice.entries) {
                            Pill(collisionLabel(c), { onCollision(c) }, on = c == chosen)
                            Spacer(Modifier.width(5.dp))
                        }
                    }
                }

                if (plan.refused.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // Not hidden. An archive containing a path that points outside the folder is
                    // one to be suspicious of, and silently dropping three entries would mean
                    // never knowing that.
                    Warn("${plan.refused.size} entr${if (plan.refused.size == 1) "y" else "ies"} refused - they point outside this folder")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && plan.items.isNotEmpty()) {
                Text(if (busy) "Working…" else "Extract")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlanRow(item: dev.niccc2007.filet.vfs.provider.PlannedItem) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.added) {
            Text("+", fontSize = 11.sp, color = colors.accent, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (item.isDir) "${item.path}/" else item.path,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            // The invented folder is the answer to his question, so it is the one thing on the
            // list drawn in the accent colour.
            color = when {
                item.added -> colors.accent
                item.collides != null -> MaterialTheme.colorScheme.error
                else -> colors.fg2
            },
            fontWeight = if (item.added) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit, on: Boolean = false) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (on) colors.accent else colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) colors.sel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun Note(text: String, dim: Boolean = false) {
    Text(text, fontSize = 10.sp, color = if (dim) Filet.colors.fg3 else Filet.colors.fg2)
}

@Composable
private fun Warn(text: String) {
    Text(text, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error)
}

private fun collisionLabel(c: CollisionChoice) = when (c) {
    CollisionChoice.SKIP -> "Skip"
    CollisionChoice.OVERWRITE -> "Overwrite"
    CollisionChoice.KEEP_BOTH -> "Keep both"
}

private fun bytes(n: Long): String = when {
    n < 0 -> "unknown"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}

/**
 * How much of the tree is drawn.
 *
 * Enough to see the shape - which is what the preview is for - without turning a 40,000-entry
 * archive into 40,000 composables. The count of what is not shown is on screen.
 */
private const val PREVIEW_ROWS = 200
