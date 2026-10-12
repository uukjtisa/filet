package dev.niccc2007.filet.handlers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.AppPick
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.theme.Filet

/**
 * "Which installed app opens this" - one dialogue, two ways in.
 *
 * ## The stale sheet this replaces
 *
 * Reported directly: *"the open with on the home menu is different from the open with of the
 * main file list... there's a stale open with menu still appearing when it was already removed
 * back then."* Both halves were true.
 *
 * [OpenWithDialog] was rebuilt on the dialogue kit a round earlier - a card with an icon
 * header, [DlgPick] rows and a [DlgTick] whose whole row is the target. The app picker next to
 * it was not. It was still a hand-rolled bottom sheet: its own scrim, its own 18dp top corners,
 * a 17dp checkbox floating beside a label in a different place, its own close cross, and no
 * footer at all. Two "Open with" surfaces, one from each era, and which one you got depended on
 * whether the file had a Filet viewer - so the Home row's menu and the file list's Open with
 * genuinely looked like two different apps.
 *
 * And there were THREE copies of the list, not two: the same rows were written again for the
 * Settings route in `AppPickerForExtension`, with different paddings.
 *
 * Now there is one body. [AppPickerBody] draws it, [AppPickerDialog] is the file route and
 * [AppPickerForExtension] is the Settings route, and the only difference between them is
 * whether there is a file to open or a type to configure - which is a tick and a footer
 * button, not a second dialogue.
 *
 * ## What survives from the sheet, deliberately
 *
 * The two tiers. "These declared they can open this" and "everything else launchable" are
 * genuinely different claims, and collapsing them would hand somebody a list of 180 apps for a
 * `.jpg`. The second tier is built lazily, off the main thread - see [OpenerCatalog]; building
 * it eagerly is what made the old sheet take seconds to appear.
 */
@Composable
fun AppPickerDialog(vm: BrowserViewModel, pick: AppPick) {
    val ext = pick.node.extension
    // Decided already when the user came through "Open with ▸ Another app" and answered there;
    // asking the same question twice in two dialogues is worse than either answer.
    var always by remember(pick.node.path) {
        mutableStateOf(pick.forceRemember ?: remembersByDefault())
    }
    val askAlways = pick.forceRemember == null && ext.isNotEmpty()

    AppPickerBody(
        title = "Open with",
        sub = "${pick.node.name}  ·  ${humanSize(pick.node.size)}",
        apps = pick.apps,
        emptyFirstTier = "Nothing on this phone claimed .$ext.",
        onDismiss = { vm.dismissAppPicker() },
        tick = when {
            askAlways -> AlwaysTick(
                on = always,
                label = "Always open .$ext with the app I pick",
                note = if (always) "Changeable later in Settings ▸ Default openers."
                else "This time only. Filet will ask again next time.",
                onToggle = { always = !always },
            )
            // Already answered upstream. Stated rather than asked, so the decision is visible
            // in the dialogue that acts on it.
            always && ext.isNotEmpty() -> AlwaysTick(
                on = true,
                label = "Will become the default for .$ext",
                note = "Settings ▸ Default openers.",
                onToggle = null,
            )
            else -> null
        },
    ) { app -> vm.openWithApp(pick.node, app, always) }
}

/**
 * The same list, asked from Settings where there is no file in hand.
 *
 * It used to be its own `Dialog(usePlatformDefaultWidth = false)` wrapped round a copy of the
 * sheet, because the first cut was a `Box(fillMaxSize)` emitted as a sibling of a
 * `LazyColumn(fillMaxSize)` - the list had already taken every pixel, so the sheet composed,
 * laid out at zero height, and tapping "Another app" appeared to do nothing at all. [Dlg] is
 * its own window and has the same immunity, so the wrapper is gone with the copy.
 *
 * @param onPick null means the user backed out without choosing.
 */
@Composable
fun AppPickerForExtension(
    extension: String,
    apps: List<ExternalApp>,
    onPick: (ExternalApp?) -> Unit,
) {
    AppPickerBody(
        title = "Which app opens .$extension",
        sub = if (apps.isEmpty()) "Nothing here declared this type."
        else "A single tap goes straight here.",
        apps = apps,
        emptyFirstTier = "Nothing on this phone claimed .$extension.",
        onDismiss = { onPick(null) },
        tick = null,
    ) { app -> onPick(app) }
}

