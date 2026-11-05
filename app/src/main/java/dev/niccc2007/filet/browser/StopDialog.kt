package dev.niccc2007.filet.browser

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Shutdown Filet, and what that ends. See [RunningWork] for why the list is the feature.
 *
 * Says plainly what the stop does and does not do. It ends what is running - the music, a
 * share being hosted, a crawl, a copy - and nothing is deleted, so the next open finds every
 * setting, bookmark and playlist where it was. "As if just installed" is true of what is
 * running and would be false of what is stored, so the sheet does not say it.
 */
@Composable
internal fun StopDialog(
    items: List<RunningWork.Item>,
    askAgain: Boolean,
    onAskAgain: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAsk by remember { mutableStateOf(!askAgain) }
    val cuts = items.filter { it.interrupts }
    val rest = items.filterNot { it.interrupts }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            FiletIcons.Power,
            "Shutdown Filet",
            sub = "Ends everything it is running. Nothing is deleted.",
            onClose = onDismiss,
        )
        DlgBody {
            // Interrupted work first and in the warning colour: it is the only line on this
            // sheet that has a cost, so it is the line the eye should land on.
            for (c in cuts) DlgWarn("${c.label} — will stop partway and may leave a partial result")
            if (rest.isNotEmpty()) {
                DlgSection("Also ends")
                for (r in rest) {
                    Text(
                        r.label,
                        fontSize = 13.sp,
                        color = Filet.colors.fg2,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            if (items.isEmpty()) {
                // Said rather than shown as an empty list, which would read as the sheet having
                // failed to find out.
                Text(
                    "Nothing is running. Filet closes and stays closed until you open it.",
                    fontSize = 13.sp,
                    color = Filet.colors.fg2,
                )
            }
            Spacer(Modifier.height(12.dp))
            DlgTick(dontAsk, "Don't ask again") { dontAsk = !dontAsk }
            // The limit of the tick, stated where it is set rather than discovered later.
            DlgCaption("Still asks whenever stopping would cut work off partway.")
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                RunningWork.confirmLabel(items),
                kind = if (cuts.isNotEmpty()) BtnKind.DANGER else BtnKind.PRIMARY,
            ) {
                onAskAgain(!dontAsk)
                onStop()
            }
        }
    }
}
