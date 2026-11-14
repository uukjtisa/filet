package dev.niccc2007.filet.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.niccc2007.filet.browser.FileThumb
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.LocalSampleInside
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.browser.tabGlyph
import dev.niccc2007.filet.icons.AppIconSet
import dev.niccc2007.filet.icons.AppIconSwitch
import dev.niccc2007.filet.icons.FolderDrawing
import dev.niccc2007.filet.icons.IconStyle
import dev.niccc2007.filet.icons.IconStyles
import dev.niccc2007.filet.icons.Inside
import dev.niccc2007.filet.icons.LegacyLooks
import dev.niccc2007.filet.icons.LocalPlaceLook
import dev.niccc2007.filet.icons.PlaceLook
import dev.niccc2007.filet.icons.label
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.tabs.HintInfo
import dev.niccc2007.filet.ui.tabs.HoldHint
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * Settings › Appearance › Legacy icons: the line icons from before the icon rework, a choice for
 * each place, and Filet's own app icon. See the Legacy icons mock and its spec in docs/design.
 *
 * Writes only `look.legacy.v1` (and the app icon, which Android holds); never a folder's record.
 */
internal fun AnchoredList.legacyIconsSection() {
    item { SettingsSection("Legacy icons", "") }
    anchor(LegacyLooks.KEY)
    item { LegacySample() }
    item {
        LookRow("Tabs", TWO, { it.tabs.ordinal }, "The tab chips along the top and the list on Settings › Bottom bar. The bar itself never changes.") { s, i ->
            s.copy(tabs = IconStyle.entries[i])
        }
    }
    item { GroupLabel("Files and folders", "Every list: the folder you are in, search results, pickers. Home never changes.") }
    item {
        LookRow("Folders", FOLDERS, { it.files.folders.ordinal }, FOLDERS_HINT, sub = true) { s, i ->
            s.copy(files = s.files.copy(folders = FolderDrawing.entries[i]))
        }
    }
    item {
        LookRow("Files", TWO, { it.files.files.ordinal }, FILES_HINT, sub = true) { s, i ->
            s.copy(files = s.files.copy(files = IconStyle.entries[i]))
        }
    }
    item { GroupLabel("Gallery", "The grid views and the category galleries.") }
    item {
        LookRow("Folders", FOLDERS, { it.gallery.folders.ordinal }, "Folders in the grid views. $FOLDERS_HINT", sub = true) { s, i ->
            s.copy(gallery = s.gallery.copy(folders = FolderDrawing.entries[i]))
        }
    }
    item {
        LookRow("Files", TWO, { it.gallery.files.ordinal }, "Files with no picture, in the grids and galleries.", sub = true) { s, i ->
            s.copy(gallery = s.gallery.copy(files = IconStyle.entries[i]))
        }
    }
    item {
        LookRow("Shortcuts tab", TWO, { it.shortcuts.ordinal }, "The icons beside each row on the Shortcuts tab. Home-screen shortcuts never change.") { s, i ->
            s.copy(shortcuts = IconStyle.entries[i])
        }
    }
    anchor(AppIconSwitch.EXPORT_KEY)
    item { AppIconRow() }
}

private val TWO = listOf("Drawn", "Legacy")

/** In [FolderDrawing]'s order. */
private val FOLDERS = listOf("Drawn", "Drawn, whole colour", "Legacy", "Legacy outline")

private const val FOLDERS_HINT = "Drawn: today. Drawn, whole colour: a folder with a colour wears it all over, " +
    "its emblem white on top; Filet blue looks the same. Legacy: the folder from just before the rework, with " +
    "its pictures. Legacy outline: the bare outline before that, with no emblem and no colour. A folder set " +
    "otherwise in Change icon keeps its own."

private const val FILES_HINT = "For files with no picture of their own. Legacy: the kind's line icon, or the " +
    "extension as text when Filet has no icon for the kind. Pictures, video frames and album art never change."

@Composable
private fun rememberLooks(): Pair<LegacyLooks, IconStyles> {
    val store = LegacyLooks.of(LocalContext.current)
    val styles by store.styles.collectAsState()
    return store to styles
}

@Composable
private fun LookRow(
    label: String,
    options: List<String>,
    selected: (IconStyles) -> Int,
    hint: String,
    sub: Boolean = false,
    change: (IconStyles, Int) -> IconStyles,
) {
    val (store, styles) = rememberLooks()
    SegmentRow(label, options, selected(styles), sub = sub, fresh = true, hint = hint, key = LegacyLooks.KEY) {
        store.set(change(styles, it))
    }
}

/** `.grouplabel`: a group's name, with its hint. */
@Composable
private fun GroupLabel(text: String, hint: String) = HoldHint(hint, LegacyLooks.KEY) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, color = Filet.colors.fg2)
        Spacer(Modifier.width(4.dp))
        HintInfo()
    }
}

/** A folder and a file standing nowhere, for the sample. Never read from a drive. */
private val SAMPLE_FOLDER = VNode(VPath("sample", "/Music"), isDir = true, size = -1, mtime = 0L)
private val SAMPLE_FILE = VNode(VPath("sample", "/Trail map.pdf"), isDir = false, size = 685_056L, mtime = 0L)

