package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PeekKind
import dev.niccc2007.filet.browser.rememberFolderPeek
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.icons.BrandTone
import dev.niccc2007.filet.icons.Colours
import dev.niccc2007.filet.icons.EmblemGrid
import dev.niccc2007.filet.icons.FolderLook
import dev.niccc2007.filet.icons.FolderStyles
import dev.niccc2007.filet.icons.GroupChips
import dev.niccc2007.filet.icons.Inside
import dev.niccc2007.filet.icons.drawFolder
import dev.niccc2007.filet.icons.drawTypeIcon
import dev.niccc2007.filet.icons.familyOf
import dev.niccc2007.filet.icons.lookFor
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one sheet a shortcut is made on, whatever it is for and wherever it was asked for: a
 * file's menu, a row's hold, a tab held in the bar or the side bar, a row of the Shortcuts tab or
 * its New shortcut. And the same sheet edits one already on the home screen.
 *
 * It settles what the launcher's own dialog cannot: the name, what opens it, and the icon. The
 * icon is shown as the home screen will show it, cut to a launcher's circle with the name cut
 * where a home screen cuts it, and every way of drawing it is shown as it would come out.
 */
@Composable
fun ShortcutSheetHost(state: ShortcutSheetState, vm: BrowserViewModel) {
    when (state) {
        is ShortcutSheetState.Sheet -> ShortcutSheet(
            ask = state.ask,
            onClose = vm::closeShortcutSheet,
            onChange = { vm.chooseNewShortcut(returnTo = state.ask) },
            onDone = { label, handler, draft -> vm.finishShortcutSheet(state.ask, label, handler, draft) },
            onRemove = { vm.removeShortcut(state.ask) },
        )
        is ShortcutSheetState.Choosing -> {
            val scripts by vm.scripts.scripts.collectAsState()
            ShortcutChooser(
                rows = remember(scripts) { chooserRows(scripts) },
                onBack = { vm.chooserBack(state) },
                onClose = vm::closeShortcutSheet,
                onPick = { vm.openShortcutSheet(it, SheetFrom.TAB) },
                onBrowse = { vm.pickShortcutTarget(it.folder) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSheet(
    ask: ShortcutAsk,
    onClose: () -> Unit,
    onChange: () -> Unit,
    onDone: (label: String, handler: HandlerId?, draft: IconDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val s = ask.subject
    val editing = ask.editing
    val node = (s as? PinSubject.File)?.node
    val folder = node?.takeIf { it.isDir }
    val graph = remember(context) { FiletApp.graphOf(context) }
    val styles = remember(context) { FolderStyles.of(context) }
    // What the folder wears in Filet now: Same as the folder starts there, and changes it on Add.
    val folderLook = remember(ask) { folder?.let { lookFor(styles.byPath.value, it.path) } }
    val peek = folder?.let { rememberFolderPeek(graph.folderPeek, it.path, it.mtime, true).value }
    val firstPicture = peek?.items?.firstOrNull { it.kind == PeekKind.MEDIA }?.node?.name

    var label by remember(ask) { mutableStateOf(editing?.label ?: s.name) }
    var handler by remember(ask) {
        mutableStateOf(editing?.handler?.let { h -> runCatching { HandlerId.valueOf(h) }.getOrNull() })
    }
    var draft by remember(ask) {
        mutableStateOf(IconDraft.start(s, folderLook, current = ShortcutIcon.parse(editing?.icon)))
    }
    // A picture with see-through edges starts framed, so an app's icon keeps its shape. Only until
    // somebody chooses, and never over a choice already kept on the shortcut.
    var framedTouched by remember(ask) { mutableStateOf(editing?.icon != null) }
    LaunchedEffect(ask) {
        if (framedTouched || node == null || node.isDir || IconSource.PICTURE !in sourcesFor(s)) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            runCatching { Thumbnails.get(context, graph.vfs, node.path, node.size, node.mtime, px = 216) }.getOrNull()
        } ?: return@LaunchedEffect
        if (!framedTouched) draft = draft.copy(framed = ShortcutArt.seeThrough(bmp))
    }

    val iconSection = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val tone = remember(context) { BrandTone.of(context) }

    Dlg(onDismiss = onClose, dismissOnScrim = false) {
        DlgHeader(
            FiletIcons.Pin,
            if (editing != null) "Shortcut" else "Add to home screen",
            sub = sheetSub(s, editing != null, node?.path?.parent?.path?.let(::whereOf)),
            onClose = onClose,
        )
        DlgBody {
            if (ask.from != SheetFrom.MENU) {
                OpensRow(s, defaultIcon(s), onChange.takeIf { editing == null })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                HomePreview(s, previewIcon(draft), label.ifBlank { s.name }) {
                    scope.launch { iconSection.bringIntoView() }
                }
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    DlgField("Name", label, { label = it.take(40) }, tight = true)
                    Spacer(Modifier.height(7.dp))
                    DlgCaption(opensLine(s))
                }
            }
            if (node != null && !node.isDir) {
                DlgSection("Opens with")
                OpenerChips(openersFor(node), handler) { handler = it }
            }
            Column(Modifier.bringIntoViewRequester(iconSection)) {
                DlgSection("Icon")
                IconSources(s, draft) { draft = draft.copy(source = it) }
                IconPanel(
                    s = s,
                    draft = draft,
                    tone = tone,
                    hasPicture = firstPicture != null,
                    onDraft = { next ->
                        if (next.framed != draft.framed) framedTouched = true
                        draft = next
                    },
                    firstPicture = firstPicture,
                )
            }
        }
        DlgFooter {
            if (editing != null) {
                Text(
                    "Remove",
                    fontSize = 12.5.sp,
                    color = Filet.colors.bad,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onRemove)
                        .padding(start = 2.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
                )
                DlgSpacer()
                DlgBtn("Cancel", fill = false, onClick = onClose)
                DlgBtn("Save", kind = BtnKind.PRIMARY, enabled = label.isNotBlank(), fill = false) {
                    onDone(label.trim(), handler, draft)
                }
            } else {
                DlgBtn("Cancel", onClick = onClose)
                DlgBtn("Add", kind = BtnKind.PRIMARY, enabled = label.isNotBlank()) {
                    onDone(label.trim(), handler, draft)
                }
            }
        }
    }
}

/**
 * The icon a draft shows. A folder's is drawn with the look being set, even under Same as the
 * folder: that look is the folder's own once the sheet is added.
 */
private fun previewIcon(d: IconDraft): ShortcutIcon =
    if (d.source == IconSource.FOLDER) ShortcutIcon.Folder(d.look) else d.icon()

/** What [icon] draws for [s], kept while the next one is drawn so nothing flickers. */
@Composable
private fun rememberDrawn(s: PinSubject, icon: ShortcutIcon): ShortcutArt.Drawn? {
    val context = LocalContext.current
    var drawn by remember(s) { mutableStateOf<ShortcutArt.Drawn?>(null) }
    LaunchedEffect(s, icon) { drawn = ShortcutArt.draw(context, s, icon) }
    return drawn
}

/**
 * A drawn icon in a launcher's circle: the middle 72 of its 108 units fill [side], the rest cut,
 * as a mask cuts it.
 */
@Composable
private fun DrawnIcon(drawn: ShortcutArt.Drawn?, side: Dp) {
    Box(Modifier.size(side).clip(CircleShape), contentAlignment = Alignment.Center) {
        val whole = side * (108f / 72f)
        when (drawn) {
            is ShortcutArt.Drawn.Res -> Image(painterResource(drawn.layer), null, Modifier.requiredSize(whole))
            is ShortcutArt.Drawn.Bits -> {
                val image = remember(drawn) { drawn.bitmap.asImageBitmap() }
                Image(image, null, Modifier.requiredSize(whole))
            }
            null -> Unit
        }
    }
}

/** The icon over its name on a patch of dark wallpaper, cut where a home screen cuts both. */
@Composable
private fun HomePreview(s: PinSubject, icon: ShortcutIcon, name: String, onTap: () -> Unit) {
    val colors = Filet.colors
    val drawn = rememberDrawn(s, icon)
    Box(
        Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .drawBehind { wallpaper() }
            .clickable(onClickLabel = "Change the icon", role = Role.Button, onClick = onTap),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DrawnIcon(drawn, 52.dp)
            Text(
                name,
                fontSize = 11.sp,
                color = Color.White,
                style = TextStyle(shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 4f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp).padding(top = 5.dp),
            )
        }
        // The pen says the picture is the way in to changing it.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-12).dp, y = 44.dp)
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .background(colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FiletIcons.Rename, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
        }
    }
}

/** `radial-gradient(120% 90% at 30% 10%, #3B4A55 0%, #1B2329 55%, #101417 100%)`. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.wallpaper() {
    val rx = size.width * 1.2f
    val ry = size.height * 0.9f
    val c = Offset(size.width * 0.3f, size.height * 0.1f)
    val k = ry / rx
    // Squashed about its centre, so the circle the brush draws is the ellipse the mock asks for.
    withTransform({ scale(1f, k, c) }) {
        val top = c.y - c.y / k
        drawRect(
            Brush.radialGradient(0f to Color(0xFF3B4A55), 0.55f to Color(0xFF1B2329), 1f to Color(0xFF101417), center = c, radius = rx),
            topLeft = Offset(0f, top),
            size = Size(size.width, size.height / k),
        )
    }
}

/** What the shortcut opens, with its own icon, and Change to pick something else. */
@Composable
private fun OpensRow(s: PinSubject, icon: ShortcutIcon, onChange: (() -> Unit)?) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.sunken)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        DrawnIcon(rememberDrawn(s, icon), 34.dp)
        Column(Modifier.weight(1f)) {
            Text("OPENS", fontSize = 9.5.sp, letterSpacing = 1.sp, color = colors.fg3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ${s.noun}", fontSize = 11.sp, color = colors.fg3, maxLines = 1)
            }
        }
        if (onChange != null) {
            Text(
                "Change",
                fontSize = 12.5.sp,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onChange)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
    }
}

/** The openers as chips: Default, the kind's own viewer, then the general ones. */
@Composable
private fun OpenerChips(options: List<Pair<HandlerId?, String>>, chosen: HandlerId?, onPick: (HandlerId?) -> Unit) {
    val colors = Filet.colors
    HScroll(ground = colors.raised, spacing = 5.dp) {
        for ((id, name) in options) {
            val on = id == chosen
            Text(
                name,
                fontSize = 11.sp,
                color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) colors.accent else colors.high)
                    .semantics { selected = on }
                    .clickable(role = Role.RadioButton) { onPick(id) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

/** One tile per source, each showing what it would make. */
@Composable
private fun IconSources(s: PinSubject, draft: IconDraft, onPick: (IconSource) -> Unit) {
    val colors = Filet.colors
    HScroll(ground = colors.raised, spacing = 5.dp) {
        for (source in sourcesFor(s)) {
            val on = source == draft.source
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .width(62.dp)
                    .clip(shape)
                    .background(if (on) colors.sel else colors.sunken)
                    .border(1.5.dp, if (on) colors.accent else Color.Transparent, shape)
                    .semantics { selected = on }
                    .clickable(role = Role.RadioButton) { onPick(source) }
                    .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                DrawnIcon(rememberDrawn(s, previewIcon(draft.copy(source = source))), 34.dp)
                Text(
                    source.label,
                    fontSize = 10.5.sp,
                    color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What the chosen source lets be set, and a line saying what it is. */
@Composable
private fun IconPanel(
    s: PinSubject,
    draft: IconDraft,
    tone: BrandTone,
    hasPicture: Boolean,
    firstPicture: String?,
    onDraft: (IconDraft) -> Unit,
) {
    when (draft.source) {
        IconSource.PICTURE -> {
            Spacer(Modifier.height(10.dp))
            Seg2("Fill the shape", "Framed, wallpaper around it", second = draft.framed) { onDraft(draft.copy(framed = it)) }
            Spacer(Modifier.height(6.dp))
            DlgCaption("The picture itself, cut to your launcher's shape. It's drawn once when you add it, so a later edit to the photo won't reach the icon.")
        }
        IconSource.TYPE -> {
            val ext = (s as? PinSubject.File)?.node?.extension.orEmpty()
            Spacer(Modifier.height(6.dp))
            DlgCaption("The ${familyOf(ext).label} icon, the one the file list draws.")
        }
        IconSource.FOLDER -> {
            Spacer(Modifier.height(10.dp))
            Seg2("Same as the folder", "Its own", second = draft.folderOwn) { own -> onDraft(draft.copy(folderOwn = own)) }
            Spacer(Modifier.height(5.dp))
            DlgCaption(
                if (draft.folderOwn) "Only this shortcut. The folder in Filet keeps its own icon."
                else "Wears the folder's own icon and follows it. A change here changes the folder in Filet too, same as Change icon.",
            )
            Spacer(Modifier.height(2.dp))
            DlgSection("Emblem")
            EmblemPicker(draft.group, draft.look.emblem, draft.look.colour, tone,
                onGroup = { onDraft(draft.copy(group = it)) },
                onEmblem = { onDraft(draft.copy(look = draft.look.copy(emblem = it))) },
                onColour = { onDraft(draft.copy(look = draft.look.copy(colour = it))) },
            )
            if (hasPicture) {
                Spacer(Modifier.height(10.dp))
                DlgTick(
                    on = draft.look.cover != null,
                    label = "Use a photo from inside",
                    sub = "One picture in front, instead of the three Filet would pick",
                    onToggle = { onDraft(draft.copy(look = draft.look.copy(cover = if (draft.look.cover != null) null else firstPicture))) },
                )
            }
        }
        IconSource.EMBLEM -> {
            Spacer(Modifier.height(10.dp))
            EmblemPicker(draft.group, draft.emblem, draft.colour, tone,
                onGroup = { onDraft(draft.copy(group = it)) },
                onEmblem = { onDraft(draft.copy(emblem = it)) },
                onColour = { onDraft(draft.copy(colour = it)) },
            )
            Spacer(Modifier.height(6.dp))
            DlgCaption("The emblem on its own, drawn full size. Same emblems and colours as a folder's Change icon.")
        }
        IconSource.DRAWING -> {
            Spacer(Modifier.height(10.dp))
            DrawingGrid(draft.art) { onDraft(draft.copy(art = it)) }
            Spacer(Modifier.height(6.dp))
            val own = ownArt(s)
            DlgCaption("Every tab and action drawing. " + if (own != null && draft.art == own) "This one is its own." else "${drawingLabel(draft.art)}'s drawing.")
        }
        IconSource.FILET -> {
            Spacer(Modifier.height(6.dp))
            DlgCaption("Filet's own icon. Every shortcut used to get this one.")
        }
        IconSource.SIMPLE -> {
            Spacer(Modifier.height(6.dp))
            DlgCaption("The line icon Filet uses inside the app, on a plain disc in Filet's colour.")
        }
    }
}

/** Change icon's emblem groups, grid and colours, the swatches in this build's colours. */
@Composable
private fun EmblemPicker(
    group: String,
    emblem: String?,
    colour: String,
    tone: BrandTone,
    onGroup: (String) -> Unit,
    onEmblem: (String?) -> Unit,
    onColour: (String) -> Unit,
) {
    GroupChips(group, onGroup)
    Spacer(Modifier.height(6.dp))
    EmblemGrid(emblem, group, onEmblem)
    DlgSection("Colour")
    Colours(colour, tone, onColour)
}

/** Every tab and action drawing, six to a row, scrolling past four rows. */
@Composable
private fun DrawingGrid(chosen: String, onPick: (String) -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxWidth().heightIn(max = 196.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (row in DRAWINGS.chunked(6)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (art in row) {
                    val on = art == chosen
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(if (on) colors.sel else colors.sunken)
                            .border(1.5.dp, if (on) colors.accent else Color.Transparent, shape)
                            .semantics { selected = on }
                            .clickable(role = Role.RadioButton) { onPick(art) }
                            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The drawing's middle 60 units, in this build's colours.
                        Box(Modifier.size(36.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                            homeArt(art)?.let { Image(painterResource(it), null, Modifier.requiredSize(36.dp * (108f / 60f))) }
                        }
                        Text(
                            drawingLabel(art),
                            fontSize = 9.sp,
                            color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 3.dp),
                        )
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Two choices side by side on a sunken track; [second] is whether the right one is on. */
@Composable
private fun Seg2(first: String, secondLabel: String, second: Boolean, onPick: (Boolean) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(colors.sunken).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((label, value) in listOf(first to false, secondLabel to true)) {
            val on = value == second
            Text(
                label,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) colors.raised else Color.Transparent)
                    .semantics { selected = on }
                    .clickable(role = Role.RadioButton) { onPick(value) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * What a new shortcut should open: every tab by its group, the actions, the scripts, and a file
 * or folder picked in Filet's own browser.
 */
@Composable
private fun ShortcutChooser(
    rows: List<ChooserRow>,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onPick: (PinSubject) -> Unit,
    onBrowse: (ChooserRow.Browse) -> Unit,
) {
    val colors = Filet.colors
    Dlg(onDismiss = onClose) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.high)
                    .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FiletIcons.Back, "Back", tint = colors.fg2, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text("What should it open?", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
                Text("Any tab, action, script, file or folder", fontSize = 11.sp, color = colors.fg3, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Close", role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FiletIcons.Close, "Close", tint = colors.fg3, modifier = Modifier.size(14.dp))
            }
        }
        DlgBody {
            for (row in rows) {
                when (row) {
                    is ChooserRow.Heading -> DlgSection(row.label)
                    is ChooserRow.Pick -> ChooserLine(row.subject.name, row.note, { onPick(row.subject) }) {
                        ownArt(row.subject)?.let { art ->
                            homeArt(art)?.let { Image(painterResource(it), null, Modifier.requiredSize(30.dp * (108f / 60f))) }
                        }
                    }
                    is ChooserRow.Browse -> ChooserLine(row.label, row.note, { onBrowse(row) }) {
                        Canvas(Modifier.size(30.dp)) {
                            if (row.folder) drawFolder(Inside.Pages, FolderLook.PLAIN) else drawTypeIcon("pdf")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooserLine(label: String, note: String?, onClick: () -> Unit, art: @Composable () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(30.dp).clipToBounds(), contentAlignment = Alignment.Center) { art() }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) Text(note, fontSize = 10.5.sp, color = colors.fg3)
        }
    }
}

/** One row in the in-app shortcut list. */
@Composable
fun ShortcutRow(
    record: ShortcutRecord,
    live: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 7.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordTile(record)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            dev.niccc2007.filet.browser.ListNameText(
                text = dev.niccc2007.filet.ui.highlighted(record.label, dev.niccc2007.filet.ui.LocalPaneQuery.current, colors.accent),
                color = androidx.compose.material3.LocalContentColor.current,
                startSp = 13f,
                lines = 1,
                isDir = true,
            )
            Text(
                buildString {
                    append(record.kindLabel())
                    if (!live) append(" · not confirmed by the launcher")
                    record.handler?.let { append(" · opens with ").append(it.lowercase()) }
                },
                fontSize = 10.sp, color = if (live) colors.fg3 else colors.warn,
            )
        }
        SmallBtn("Edit", onEdit)
        SmallBtn("Forget", onForget)
    }
}
