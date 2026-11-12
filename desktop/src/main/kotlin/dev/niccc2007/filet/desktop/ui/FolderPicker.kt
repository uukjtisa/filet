@file:OptIn(ExperimentalFoundationApi::class)

package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.desktop.mount.Platform
import dev.niccc2007.filet.desktop.mount.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What a folder pick is for: the dialog's title, its button, where it opens, and what the choice does. */
class PickRequest(val title: String, val action: String, val start: String?, val onPick: (File) -> Unit)

/**
 * Choosing a folder to share, drawn in the window's own colours.
 *
 * Decision: an in-window dialog rather than the platform's. Swing's chooser was the one light,
 * dated surface in a dark window, and the Windows dialog follows the system theme, not this one.
 * This one also says what the window cares about that a generic chooser does not: which drives
 * there are and how full each is, since a whole drive is a thing that can be shared.
 *
 * Reading happens off the window's thread. A network drive whose server has gone blocks a
 * listing or a free-space query for as long as Windows lets it, so each drive is measured on its
 * own and fills in when it answers.
 */
@Composable
fun FolderPicker(req: PickRequest, close: () -> Unit) {
    var dir by remember { mutableStateOf(startDir(req.start)) }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<File?>(null) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Pair<String, File>>>(emptyList()) }
    var roots by remember { mutableStateOf<List<File>>(emptyList()) }
    val drives = remember { mutableStateMapOf<String, DriveInfo>() }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) {
        places = withContext(Dispatchers.IO) { knownPlaces() }
        roots = withContext(Dispatchers.IO) { File.listRoots()?.toList().orEmpty() }
        for (r in roots) launch(Dispatchers.IO) { drives[r.path] = measure(r) }
    }
    LaunchedEffect(dir) {
        entries = null
        failed = null
        selected = null
        withContext(Dispatchers.IO) { runCatching { folders(dir) } }
            .onSuccess { entries = it; lastDir = dir.path }
            .onFailure { entries = emptyList(); failed = it.message ?: "This folder cannot be read." }
        list.scrollToItem(0)
    }
    val chosen = selected ?: dir

    Column(
        Modifier.widthIn(max = 860.dp).fillMaxWidth(0.94f).heightIn(max = 620.dp).fillMaxHeight(0.9f)
            .background(Ink.panel, RoundedCornerShape(14.dp)).border(1.dp, Ink.edge, RoundedCornerShape(14.dp)),
    ) {
        // Title and where it is.
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(req.title, 16, bold = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Up", enabled = dir.parentFile != null) { dir.parentFile?.let { dir = it } }
                if (typing) {
                    Field(typed, { typed = it }, "C:\\Users\\you\\Documents", Modifier.weight(1f))
                    Btn("Go", primary = true) {
                        val f = File(typed.trim()).absoluteFile
                        if (f.isDirectory) { dir = f; typing = false } else failed = "No folder at ${typed.trim()}."
                    }
                    Btn("Cancel", kind = BtnKind.GHOST) { typing = false }
                } else {
                    Crumbs(dir, Modifier.weight(1f)) { dir = it }
                    Btn("Type a path", kind = BtnKind.GHOST) { typed = dir.path; typing = true }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Places and drives.
            val railScroll = rememberScrollState()
            Box(Modifier.width(230.dp).fillMaxHeight().background(Ink.strip)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(railScroll).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RailHead("PLACES")
                    for ((name, f) in places) RailItem(name, null, current = dir == f, onClick = { dir = f }) {
                        FolderMark(Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    RailHead("DRIVES")
                    for (r in roots) {
                        val info = drives[r.path]
                        RailItem(info?.label?.ifBlank { null } ?: r.path, info, current = rootOf(dir) == r, onClick = { dir = r }) {
                            DriveMark(Modifier.size(18.dp), network = info?.network == true)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(railScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = ScrollStyle)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.line))

            // The folders in it.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val shown = entries
                when {
                    shown == null -> Txt("Reading...", 13, Ink.muted, modifier = Modifier.padding(18.dp))
                    shown.isEmpty() -> Txt(failed ?: "No folders in here. The button below takes this one.", 13, if (failed != null) Ink.warn else Ink.muted, modifier = Modifier.padding(18.dp))
                    else -> {
                        LazyColumn(Modifier.fillMaxSize().padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), state = list) {
                            items(shown, key = { it.path }) { f ->
                                FolderRow(f.name, picked = selected == f, onPick = { selected = if (selected == f) null else f }, onOpen = { dir = f })
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 3.dp), style = ScrollStyle)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        // What the button will take.
        Row(
            Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(chosen.path, 13.5, Ink.url, mono = true, oneLine = true, modifier = Modifier.weight(1f, fill = false))
                    if (chosen.parentFile == null) Tag("whole drive")
                }
                Txt(
                    if (failed != null && entries?.isNotEmpty() == true) failed!!
                    else "Click a folder to choose it, double-click to open it.",
                    12, if (failed != null && entries?.isNotEmpty() == true) Ink.warn else Ink.muted,
                )
            }
            Btn("Cancel", kind = BtnKind.GHOST) { close() }
            Btn(req.action, primary = true) {
                close()
                req.onPick(chosen)
            }
        }
    }
}

/** The path as steps, each one a way back to it. */
@Composable
private fun Crumbs(dir: File, modifier: Modifier, go: (File) -> Unit) {
    val steps = generateSequence(dir) { it.parentFile }.toList().asReversed()
    Row(
        modifier.background(Ink.bg, RoundedCornerShape(8.dp)).border(1.dp, Ink.edge, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState(Int.MAX_VALUE)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, f ->
            if (i > 0) Txt("›", 13, Ink.muted, modifier = Modifier.padding(horizontal = 2.dp))
            val last = i == steps.lastIndex
            Box(
                Modifier.background(if (last) Ink.btn else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { go(f) }.padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Txt(f.name.ifEmpty { f.path.trimEnd('\\', '/').ifEmpty { "/" } }, 13, if (last) Ink.text else Ink.dim, mono = i == 0, oneLine = true)
            }
        }
    }
}

@Composable
private fun RailHead(s: String) = Txt(s, 11, Ink.muted, bold = true, spaced = true, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp))

@Composable
private fun RailItem(name: String, drive: DriveInfo?, current: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) Color(0xFF1C2B31) else if (hovered) Ink.btn else Color.Transparent, RoundedCornerShape(8.dp))
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(name, 13, if (current) Ink.text else Ink.soft, bold = current, oneLine = true)
            if (drive != null && drive.total > 0) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF22302F), RoundedCornerShape(2.dp))) {
                    val used = (1f - drive.free.toFloat() / drive.total).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth(used).height(4.dp)
                            .background(Brush.horizontalGradient(listOf(Ink.primary, Ink.accent)), RoundedCornerShape(2.dp)),
                    )
                }
                Txt("${Probe.size(drive.free)} free of ${Probe.size(drive.total)}", 11, Ink.muted, oneLine = true)
            } else if (drive != null) {
                Txt("Not available", 11, Ink.muted)
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, picked: Boolean, onPick: () -> Unit, onOpen: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .background(if (picked) Color(0xFF1F3640) else if (hovered) Ink.btn else Color.Transparent, shape)
            .border(1.dp, if (picked) Ink.primary else Color.Transparent, shape)
            .hoverable(hover)
            .combinedClickable(onClick = onPick, onDoubleClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FolderMark(Modifier.size(20.dp))
        Txt(name, 13.5, if (picked) Ink.text else Ink.soft, oneLine = true, modifier = Modifier.weight(1f))
        // A visible way in, for anyone who does not think to double-click.
        if (hovered || picked) {
            Box(Modifier.background(Ink.bg, RoundedCornerShape(6.dp)).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Txt("Open ›", 12, Ink.accent)
            }
        }
    }
}

