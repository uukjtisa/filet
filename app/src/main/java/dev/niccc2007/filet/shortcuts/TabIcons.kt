package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.R
import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.home.MediaCategory
import dev.niccc2007.filet.icons.FolderLook
import dev.niccc2007.filet.icons.FolderStyles
import dev.niccc2007.filet.icons.Inside
import dev.niccc2007.filet.icons.drawFolder
import dev.niccc2007.filet.icons.drawTypeIcon
import dev.niccc2007.filet.icons.lookFor
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes

// The tab icons in the app and on the home screen. The drawings are generated from the tab icon
// mock by tools/make-tab-icons.mjs; see TabIconRes.

/** The home-screen icon for a tab or action, by its id in the tab icon mock. Filet's own for anything else. */
fun launcherIcon(art: String?): Int = art?.let { TabIconRes.LAUNCHER[it] } ?: R.mipmap.ic_launcher

/** The art alone, on its 108-unit canvas, or null for an id the mock does not have. */
fun artDrawable(art: String?): Int? = art?.let { TabIconRes.ART[it] }

/** The art a shortcut record is drawn with: its tab's, its action's, the Scripts tab's for a script. */
fun recordArt(record: ShortcutRecord): String? = when (record.kind) {
    ShortcutKind.TAB -> TabTarget.byId(record.target)?.art
    ShortcutKind.ACTION -> AppAction.parse(record.target)?.art
    ShortcutKind.SCRIPT -> TabTarget.SCRIPTS.art
    ShortcutKind.FILE -> null
}

/** The drawing for a media category: its tab's. */
fun categoryArt(category: MediaCategory): String? = TabTarget.entries.firstOrNull { it.category == category }?.art

/** The tab a pane is showing, for its icon: null for a folder, and for a pane with no tab of its own. */
fun tabOfPane(kind: PaneKind, category: MediaCategory?): TabTarget? =
    if (kind == PaneKind.FOLDER) null
    else TabTarget.entries.firstOrNull { it.kind == kind && (it.category == null || it.category == category) }

/**
 * The art on no tile, its middle 60 units filling [size]: how a tab chip draws it. The tile's
 * margin is what a launcher's mask eats, and on a chip it would only make the drawing smaller.
 */
@Composable
fun TabArt(art: String, size: Dp, modifier: Modifier = Modifier) {
    val res = artDrawable(art) ?: return
    Box(modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.requiredSize(size * (108f / 60f)))
    }
}

/**
 * The icon as a home screen shows it, on a small rounded tile: Filet's tile and the art, the 72
 * units a launcher shows of the 108. For the Shortcuts tab's rows.
 */
@Composable
fun ArtTile(art: String, side: Dp = 30.dp) {
    val res = artDrawable(art)
    val shape = RoundedCornerShape(side * 0.27f)
    Box(
        Modifier.size(side).clip(shape).border(1.dp, Filet.colors.lineSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        val full = side * (108f / 72f)
        Image(painterResource(R.drawable.ic_tab_bg), contentDescription = null, modifier = Modifier.requiredSize(full))
        if (res != null) Image(painterResource(res), contentDescription = null, modifier = Modifier.requiredSize(full))
    }
}

/**
 * A shortcut record's tile: its tab's or action's icon, or for a file, the folder or the file type
 * as the list draws it.
 */
@Composable
fun RecordTile(record: ShortcutRecord) {
    recordArt(record)?.let { ArtTile(it); return }
    val path = runCatching { VPath.parse(record.target) }.getOrNull()
    val colors = Filet.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(30.dp).clip(shape).background(colors.sunken).border(1.dp, colors.lineSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (record.dir == true && path != null) {
            val look = rememberFolderLook(path)
            Canvas(Modifier.size(26.dp)) { drawFolder(Inside.Pages, look) }
        } else {
            val ext = FileTypes.extensionOf(path?.name ?: record.target)
            Canvas(Modifier.size(26.dp)) { drawTypeIcon(ext) }
        }
    }
}

/** How a folder at [path] is dressed now, following changes made with Change icon. */
@Composable
fun rememberFolderLook(path: VPath): FolderLook {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return lookFor(emptyMap(), path)
    val styles = remember(context) { runCatching { FolderStyles.of(context) }.getOrNull() }
    val chosen by (styles?.byPath ?: NO_STYLES).collectAsState()
    return lookFor(chosen, path)
}

private val NO_STYLES = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, FolderLook>())
