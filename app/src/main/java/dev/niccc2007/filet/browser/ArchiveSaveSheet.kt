package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What to do with a file that was edited inside an archive.
 *
 * The prompt is exactly this: on save, offer to update the archive or to write the file
 * somewhere else. Both halves matter.
 *
 * **Update** is shown with its cost first, because a save into a zip rewrites one entry and a
 * save into a 7z rewrites the whole archive - one keystroke apart in the UI, minutes apart in
 * reality. When the archive cannot hold the edit at all (encrypted, or a format Filet only
 * reads) the reason is printed and the row is gone, rather than the row being there and failing
 * afterwards.
 *
 * **Save somewhere else** is offered for every archive without exception, including the ones
 * that could be updated. It is the answer to "I did not mean to change the archive", and it is
 * the only answer for a RAR.
 *
 * The two live in the dialog's BODY as full-width rows rather than in its button slot. Two
 * stacked buttons in an `AlertDialog`'s button row lay out down the row's height and push the
 * third off the bottom of the dialog - which is what the first version of this did, and it took
 * a screenshot to see. They also read better this way: these are two choices, not an OK and a
 * Cancel.
 */
@Composable
fun ArchiveSaveSheet(
    fileName: String,
    archiveName: String,
    cost: String,
    refusal: String?,
    busy: Boolean,
    onUpdate: () -> Unit,
    onElsewhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    // Scrim dismissal is off while busy: the archive is being rewritten, and a stray tap
    // outside the card during that is not a decision to cancel anything.
    Dlg(onDismiss = { if (!busy) onDismiss() }, dismissOnScrim = false) {
        DlgHeader(
            FiletIcons.Zip,
            "Save $fileName",
            sub = "inside $archiveName",
            onClose = { if (!busy) onDismiss() },
        )
        DlgBody {
            // One banner, and only when the archive is actually refusing. The cost is a fact
            // about what is about to happen, not an obstacle to it, so it is a caption.
            if (refusal != null) DlgWarn(refusal, bad = true) else DlgCaption(cost)
            Spacer(Modifier.height(10.dp))
            if (refusal == null) {
                DlgAction(
                    FiletIcons.Zip,
                    if (busy) "Saving…" else "Update the archive",
                    sub = "Puts the edit back where it came from.",
                    enabled = !busy,
                    onClick = onUpdate,
                )
            }
            DlgAction(
                FiletIcons.FolderOpen,
                "Save somewhere else…",
                sub = "Pick a folder. The archive is left exactly as it is.",
                enabled = !busy,
                onClick = onElsewhere,
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", enabled = !busy, onClick = onDismiss)
        }
    }
}
