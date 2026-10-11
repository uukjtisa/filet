package dev.niccc2007.filet.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.data.SettingsTransfer
import dev.niccc2007.filet.data.SettingsTransfer.Section
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Choosing what goes into a settings file, and what comes out of one.
 *
 * ## Why both ends ask
 *
 * The obvious shape is one button that writes everything and one that reads everything back.
 * It is wrong at both ends, for different reasons.
 *
 * Exporting everything means a file that quietly contains the folders this phone shares -
 * and, if it also carried passwords, a credential dump sitting in Download. Choosing what
 * goes in is what lets somebody hand the file to a friend who just wants their theme.
 *
 * Importing everything means a setup replaced without being seen. The import sheet lists what
 * is actually in the file, with counts, **before** anything is written, because an import
 * that silently overwrote a bookmark list is something people find a week later and cannot
 * undo.
 */
@Composable
fun ExportSheet(
    /** Sections offered, with how many entries each would carry from this phone. */
    counts: Map<Section, Int>,
    onExport: (Set<Section>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember {
        mutableStateOf(Section.entries.filter { it.onByDefault && (counts[it] ?: 0) > 0 }.toSet())
    }
    var secrets by remember { mutableStateOf(false) }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Share, "Export settings", onClose = onDismiss)
        DlgBody {
            DlgCaption("Pick what travels. The file is plain text you can read.")
            Spacer(Modifier.height(10.dp))
            for (section in Section.entries) {
                val n = counts[section] ?: 0
                // A section with nothing in it is offered GREYED rather than hidden, so its
                // absence from the file reads as "there was none of this" instead of as the
                // feature having missed it.
                DlgTick(
                    on = section in picked && n > 0,
                    label = if (n > 0) "${section.label}  ·  $n" else "${section.label}  ·  none",
                    sub = section.about,
                    enabled = n > 0,
                ) { picked = if (section in picked) picked - section else picked + section }
                Spacer(Modifier.height(6.dp))
            }
            if (Section.REMOTES in picked) {
                Spacer(Modifier.height(4.dp))
                DlgTick(
                    on = secrets,
                    label = "Include passwords",
                    sub = "Written into the file in plain text. A stored password is " +
                        "encrypted with a key that never leaves this phone, so it cannot be " +
                        "copied across any other way.",
                ) { secrets = !secrets }
                if (secrets) {
                    Spacer(Modifier.height(8.dp))
                    DlgWarn(
                        "Anyone who opens this file can read those passwords. Delete it once " +
                            "the other phone has them.",
                        bad = true,
                    )
                }
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Choose where", kind = BtnKind.PRIMARY, enabled = picked.isNotEmpty()) {
                onExport(picked, secrets)
            }
        }
    }
}

/**
 * What is in the file, before any of it is applied.
 *
 * The version and the file name are shown because the commonest mistake with a settings file
 * is importing the wrong one: an older export of the same phone looks identical to a new one
 * in a file listing.
 */
@Composable
fun ImportSheet(
    from: String,
    reading: SettingsTransfer.Reading.Ok,
    onImport: (Set<Section>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(from) {
        mutableStateOf(reading.counts.filterValues { it > 0 }.keys.toSet())
    }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Open, "Import settings", onClose = onDismiss)
        DlgBody {
            DlgCaption("Written by Filet ${reading.app} · $from")
            Spacer(Modifier.height(10.dp))
            if (reading.counts.values.none { it > 0 }) {
                Text(
                    "There is nothing in this file that this version of Filet can use.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Filet.colors.fg2,
                )
            }
            for ((section, n) in reading.counts) {
                DlgTick(
                    on = section in picked && n > 0,
                    label = "${section.label}  ·  $n",
                    sub = section.about,
                    enabled = n > 0,
                ) { picked = if (section in picked) picked - section else picked + section }
                Spacer(Modifier.height(6.dp))
            }
            if (reading.containsPasswords) {
                Spacer(Modifier.height(4.dp))
                DlgWarn("This file carries network drive passwords in plain text.")
            }
            if (reading.unknownKeys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Said rather than swallowed. A file from a newer Filet is imported for the
                // parts this build understands; refusing the whole thing over one unknown key
                // is the behaviour that stops people using the feature.
                DlgCaption(
                    "${reading.unknownKeys.size} setting(s) here come from a different version " +
                        "of Filet and will be left alone.",
                )
            }
            Spacer(Modifier.height(8.dp))
            DlgCaption(
                "What you pick replaces what is on this phone now. Folders granted through " +
                    "the system picker have to be granted again here.",
            )
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Import", kind = BtnKind.PRIMARY, enabled = picked.isNotEmpty()) {
                onImport(picked)
            }
        }
    }
}