/** `.lpreview`: Tab, Folder, File, Gallery and Shortcut, each as the choices make it, live. */
@Composable
private fun LegacySample() {
    val colors = Filet.colors
    val (_, styles) = rememberLooks()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.Bottom,
    ) {
        Sample("Tab") {
            if (styles.tabs == IconStyle.LEGACY) {
                Icon(tabGlyph(TabTarget.PLAYLISTS), null, tint = colors.accent, modifier = Modifier.size(13.dp))
            } else {
                dev.niccc2007.filet.shortcuts.TabArt(TabTarget.PLAYLISTS.art, 19.dp)
            }
        }
        Sample("Folder") { Thing(SAMPLE_FOLDER, styles.files, 30.dp) }
        Sample("File") { Thing(SAMPLE_FILE, styles.files, 30.dp) }
        Sample("Gallery") { Thing(SAMPLE_FOLDER, styles.gallery, 34.dp) }
        Sample("Shortcut") {
            if (styles.shortcuts == IconStyle.LEGACY) {
                dev.niccc2007.filet.shortcuts.GlyphTile(tabGlyph(TabTarget.PLAYLISTS))
            } else {
                dev.niccc2007.filet.shortcuts.ArtTile(TabTarget.PLAYLISTS.art)
            }
        }
    }
}

@Composable
private fun Sample(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        Text(label, fontSize = 9.5.sp, color = Filet.colors.fg3)
    }
}

@Composable
private fun Thing(node: VNode, look: PlaceLook, size: Dp) {
    CompositionLocalProvider(
        LocalPlaceLook provides look,
        LocalSampleInside provides { n -> if (n.isDir) Inside.Pages else null },
    ) {
        FileThumb(node = node, size = size, fallbackTint = Filet.colors.fg2)
    }
}

// ── the app icon ──

/** The App icon row: the icon on now, and the picker. */
@Composable
private fun AppIconRow() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(AppIconSwitch.current(context)) }
    var picking by remember { mutableStateOf(false) }
    HoldHint("Filet's own icon on the home screen and in the app list. Switching it moves the icon on your home screen.", AppIconSwitch.EXPORT_KEY) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { picking = true }
                .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherIcon(now, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("App icon", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    NewTag()
                    HintInfo()
                }
                Text(if (debugBuild) designName(now) else now.label(), fontSize = 11.sp, color = Filet.colors.fg3)
            }
            Icon(FiletIcons.Chevron, null, tint = Filet.colors.fg3, modifier = Modifier.size(16.dp))
        }
    }
    if (picking) {
        AppIconPicker(now, onDismiss = { picking = false }) { to ->
            AppIconSwitch.switchTo(context, to)
            now = AppIconSwitch.current(context)
            picking = false
        }
    }
}

/** Filet Debug keeps its amber in every design, so only the design switches there. */
private val debugBuild: Boolean get() = dev.niccc2007.filet.BuildConfig.DEBUG

private fun designName(c: AppIconSwitch.Choice) = AppIconSet.DESIGNS.firstOrNull { it.first == c.design }?.second ?: c.design

/** The palette colour each icon colour is named for, for its swatch. */
private val SWATCH = mapOf("slate" to 0xFF4E7382, "ember" to 0xFFAC5C1E, "moss" to 0xFF4C7A4E, "ink" to 0xFF6A6A67)

/**
 * The picker: the icon now and the one picked, the designs six across, the four colours, two
 * captions, and Cancel and Switch. Switch does nothing until something changes.
 */
@Composable
private fun AppIconPicker(now: AppIconSwitch.Choice, onDismiss: () -> Unit, onSwitch: (AppIconSwitch.Choice) -> Unit) {
    val colors = Filet.colors
    var design by remember { mutableStateOf(now.design) }
    var colour by remember { mutableStateOf(now.colour) }
    val picked = AppIconSwitch.Choice(design, colour)
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Cog, "App icon", onClose = onDismiss)
        DlgBody {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Preview(now, "Now")
                Preview(picked, if (picked == now) "Picked" else "After switching")
            }
            Spacer(Modifier.height(12.dp))
            Text("Design", fontSize = 11.5.sp, color = colors.fg2)
            Spacer(Modifier.height(6.dp))
            for (row in AppIconSet.DESIGNS.chunked(6)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((d, name) in row) {
                        val on = d == design
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                                .border(1.dp, if (on) colors.accent else colors.lineSoft, RoundedCornerShape(10.dp))
                                .clickable { design = d }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LauncherIcon(AppIconSwitch.Choice(d, colour), 34.dp)
                            Text(name, fontSize = 9.5.sp, color = if (on) colors.accent else colors.fg3, maxLines = 1)
                        }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!debugBuild) {
                Spacer(Modifier.height(6.dp))
                Text("Colour", fontSize = 11.5.sp, color = colors.fg2)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((c, name) in AppIconSet.COLOURS) {
                        val on = c == colour
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, if (on) colors.accent else colors.lineSoft, RoundedCornerShape(50))
                                .clickable { colour = c }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(SWATCH.getValue(c))))
                            Spacer(Modifier.width(6.dp))
                            Text(name, fontSize = 11.5.sp, color = if (on) colors.accent else colors.fg2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            DlgCaption("Switching moves the icon: your launcher takes it off its spot, and it comes back in the app list or at the end of your last home screen.")
            if (debugBuild) DlgCaption("Filet Debug keeps its amber in every design, so the colour shows on Filet only.")
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Switch", kind = BtnKind.PRIMARY, enabled = picked != now, onClick = { onSwitch(picked) })
        }
    }
}

@Composable
private fun Preview(c: AppIconSwitch.Choice, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LauncherIcon(c, 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(if (debugBuild) designName(c) else c.label(), fontSize = 12.sp, color = Filet.colors.fg2)
        Text(caption, fontSize = 10.sp, color = Filet.colors.fg3)
    }
}

/** An app icon as the launcher draws it, from its adaptive icon. */
@Composable
private fun LauncherIcon(c: AppIconSwitch.Choice, size: Dp) {
    val context = LocalContext.current
    val res = AppIconSet.ICON[c.key] ?: return
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(res, px) {
        runCatching { context.getDrawable(res)?.toBitmap(px, px)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, null, Modifier.size(size))
}
