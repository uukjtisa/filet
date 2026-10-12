package dev.niccc2007.filet.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath

/**
 * The kept queues, and what is in one.
 *
 * ## Two screens or one
 *
 * One, with a drill-down held in local state rather than a second `PaneKind`. A playlist is
 * not a destination - nothing else in the app links to one, the back gesture should return to
 * the list rather than to whatever was open before it, and giving it a pane kind would mean a
 * route, an icon, a search mode and a refresh plan for a screen that is reached from exactly
 * one row. The tab is the destination; the playlist is a state of it.
 *
 * ## What is deliberately not here
 *
 * No library, no artists, no albums. The file browser is the library - in a collection that
 * came off a phone the folder IS the album and its name IS the artist - and a second way to
 * browse the same files, with a different vocabulary and its own empty states, is the
 * redundancy this feature was asked NOT to add. See [Playlists].
 */
@Composable
fun PlaylistsScreen(vm: BrowserViewModel, pane: PaneController) {
    val lists by vm.playlists.items.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var making by remember { mutableStateOf(false) }

    val shown = open?.let { id -> lists.firstOrNull { it.id == id } }
    // Back closes the drill-down first. Without this the tab closes and the list you were
    // three rows into is gone, which is the one thing a back button must not do.
    BackHandler(enabled = shown != null) { open = null }

    if (shown != null) {
        PlaylistDetail(
            vm = vm,
            list = shown,
            onBack = { open = null },
            onRename = { renaming = shown },
            onDelete = { deleting = shown },
        )
    } else {
        PlaylistList(
            lists = lists,
            onOpen = { open = it },
            onPlay = { vm.playPlaylist(it) },
            onNew = { making = true },
        )
    }

    if (making) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirm = "Create",
            onDismiss = { making = false },
        ) { name -> making = false; vm.playlists.create(name) }
    }
    renaming?.let { list ->
        NameDialog(
            title = "Rename playlist",
            initial = list.name,
            confirm = "Rename",
            onDismiss = { renaming = null },
        ) { name -> renaming = null; vm.playlists.rename(list.id, name) }
    }
    deleting?.let { list ->
        Dlg(onDismiss = { deleting = null }) {
            DlgHeader(
                icon = FiletIcons.Delete,
                title = "Delete ${list.name}?",
                sub = dev.niccc2007.filet.browser.plural(list.size, "track", "tracks"),
                tone = dev.niccc2007.filet.ui.dialogs.DlgTone.BAD,
                onClose = { deleting = null },
            )
            DlgBody {
                // The one thing anybody is actually worried about when they press this.
                DlgCaption("The list goes. The files stay exactly where they are.")
            }
            DlgFooter {
                DlgBtn("Cancel") { deleting = null }
                DlgBtn("Delete", kind = BtnKind.DANGER) {
                    vm.playlists.delete(list.id)
                    deleting = null
                    open = null
                }
            }
        }
    }
}

