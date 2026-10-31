package dev.niccc2007.filet.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
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
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.CollisionChoice
import dev.niccc2007.filet.vfs.provider.ExtractPlan

/**
 * What the extraction is about to do, before it does it.
 *
 * The design calls for a confirmation showing what the result will look like, because an archive he
 * expected to produce `<archive name>/contents` scattered its contents straight into the folder
 * instead - and he only found out afterwards, with the mess already made.
 *
 * So this draws the destination AS IT WILL BE. A folder the plan invents is marked as added; a
 * redundant parent it lifted away is struck through. Both buttons are here, one tap
 * each, and everything on screen comes from the same [ExtractPlan] the extractor will walk - so
 * this cannot be a truthful drawing of something that then does not happen.
 *
 * Four things are said before you commit, all of which are otherwise discovered too late:
 * what would be overwritten, whether it fits, what the archive costs, and whether any entry
 * tried to write outside the folder.
 */
@Composable
fun BoxScope.ExtractCard(
    plan: ExtractPlan,
    destinationName: String,
    busy: Boolean,
    onWrap: () -> Unit,
    onStripLess: () -> Unit,
    /** Take one MORE wrapper off, up to `plan.strippableLevels`. */
    onStripMore: () -> Unit,
    onCollision: (CollisionChoice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Why this destination cannot be written to, or null. See `writeBlockReason`. */
    readOnly: String? = null,
    onHeight: (Int) -> Unit = {},
) {
    val colors = Filet.colors

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            // Measured and reported so the listing can pad clear of it, exactly as the pick
            // card does. See `LocalPickInset`.
            .onSizeChanged { onHeight(it.height) },
    ) {
      Column(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
      ) {
        // The destination is the one fact worth stating in words. The ghosts show WHAT will
        // be created; only the header can say WHERE, and an archive about to spray forty
        // files into the folder you are standing in should say so before it does.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Both lines carry a name, so neither is dotted - see ListName. The archive's
                // extension stays in sight past the fade, which is the part that says what it is.
                ListNameText(
                    "Extract ${plan.archiveName}",
                    MaterialTheme.colorScheme.onSurface,
                    startSp = 13.5f,
                    fontWeight = FontWeight.SemiBold,
                )
                ListNameText("into $destinationName", colors.fg3, startSp = 10.5f, isDir = true)
            }
            Icon(
                FiletIcons.Close,
                "Cancel",
                tint = colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        run {

                // ── the two buttons ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (plan.wrapFolder != null) "Don't add a folder" else "Put in a folder", onWrap)
                }
                // ── the wrappers, as a control rather than as a fait accompli ──────────────
                //
                // Both directions, and only when there is somewhere to go in that direction.
                // One pill that could only put a folder BACK was the whole control, so an
                // archive whose wrappers had all been lifted offered one step of undo and no
                // way to see that more had been taken than you wanted - which is how
                // `firms/firmware/stuff.txt` extracted as a bare `stuff.txt`.
                if (plan.strippableLevels > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // Nothing is lifted by default, so the resting state says what IS
                            // happening rather than counting what is not. "0 of 2" reads as a
                            // setting that failed to apply; "kept as it is in the archive" is
                            // the same fact stated as the decision it actually is.
                            if (plan.strippedFolders.isEmpty()) {
                                "Folders kept as they are in the archive"
                            } else {
                                "Folders lifted: ${plan.strippedFolders.size} of ${plan.strippableLevels}"
                            },
                            fontSize = 10.5.sp,
                            color = colors.fg3,
                            modifier = Modifier.weight(1f),
                        )
                        if (plan.strippedFolders.isNotEmpty()) {
                            Pill("Keep ${plan.strippedFolders.last()}/", onStripLess)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (plan.strippedFolders.size < plan.strippableLevels) {
                            Pill(
                                if (plan.strippedFolders.isEmpty()) "Lift the top folder"
                                else "Lift one more",
                                onStripMore,
                            )
                        }
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
        if (readOnly != null) {
            Spacer(Modifier.height(6.dp))
            // Said, not implied by a dead button. The answer is to walk somewhere writable,
            // and somebody can only do that if they are told what is wrong.
            Warn(readOnly)
        }
        Spacer(Modifier.height(10.dp))
        ExtractCardButton(
            label = when {
                busy -> "Working…"
                readOnly != null -> "Cannot write here"
                else -> "Extract"
            },
            enabled = !busy && readOnly == null && plan.items.isNotEmpty(),
            onClick = onConfirm,
        )
      }
    }
}

/** The one action. Cancel is the X in the header, as on the pick card. */
@Composable
private fun ExtractCardButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) colors.accent else colors.fg3.copy(alpha = 0.35f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
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
            // The invented folder is the answer to the question, so it is the one thing on the
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