/** A drive: a rounded slab with a light, the light amber for a network drive. */
@Composable
private fun DriveMark(modifier: Modifier, network: Boolean) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val top = h * 0.28f
        drawRoundRect(Ink.primary, Offset(w * 0.06f, top), Size(w * 0.88f, h * 0.48f), CornerRadius(w * 0.12f), style = Stroke(w * 0.1f))
        drawCircle(if (network) Ink.warn else Ink.ok, radius = w * 0.07f, center = Offset(w * 0.74f, top + h * 0.24f))
        drawLine(Ink.accent, Offset(w * 0.22f, top + h * 0.24f), Offset(w * 0.52f, top + h * 0.24f), strokeWidth = w * 0.08f)
    }
}

private class DriveInfo(val label: String, val free: Long, val total: Long, val network: Boolean)

/** Where the picker opened last in this run, so a second pick starts where the first ended. */
private var lastDir: String? = null

private fun startDir(start: String?): File =
    listOfNotNull(start, lastDir, System.getProperty("user.home"))
        .map { File(it) }
        .firstOrNull { runCatching { it.isDirectory }.getOrDefault(false) }
        ?: File.listRoots().first()

private fun rootOf(f: File): File? = generateSequence(f) { it.parentFile }.lastOrNull()

/** The subfolders worth offering, by name. Hidden and system ones are left out, as Explorer leaves them. */
private fun folders(dir: File): List<File> {
    val all = dir.listFiles() ?: throw IOException("This folder cannot be read. It may need permissions this app does not have.")
    return all.filter { f ->
        f.isDirectory && !f.isHidden && !f.name.startsWith("$") && f.name != "System Volume Information"
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** The usual folders that exist here, under the names people know them by. */
private fun knownPlaces(): List<Pair<String, File>> {
    val home = File(System.getProperty("user.home"))
    val view = runCatching { javax.swing.filechooser.FileSystemView.getFileSystemView() }.getOrNull()
    val windows = Platform.current == Platform.WINDOWS
    // On Windows the view knows where Desktop and Documents really are, which is not always under
    // the profile: a synced folder can hold both.
    val desktop = if (windows) view?.homeDirectory else null
    val documents = if (windows) view?.defaultDirectory else null
    return listOf(
        "Home" to home,
        "Desktop" to (desktop ?: File(home, "Desktop")),
        "Documents" to (documents ?: File(home, "Documents")),
        "Downloads" to File(home, "Downloads"),
        "Pictures" to File(home, "Pictures"),
        "Music" to File(home, "Music"),
        "Videos" to File(home, if (windows) "Videos" else "Movies").let { if (it.isDirectory) it else File(home, "Videos") },
    ).filter { (_, f) -> runCatching { f.isDirectory }.getOrDefault(false) }.distinctBy { it.second.absolutePath }
}

private fun measure(root: File): DriveInfo {
    val view = runCatching { javax.swing.filechooser.FileSystemView.getFileSystemView() }.getOrNull()
    val label = runCatching { view?.getSystemDisplayName(root) }.getOrNull().orEmpty()
    val network = runCatching { view?.getSystemTypeDescription(root).orEmpty().contains("network", ignoreCase = true) }.getOrDefault(false)
    val total = runCatching { root.totalSpace }.getOrDefault(0L)
    val free = runCatching { root.usableSpace }.getOrDefault(0L)
    return DriveInfo(label, free, total, network)
}
