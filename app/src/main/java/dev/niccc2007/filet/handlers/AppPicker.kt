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
        title = "Open file type \".$extension\" with.",
        sub = if (apps.isEmpty()) "Current list of apps doesn't know this file type."
        else "Current list of apps that support the file type \".$extension\"",
        apps = apps,
        emptyFirstTier = "Consider opening the full list of apps instead.",
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

    // Started the moment the dialogue opens, not when "All apps" is tapped: by the time
    // somebody has read the first tier and reached for the button, the second is usually built
    // already, so the tap is instant. Null while building, and the tap then shows a spinner
    // rather than an empty section that reads as "there are no other apps". produceState, not
    // remember: the build is a hundred-odd inter-process calls. See OpenerCatalog.
    val catalog by produceState<OpenerCatalog.Catalog?>(null, apps) {
        value = OpenerCatalog.get(context, apps)
    }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Open,
            title = title,
            sub = sub,
            onClose = onDismiss,
        )
        // A lazy list, so only the rows on screen are built: the second tier is the whole phone,
        // and composing every row of it at once is what made the tap take seconds.
        DlgBody(scroll = false) {
          androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
            if (apps.isEmpty()) item {
                // Not a dead end. An extension nothing on the device DECLARES - `.mcaddon`,
                // say - resolves to the wildcard mime, which the candidate query deliberately
                // answers with nothing. Saying "no app for .mcaddon" states a fact Filet does
                // not have: Minecraft opens one perfectly well, it simply never registered the
                // type. So the empty tier is named and everything launchable is under it.
                DlgCaption(emptyFirstTier)
            }
            items(apps.size, key = { "a:$it:" + apps[it].component.flattenToString() }) { i ->
                AppRow(apps[i], apps[i].detail) { onPick(apps[i]) }
            }

            val built = catalog
            if (!showAll) {
                item(key = "all") {
                    DlgPick(
                        icon = FiletIcons.More,
                        label = "Show all apps on this phone",
                        detail = "Open the full list of apps.",
                    ) { showAll = true }
                }
            } else {
                item(key = "else") {
                    Column {
                        DlgSection("All apps")
                        DlgCaption(
                            "Choose an app for this file type."
                        )
                    }
                }
                if (built == null) {
                    // Feedback that it is working rather than hung: the list of every app is
                    // read from the system, and on a full phone that is not instant.
                    item(key = "reading") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            DlgCaption("Reading every app on this phone. Please wait.")
                        }
                    }
                } else {
                    items(built.launchable.size, key = { "l:$it:" + built.launchable[it].component.flattenToString() }) { i ->
                        val app = built.launchable[i]
                        AppRow(
                            app,
                            built.declaredTypes[app.packageName]?.joinToString(", ")
                                ?: "declares no file types",
                        ) { onPick(app) }
                    }
                }
            }

            if (tick != null) item(key = "tick") { Column {
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
            } }
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
    // Off the main thread and cached: decoding a launcher icon is a binder call and a bitmap
    // draw, and doing it in composition for every row is the other half of the slow list. The
    // glyph stands in for the frame or two it takes.
    val icon by produceState(AppIcons.peek(app), app) {
        if (value == null) value = AppIcons.load(context, app)
    }
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
            val bmp = icon
            if (bmp != null) {
                Image(bmp, null, modifier = Modifier.size(26.dp))
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

/** Launcher icons, decoded once per app per process, off the main thread. */
internal object AppIcons {
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(256)

    private fun keyOf(app: ExternalApp) = app.component.flattenToString()

    fun peek(app: ExternalApp): androidx.compose.ui.graphics.ImageBitmap? = cache.get(keyOf(app))

    suspend fun load(context: android.content.Context, app: ExternalApp): androidx.compose.ui.graphics.ImageBitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            peek(app) ?: ExternalApps.icon(context, app)?.also { cache.put(keyOf(app), it) }
        }
}