/**
 * The tick above the footer, when there is a routing decision to make.
 *
 * @param onToggle null for a decision already taken elsewhere, which is stated and not asked.
 */
private class AlwaysTick(
    val on: Boolean,
    val label: String,
    val note: String,
    val onToggle: (() -> Unit)?,
)

@Composable
private fun AppPickerBody(
    title: String,
    sub: String,
    apps: List<ExternalApp>,
    emptyFirstTier: String,
    onDismiss: () -> Unit,
    tick: AlwaysTick?,
    onPick: (ExternalApp) -> Unit,
) {
    val context = LocalContext.current
    var showAll by remember(apps) { mutableStateOf(false) }

    // produceState, not remember: building the second tier is a hundred-odd inter-process
    // calls, and `remember` runs during composition on the main thread. See OpenerCatalog.
    val catalog by produceState(OpenerCatalog.Catalog(), showAll, apps) {
        value = if (showAll) OpenerCatalog.get(context, apps) else OpenerCatalog.Catalog()
    }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Open,
            title = title,
            sub = sub,
            onClose = onDismiss,
        )
        DlgBody {
            if (apps.isEmpty()) {
                // Not a dead end. An extension nothing on the device DECLARES - `.mcaddon`,
                // say - resolves to the wildcard mime, which the candidate query deliberately
                // answers with nothing. Saying "no app for .mcaddon" states a fact Filet does
                // not have: Minecraft opens one perfectly well, it simply never registered the
                // type. So the empty tier is named and everything launchable is under it.
                DlgCaption(emptyFirstTier)
            }
            for (app in apps) {
                AppRow(app, app.detail) { onPick(app) }
            }

            if (!showAll) {
                DlgPick(
                    icon = FiletIcons.More,
                    label = "All apps on this phone",
                    detail = "Including ones that did not claim this type",
                ) { showAll = true }
            } else {
                DlgSection("Everything else")
                DlgCaption(
                    "These did not claim this file type. One may still open it, and one may " +
                        "refuse — Filet drops the default if it does.",
                )
                for (app in catalog.launchable) {
                    AppRow(
                        app,
                        catalog.declaredTypes[app.packageName]?.joinToString(", ")
                            ?: "declares no file types",
                    ) { onPick(app) }
                }
            }

            if (tick != null) {
                Spacer(Modifier.height(10.dp))
                // The specific complaint about the old "always open with this" checkbox: it
                // was a 17dp box floating beside a label, the two in different places and only
                // the box a target. DlgTick makes the whole row the target and the whole row
                // show the state.
                DlgTick(
                    on = tick.on,
                    label = tick.label,
                    enabled = tick.onToggle != null,
                ) { tick.onToggle?.invoke() }
                DlgCaption(tick.note)
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            // No "Open" button: the row IS the commit here, because picking an app is picking
            // the thing that happens. A second button would have nothing left to do, which is
            // the same reasoning OpenWithDialog applies to its Settings route.
            DlgSpacer()
        }
    }
}

/**
 * One app row: its real launcher icon, its label, and the line that tells two alike apart.
 *
 * Not [DlgPick], and the reason is the icon. A `DlgPick` tile holds a vector tinted to the
 * theme, which is exactly wrong for an app: a monochrome WhatsApp is not recognisable, and
 * recognising the icon is how anybody actually picks from this list. Everything else - the
 * 30dp tile, the 11dp radius, the paddings, the type sizes - is `DlgPick`'s, so the two kinds
 * of row sit in one list without a seam.
 *
 * @param note the second line. The package name for a first-tier row, the declared types for a
 *   second-tier one.
 */
@Composable
private fun AppRow(app: ExternalApp, note: String, onClick: () -> Unit) {
    val colors = Filet.colors
    val context = LocalContext.current
    val icon = remember(app) { ExternalApps.icon(context, app) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, null, modifier = Modifier.size(26.dp))
            } else {
                Icon(FiletIcons.Open, null, tint = colors.fg2, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                note,
                fontSize = 10.sp,
                color = colors.fg3,
                fontFamily = if (note == app.packageName) FontFamily.Monospace
                else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
