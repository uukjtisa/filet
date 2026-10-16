package dev.niccc2007.filet.playback

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.browser.plural
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
 * ## Why this looks like a music app when the rest of Filet does not
 *
 * Everywhere else a row is a FILE: its name as typed, its size, its date. Here a row is a
 * TRACK - the title the file says it has, who made it, how long it runs, its cover - because
 * a playlist is the one place in a file manager where the filename is the least useful thing
 * on the screen. `VID_20261009_120104.mp4` and `03 - track.mp3` tell you nothing about what
 * is in them, and in a list somebody assembled on purpose they were all it had.
 *
 * Tags come from [TrackCards], which reads them off the main thread, at thumbnail size, and
 * keeps them - see there for why a list does not use the player's own tag reader.
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
            vm = vm,
            lists = lists,
            onOpen = { open = it },
            onPlay = { vm.playPlaylist(it) },
            onShuffle = { vm.playPlaylist(it, shuffle = true) },
            onRename = { renaming = it },
            onDelete = { deleting = it },
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
                sub = plural(list.size, "track", "tracks"),
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

// ── the shelf of lists ────────────────────────────────────────────────────────

@Composable
private fun PlaylistList(
    vm: BrowserViewModel,
    lists: List<Playlist>,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onShuffle: (String) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onNew: () -> Unit,
) {
    val colors = Filet.colors
    LazyColumn(Modifier.fillMaxSize()) {
        items(lists, key = { it.id }) { list ->
            // A long press on a playlist, which is where a long press goes everywhere else
            // in this app. Without it the only way to rename or delete one was to open it
            // first and find the buttons in its header - two steps to reach something the
            // row itself plainly refers to.
            var menu by remember(list.id) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpen(list.id) },
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open", fontSize = 13.sp) },
                            onClick = { menu = false; onOpen(list.id) },
                        )
                        if (list.size > 0) {
                            DropdownMenuItem(
                                text = { Text("Play", fontSize = 13.sp) },
                                onClick = { menu = false; onPlay(list.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Shuffle", fontSize = 13.sp) },
                                onClick = { menu = false; onShuffle(list.id) },
                            )
                        }
                        // The two a built-in list does not have: Favourites is not a
                        // playlist somebody made and cannot be renamed out of existence.
                        if (!list.builtIn) {
                            DropdownMenuItem(
                                text = { Text("Rename", fontSize = 13.sp) },
                                onClick = { menu = false; onRename(list) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", fontSize = 13.sp) },
                                onClick = { menu = false; onDelete(list) },
                            )
                        }
                    }
                }
                PlaylistCover(vm.trackCards, list, 54.dp, radius = 13.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        list.name,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (list.size == 0) "Empty" else plural(list.size, "track", "tracks"),
                        fontSize = 11.sp,
                        color = colors.fg3,
                    )
                }
                if (list.size > 0) {
                    Box(
                        Modifier
                            .size(38.dp)
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
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(colors.accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FiletIcons.Plus, null,
                        tint = colors.accent, modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Text("New playlist", fontSize = 13.5.sp, color = colors.accent)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * A playlist's cover: one artwork, or four in a square.
 *
 * Four wherever there are four, because a playlist is a mixture by definition and a single
 * cover makes it look like an album. Taken from the first tracks that HAVE art rather than the
 * first tracks, so a list whose opening song is untagged is not represented by a blank tile.
 */
@Composable
fun PlaylistCover(cards: TrackCards, list: Playlist, side: Dp, radius: Dp) {
    val colors = Filet.colors
    // Only the first handful are consulted. A two-hundred-track list must not read two
    // hundred files to draw a 54dp square.
    val heads = remember(list.tracks) { list.tracks.take(6) }
    val arts = heads.mapNotNull { rememberTrackCard(cards, it).value.art }

    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(radius))
            .background(if (list.builtIn) colors.accent.copy(alpha = 0.15f) else colors.sunken),
        contentAlignment = Alignment.Center,
    ) {
        when {
            arts.size >= 4 -> Column(Modifier.fillMaxSize()) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0 until 2) {
                            Image(
                                arts[row * 2 + col].asImageBitmap(), null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
            arts.isNotEmpty() -> Image(
                arts.first().asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(
                if (list.builtIn) FiletIcons.Heart else FiletIcons.Audio,
                null,
                tint = if (list.builtIn) colors.accent else colors.fg3,
                modifier = Modifier.size(side * 0.34f),
            )
        }
    }
}

// ── one playlist ──────────────────────────────────────────────────────────────

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
    val cards = list.tracks.map { rememberTrackCard(vm.trackCards, it).value }
    val total = cards.sumOf { it.durationMs }
    // The colour of the list, taken from its own first cover with the same sampler the player
    // uses - so a playlist and the track playing out of it are the same hue rather than two
    // opinions about one album.
    val cover = cards.firstOrNull { it.art != null }?.art
    val (tint, onTint) = rememberArtPalette(
        art = cover,
        fallback = colors.accent,
        onFallback = MaterialTheme.colorScheme.onPrimary,
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PlaylistHeader(
                vm = vm,
                list = list,
                total = total,
                tint = tint,
                onTint = onTint,
                onBack = onBack,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
        itemsIndexed(list.tracks, key = { _, p -> p.toString() }) { i, path ->
            val mine = state.current?.id == path.toString()
            TrackRow(
                path = path,
                card = cards[i],
                playing = mine && state.active,
                sounding = mine && state.playing,
                tint = tint,
                onPlay = { vm.playPlaylist(list.id, from = i) },
                onUp = if (i > 0) ({ vm.playlists.move(list.id, i, i - 1) }) else null,
                onDown = if (i < list.size - 1) {
                    ({ vm.playlists.move(list.id, i, i + 1) })
                } else {
                    null
                },
                onRemove = { vm.playlists.remove(list.id, path) },
                onAddTo = { vm.askAddToPlaylistPath(path) },
                onReveal = { vm.revealPathInFolder(path) },
            )
        }
        if (list.size == 0) {
            item {
                DlgCaption(
                    if (list.builtIn) {
                        "Nothing hearted yet. The heart on the player screen puts the track " +
                            "you are listening to in here."
                    } else {
                        "Nothing in here yet. Select tracks in a file list and choose " +
                            "Add to playlist."
                    },
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * The top of a playlist: its cover, its name, and the two buttons.
 *
 * The wash behind it is painted from the sampled colour rather than from a blurred copy of the
 * cover. A blurred cover behind a tile of the same cover is the same picture twice, and the
 * colour was the part doing the work - the player's own Ambient face can afford the blur
 * because there the artwork is 320dp and the backdrop is the whole screen.
 */
@Composable
private fun PlaylistHeader(
    vm: BrowserViewModel,
    list: Playlist,
    total: Long,
    tint: Color,
    onTint: Color,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Filet.colors
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(250.dp)) {
            drawRect(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.24f),
                    0.62f to tint.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 6.dp),
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
                Spacer(Modifier.weight(1f))
                // Favourites cannot be renamed or deleted, so it is not offered either - a
                // disabled row is a question somebody has to answer with "oh, not that one".
                if (!list.builtIn) {
                    Icon(
                        FiletIcons.Rename, "Rename this playlist",
                        tint = colors.fg2,
                        modifier = Modifier.size(32.dp).clip(CircleShape)
                            .clickable(onClick = onRename).padding(8.dp),
                    )
                    Icon(
                        FiletIcons.Delete, "Delete this playlist",
                        tint = colors.fg2,
                        modifier = Modifier.size(32.dp).clip(CircleShape)
                            .clickable(onClick = onDelete).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.shadow(26.dp, RoundedCornerShape(10.dp))) {
                    PlaylistCover(vm.trackCards, list, 168.dp, radius = 10.dp)
                }
            }
            // Left-aligned from here down, which is the shape every music app settled on:
            // the cover is the centred object and the words hang off the left margin under
            // it, so the name, the count and the buttons all start on one line of their own.
            Spacer(Modifier.height(18.dp))
            Text(
                list.name,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    if (list.size == 0) "Empty" else plural(list.size, "track", "tracks"),
                    runningTime(total).ifEmpty { null },
                ).joinToString("  ·  "),
                fontSize = 11.5.sp,
                color = colors.fg3,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            if (list.size > 0) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShufflePill(tint) { vm.playPlaylist(list.id, shuffle = true) }
                    Spacer(Modifier.weight(1f))
                    // The one big round filled button, on the right, where a thumb is.
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(tint)
                            .clickable { vm.playPlaylist(list.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FiletIcons.PlaySolid, "Play ${list.name}",
                            tint = onTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** The quieter of the two: an outline pill, beside the filled circle. */
@Composable
private fun ShufflePill(tint: Color, onClick: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.high)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Shuffle, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text("Shuffle", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.fg2)
    }
}

/** `12 min` / `1 hr 4 min`. Empty when nothing in the list would say how long it is. */
fun runningTime(totalMs: Long): String {
    if (totalMs <= 0) return ""
    val minutes = (totalMs / 60_000).toInt()
    if (minutes < 1) return "under a minute"
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} hr ${minutes % 60} min"
}

/**
 * One track.
 *
 * The title is the one the FILE says it has, not the filename - which is the whole point of
 * this screen. The second line is the artist, falling back to the album and then to the
 * folder: a playlist made from several folders is the one place two tracks with the same name
 * genuinely sit next to each other, and the name alone cannot tell them apart.
 *
 * Reorder and remove are behind the overflow rather than on the row. Three buttons on every
 * row is a list that looks like a settings screen, and moving a track is something somebody
 * does once when they build a list and then never again.
 */
@Composable
private fun TrackRow(
    path: VPath,
    card: TrackCard,
    playing: Boolean,
    sounding: Boolean,
    tint: Color,
    onPlay: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: () -> Unit,
    onAddTo: () -> Unit,
    onReveal: () -> Unit,
) {
    val colors = Filet.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (playing) tint.copy(alpha = 0.10f) else Color.Transparent)
            // The dots are still there; this is the same menu reached the way a list row is
            // normally asked a question in this app. A control that exists only as a 34dp
            // target at the end of a row is a control half the people using it never find.
            .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
            .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            val art = card.art
            if (art != null) {
                Image(
                    art.asImageBitmap(), null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    if (card.video) FiletIcons.Video else FiletIcons.Audio, null,
                    tint = colors.fg3, modifier = Modifier.size(16.dp),
                )
            }
            if (playing) {
                // The bars, over the cover, so the row that is playing is found without
                // reading anything. Still when paused - which is the state the transport
                // glyph is in too, and the one a track number could not have shown at all.
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.46f)),
                    contentAlignment = Alignment.Center,
                ) { Equaliser(tint, sounding) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                fontSize = 13.sp,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) tint else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Said on the row rather than left to be discovered: a video in a playlist is
                // deliberate, and the badge is also the answer to "why has that one got no
                // artist" before anybody asks it.
                if (card.video) {
                    Icon(
                        FiletIcons.Video, null,
                        tint = colors.fg3, modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    card.byline(path),
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.clock.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(card.clock, fontSize = 10.5.sp, color = colors.fg3)
        }
        Box {
            Icon(
                FiletIcons.More, "More for ${card.title}",
                tint = colors.fg3,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { menu = true }
                    .padding(10.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onUp?.let {
                    DropdownMenuItem(
                        text = { Text("Move up", fontSize = 13.sp) },
                        onClick = { menu = false; it() },
                    )
                }
                onDown?.let {
                    DropdownMenuItem(
                        text = { Text("Move down", fontSize = 13.sp) },
                        onClick = { menu = false; it() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Add to another playlist", fontSize = 13.sp) },
                    onClick = { menu = false; onAddTo() },
                )
                DropdownMenuItem(
                    text = { Text("Open containing folder", fontSize = 13.sp) },
                    onClick = { menu = false; onReveal() },
                )
                DropdownMenuItem(
                    text = { Text("Take out of this playlist", fontSize = 13.sp) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/** Three bars, moving while the sound is. */
@Composable
private fun Equaliser(tint: Color, sounding: Boolean) {
    if (sounding) LiveBars(tint) else Bars(tint, STILL)
}

/** The heights the bars rest at when playback is paused. */
private val STILL = listOf(0.34f, 0.86f, 0.52f)

@Composable
private fun LiveBars(tint: Color) {
    val clock = rememberInfiniteTransition(label = "eq")

    // Three periods rather than three delays: the bars drift out of phase and stay there,
    // where a shared period with offsets reads as one wave crossing the glyph.
    val a by clock.animateFloat(
        0.30f, 1f,
        infiniteRepeatable(tween(460, easing = LinearEasing), RepeatMode.Reverse),
        label = "a",
    )
    val b by clock.animateFloat(
        0.95f, 0.34f,
        infiniteRepeatable(tween(370, easing = LinearEasing), RepeatMode.Reverse),
        label = "b",
    )
    val c by clock.animateFloat(
        0.52f, 1f,
        infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
        label = "c",
    )
    Bars(tint, listOf(a, b, c))
}

@Composable
private fun Bars(tint: Color, heights: List<Float>) {
    Row(
        Modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (h in heights) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp * h)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tint),
            )
        }
    }
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
        DlgHeader(icon = FiletIcons.Audio, title = title, onClose = onDismiss)
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