@Composable
private fun PlaylistList(
    lists: List<Playlist>,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onNew: () -> Unit,
) {
    val colors = Filet.colors
    LazyColumn(Modifier.fillMaxSize()) {
        items(lists, key = { it.id }) { list ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(list.id) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (list.builtIn) colors.accent.copy(alpha = 0.18f) else colors.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (list.builtIn) FiletIcons.Heart else FiletIcons.Rows,
                        null,
                        tint = if (list.builtIn) colors.accent else colors.fg2,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        list.name,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (list.size == 0) "Empty" else dev.niccc2007.filet.browser.plural(list.size, "track", "tracks"),
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                    )
                }
                if (list.size > 0) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .clickable { onPlay(list.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FiletIcons.PlaySolid, "Play ${list.name}",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNew)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Plus, null, tint = colors.accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(12.dp))
                Text("New playlist", fontSize = 12.5.sp, color = colors.accent)
            }
        }
        item {
            // Said once, at the foot, rather than as an empty state that only appears when
            // there is nothing - this is the thing somebody needs to read on their FIRST
            // visit, which is also the visit where Favourites already exists and so the
            // empty state would never have shown.
            DlgCaption(
                "Add tracks from a file list: select them and choose Add to playlist. " +
                    "The heart on the player puts a track in Favourites. Videos can go in a " +
                    "playlist too, if you put them there.",
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlaylistDetail(
    vm: BrowserViewModel,
    list: Playlist,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Filet.colors
    val state by vm.playback.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FiletIcons.Back, "Back to the playlists",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onBack)
                    .padding(7.dp),
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(list.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (list.size == 0) "Empty" else dev.niccc2007.filet.browser.plural(list.size, "track", "tracks"),
                    fontSize = 10.sp, color = colors.fg3,
                )
            }
            // Favourites cannot be renamed or deleted, so it is not offered either - a
            // disabled row is a question somebody has to answer with "oh, not that one".
            if (!list.builtIn) {
                Icon(
                    FiletIcons.Rename, "Rename",
                    tint = colors.fg2,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onRename).padding(7.dp),
                )
                Icon(
                    FiletIcons.Delete, "Delete this playlist",
                    tint = colors.fg2,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onDelete).padding(7.dp),
                )
            }
        }

        if (list.size > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistButton("Play all", FiletIcons.PlaySolid, primary = true) {
                    vm.playPlaylist(list.id)
                }
                PlaylistButton("Shuffle", FiletIcons.Shuffle) {
                    vm.playPlaylist(list.id, shuffle = true)
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list.tracks, key = { _, p -> p.toString() }) { i, path ->
                val playing = state.current?.id == path.toString()
                TrackRow(
                    path = path,
                    index = i,
                    playing = playing,
                    onPlay = { vm.playPlaylist(list.id, from = i) },
                    onUp = { vm.playlists.move(list.id, i, i - 1) },
                    onDown = { vm.playlists.move(list.id, i, i + 1) },
                    canUp = i > 0,
                    canDown = i < list.size - 1,
                    onRemove = { vm.playlists.remove(list.id, path) },
                )
            }
            if (list.size == 0) {
                item {
                    DlgCaption(
                        if (list.builtIn) {
                            "Nothing hearted yet. The heart on the player screen puts the " +
                                "track you are listening to in here."
                        } else {
                            "Nothing in here yet. Select tracks in a file list and choose " +
                                "Add to playlist."
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PlaylistButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) colors.accent else colors.high)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary else colors.fg2,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else colors.fg2,
        )
    }
}

/**
 * One row.
 *
 * The folder is on the second line because a playlist is the one place in this app where two
 * tracks with the same name genuinely sit next to each other - that is what a playlist made
 * from several folders IS - and the name alone cannot tell them apart.
 */
@Composable
private fun TrackRow(
    path: VPath,
    index: Int,
    playing: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    onRemove: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (playing) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (playing) {
                Icon(
                    FiletIcons.PlaySolid, null,
                    tint = colors.accent, modifier = Modifier.size(12.dp),
                )
            } else {
                Text(
                    "${index + 1}",
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                path.name,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) colors.accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                dev.niccc2007.filet.handlers.folderContext(path),
                fontSize = 10.sp,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowIcon(FiletIcons.Up, "Move up", enabled = canUp, onClick = onUp)
        RowIcon(FiletIcons.Down, "Move down", enabled = canDown, onClick = onDown)
        RowIcon(FiletIcons.Close, "Take out of this playlist", onClick = onRemove)
    }
}

@Composable
private fun RowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Icon(
        icon, label,
        tint = if (enabled) colors.fg3 else colors.fg3.copy(alpha = 0.3f),
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
    )
}

/** One field and two buttons, for naming a list. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(icon = FiletIcons.Rows, title = title, onClose = onDismiss)
        DlgBody {
            DlgField(label = "Name", value = text, onValue = { text = it })
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgSpacer()
            DlgBtn(confirm, kind = BtnKind.PRIMARY) { onConfirm(text) }
        }
    }
}
