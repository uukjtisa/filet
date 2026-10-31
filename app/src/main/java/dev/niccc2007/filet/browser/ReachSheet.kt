package dev.niccc2007.filet.browser

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
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

/**
 * Choose exactly which drives a search covers. SEARCH.md §13.3.
 *
 * The ladder is the fast path and this is the precise one, for the cases the ladder cannot
 * express: from Home there is no folder to stand in, so the "this drive" rung does not exist,
 * and yet searching exactly one drive is still a reasonable thing to want. It is also the ONLY
 * way to reach the root mounts. No rung includes them - somebody searching "this phone" is
 * looking for their own files - so a search of `/data` is something asked for by name, here.
 *
 * Opened by a long press on the reach chips, because it refines what they already say rather
 * than being a separate control with its own place on screen.
 */
@Composable
internal fun ReachSheet(
    drives: List<ReachDrive>,
    current: Reach,
    onPick: (Reach) -> Unit,
    onDismiss: () -> Unit,
) {
    var ticked by remember(current, drives) { mutableStateOf(ReachSheetModel.ticked(current, drives)) }
    val toggle = { key: VolumeKey -> ticked = if (key in ticked) ticked - key else ticked + key }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Storage, "Search these drives", onClose = onDismiss)
        DlgBody {
            val (phone, shares, system) = ReachSheetModel.sections(drives)
            if (phone.isNotEmpty()) {
                DlgSection("On this phone")
                for (d in phone) DlgTick(d.key in ticked, d.label) { toggle(d.key) }
            }
            if (shares.isNotEmpty()) {
                DlgSection("Shares")
                for (d in shares) {
                    // Said on the row, because it is the one fact about a share that changes what
                    // ticking it costs: it is asked over the network, live, every time.
                    DlgTick(d.key in ticked, d.label, sub = "Searched over the network") { toggle(d.key) }
                }
            }
            if (system.isNotEmpty()) {
                DlgSection("System")
                for (d in system) DlgTick(d.key in ticked, d.label) { toggle(d.key) }
                Spacer(Modifier.height(2.dp))
                DlgCaption(
                    "Read live through the root shell and never indexed, so it is slow. " +
                        "Not part of This phone.",
                )
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                ReachSheetModel.confirmLabel(ticked.size),
                kind = BtnKind.PRIMARY,
                // Nothing ticked is not a search. Disabled rather than accepted and answered with
                // an empty list, which would read as the drives having nothing in them.
                enabled = ticked.isNotEmpty(),
            ) { onPick(Reach.Drives(ticked)) }
        }
    }
}

/** The sheet's decisions, pure, so what it opens with and what it offers are a test. */
internal object ReachSheetModel {

    /**
     * What the sheet opens with ticked: what the current reach already covers.
     *
     * Opening on the current reach rather than on nothing makes the sheet a refinement of what
     * is on screen - untick one share, keep the rest - instead of a form to fill in from blank.
     */
    fun ticked(current: Reach, drives: List<ReachDrive>): Set<VolumeKey> = when (current) {
        is Reach.Drives -> current.keys
        // Everywhere is every drive except the system ones, which no rung includes.
        Reach.Everywhere -> drives.filterNot { it.system }.map { it.key }.toSet()
        // A tree-shaped reach sits on one drive: open with that drive, or the phone from Home.
        Reach.Folder, Reach.Subtree -> ReachLadder.phoneKeys(drives)
    }

    /** Local, shares, system - in that order, which is the order of how much each costs to ask. */
    fun sections(drives: List<ReachDrive>): Triple<List<ReachDrive>, List<ReachDrive>, List<ReachDrive>> =
        Triple(
            drives.filter { !it.remote && !it.system },
            drives.filter { it.remote },
            drives.filter { it.system },
        )

    fun confirmLabel(n: Int): String = when (n) {
        0 -> "Search"
        1 -> "Search 1 drive"
        else -> "Search $n drives"
    }
}
