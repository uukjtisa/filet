package dev.niccc2007.filet.handlers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.niccc2007.filet.browser.AppPick
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet

/**
 * One app row. Used by both tiers so the two lists cannot drift apart visually.
 *
 * @param note the second line. Declared types for a second-tier row, the package name for a
 *   first-tier one; in both cases the thing that tells two similarly-named apps apart.
 */
@Composable
private fun AppRow(app: ExternalApp, note: String, onClick: () -> Unit) {
    val colors = Filet.colors
    val context = LocalContext.current
    val icon = remember(app) { ExternalApps.icon(context, app) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Icon(FiletIcons.Open, null, tint = colors.fg3, modifier = Modifier.size(28.dp).padding(5.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                note,
                fontSize = 9.5.sp, color = colors.fg3,
                fontFamily = if (note == app.packageName) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The line that opens the second tier. */
@Composable
private fun AllAppsRow(onClick: () -> Unit) {
    Text(
        "All apps on this phone",
        fontSize = 12.5.sp,
        color = Filet.colors.accent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The divider between "these declared they can" and "everything else". */
@Composable
private fun TierHeader(sub: String) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 3.dp)) {
        Text("EVERYTHING ELSE", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.fg3)
        Text(sub, fontSize = 9.5.sp, color = colors.fg3, lineHeight = 12.5.sp)
    }
}

/**
 * Filet's own "open with", listing the installed apps that can take this file.
 *
 * This exists instead of `Intent.createChooser` for one reason: the system chooser never
 * reports what the user picked, so nothing could be remembered and "hand to another app"
 * asked every single time. See [ExternalApps].
 *
 * **The toggle is visible and it starts OFF.** Round 5 shipped it pre-ticked, reasoning that a
 * setting you have to go and find is one you never find. That was wrong: it wrote a permanent
 * routing default every time somebody opened one file in one app once, which is the thing Nic
 * asked it not to do. Opening a file is not a policy decision unless you say it is, so the
 * checkbox states what will happen and waits to be ticked. See [remembersByDefault].
 */
@Composable
fun AppPickerSheet(vm: BrowserViewModel, pick: AppPick) {
    val colors = Filet.colors
    val context = LocalContext.current
    val ext = pick.node.extension
    // Decided already when the user came through "Open with ▸ Another app" and answered
    // Always or Just once there; asking the same question twice in two sheets is worse than
    // either answer.
    var always by remember(pick.node.path) {
        mutableStateOf(pick.forceRemember ?: remembersByDefault())
    }
    val askAlways = pick.forceRemember == null && ext.isNotEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { vm.dismissAppPicker() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(bottom = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Open with", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        pick.node.name,
                        fontSize = 10.5.sp, color = colors.fg3, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    FiletIcons.Close, "Close", tint = colors.fg2,
                    modifier = Modifier.size(26.dp).clickable { vm.dismissAppPicker() }.padding(5.dp),
                )
            }

            if (askAlways) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { always = !always }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (always) colors.accent else Color.Transparent)
                            .then(
                                if (always) Modifier
                                else Modifier.background(colors.high)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (always) {
                            Icon(
                                FiletIcons.Check, null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Always use this for .$ext files",
                        fontSize = 12.sp,
                        color = if (always) MaterialTheme.colorScheme.onSurface else colors.fg2,
                    )
                }
                Text(
                    if (always) "Changeable later in Settings ▸ Default openers."
                    else "This time only. Filet will ask again next time.",
                    fontSize = 10.sp, color = colors.fg3,
                    modifier = Modifier.padding(start = 43.dp, end = 16.dp, bottom = 4.dp),
                )
            } else if (always && ext.isNotEmpty()) {
                Text(
                    "Will become the default for .$ext — Settings ▸ Default openers.",
                    fontSize = 10.5.sp, color = colors.accent,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                )
            }

            var showAll by remember(pick.node.path) { mutableStateOf(false) }
            val rest = remember(pick.apps, showAll) {
                if (showAll) ExternalApps.allLaunchable(context, pick.apps) else emptyList()
            }
            val types = remember(showAll) {
                if (showAll) ExternalApps.declaredTypes(context) else emptyMap()
            }

            // Capped, and scrollable past the cap. A device with thirty image viewers should
            // not produce a sheet taller than the screen with the rest off the bottom.
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(pick.apps, key = { "d/" + it.packageName + "/" + it.activity }) { app ->
                    AppRow(app, app.detail) { vm.openWithApp(pick.node, app, always) }
                }
                if (!showAll) {
                    item { AllAppsRow { showAll = true } }
                } else {
                    item {
                        TierHeader(
                            "These did not claim this file type. One may still open it, and " +
                                "one may refuse. Filet drops the default if it does."
                        )
                    }
                    items(rest, key = { "a/" + it.packageName }) { app ->
                        val note = types[app.packageName]?.joinToString(", ")
                            ?: "declares no file types"
                        AppRow(app, note) { vm.openWithApp(pick.node, app, always) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * The same list, asked from Settings where there is no file in hand.
 *
 * **In a real Dialog window, not a Box.** The first cut was a `Box(fillMaxSize)` emitted from
 * `SettingsPage` right after its `LazyColumn(fillMaxSize)`. Two siblings, and the list had
 * already taken every pixel - so the sheet composed, laid out at zero height, and tapping
 * "Another app" appeared to do nothing at all. A Dialog is its own window and does not
 * negotiate with the parent's layout, which is the only reason the AlertDialog beside it
 * worked. Same shape of bug as the sidebar tap that never fired: the code was right and the
 * thing underneath it was not what it looked like.
 *
 * @param onPick null means the user backed out without choosing.
 */
@Composable
fun AppPickerForExtension(
    extension: String,
    apps: List<ExternalApp>,
    onPick: (ExternalApp?) -> Unit,
) {
    Dialog(
        onDismissRequest = { onPick(null) },
        // Otherwise the platform caps the window at a phone-dialog width and the sheet is a
        // narrow column floating in the middle of the screen.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AppPickerBody(extension, apps, onPick)
    }
}

@Composable
private fun AppPickerBody(
    extension: String,
    apps: List<ExternalApp>,
    onPick: (ExternalApp?) -> Unit,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onPick(null) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(bottom = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Which app opens .$extension", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (apps.isEmpty()) "Nothing on this device handles this type."
                        else "A single tap on a .$extension file will go straight here.",
                        fontSize = 10.5.sp, color = colors.fg3,
                    )
                }
                Icon(
                    FiletIcons.Close, "Close", tint = colors.fg2,
                    modifier = Modifier.size(26.dp).clickable { onPick(null) }.padding(5.dp),
                )
            }
            var showAll by remember(extension) { mutableStateOf(false) }
            val rest = remember(apps, showAll) {
                if (showAll) ExternalApps.allLaunchable(context, apps) else emptyList()
            }
            val types = remember(showAll) {
                if (showAll) ExternalApps.declaredTypes(context) else emptyMap()
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { "d/" + it.packageName + "/" + it.activity }) { app ->
                    AppRow(app, app.detail) { onPick(app) }
                }
                if (!showAll) {
                    item { AllAppsRow { showAll = true } }
                } else {
                    item {
                        TierHeader(
                            "These did not claim this type. One may still open it, and one " +
                                "may refuse. Filet drops the default if it does."
                        )
                    }
                    items(rest, key = { "a/" + it.packageName }) { app ->
                        val note = types[app.packageName]?.joinToString(", ")
                            ?: "declares no file types"
                        AppRow(app, note) { onPick(app) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
