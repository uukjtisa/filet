package dev.niccc2007.filet.playback

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextDecoration
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
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.highlightPulse
import kotlinx.coroutines.launch

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
    // Which list is open, kept with the tab: coming back to the playlists is coming back into
    // the one that was being listened through, not to the shelf.
    var open by dev.niccc2007.filet.browser.rememberKept<String?>("playlists.open", null, save = { it }, load = { it })
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var making by remember { mutableStateOf(false) }

    val shown = open?.let { id -> lists.firstOrNull { it.id == id } }

    // The pane's search box. At the top it searches every playlist and all of the music on the
    // phone; inside one it narrows that one. A tap on a result shuts the box, brings the track
    // into view in its full list, marks it, and plays it - see [SearchTarget].
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    var target by remember { mutableStateOf<SearchTarget?>(null) }
    val pick = { listId: String, path: VPath ->
        target = SearchTarget(path, System.nanoTime())
        open = listId
        pane.openSearch(false)
    }
    // Back closes the drill-down first. Without this the tab closes and the list you were
    // three rows into is gone, which is the one thing a back button must not do.
    //
    // `InnerBack`, not `BackHandler`: a second back handler competes with the browser's own and
    // the winner is decided by composition order, which is why this one was losing and a press
    // inside an open playlist was walking the PANE's history back to Home instead.
    dev.niccc2007.filet.browser.InnerBack(vm, enabled = shown != null || open == AllMusic.ID) {
        open = null
    }

    if (open == AllMusic.ID) {
        AllMusicDetail(
            vm = vm,
            onBack = { open = null },
            query = query,
            target = target,
            onPick = { pick(AllMusic.ID, it) },
            onTargetDone = { target = null },
        )
    } else if (shown != null) {
        PlaylistDetail(
            vm = vm,
            list = shown,
            onBack = { open = null },
            onRename = { renaming = shown },
            onDelete = { deleting = shown },
            query = query,
            target = target,
            onPick = { pick(shown.id, it) },
            onTargetDone = { target = null },
        )
    } else if (query.isNotBlank()) {
        PlaylistSearch(
            vm = vm,
            lists = lists,
            query = query,
            onOpenList = { id -> open = id; pane.openSearch(false) },
            onSong = { pick(AllMusic.ID, it) },
        )
    } else {
        PlaylistList(
            vm = vm,
            lists = lists,
            onOpen = { open = it },
            onPlay = { vm.playPlaylist(it) },
            onShuffle = { vm.playPlaylist(it, shuffle = true) },
            onBeatMaps = { vm.makeBeatMapsForPlaylist(it) },
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

/**
 * A track a search result asked for: bring it into view in its whole list, mark it, play it.
 * [tick] is fresh per ask, so asking for the same track twice marks it twice.
 */
internal data class SearchTarget(val path: VPath, val tick: Long)

/**
 * What the box finds at the top of Playlists: the playlists whose names match, then the songs.
 *
 * Songs come from the same reading of the media index All Music makes, by name, filtered the
 * same way, and match on the index's title, artist and album as well as the file name - the tags
 * a library is searched by, without opening two thousand files to read them. A tap on a song
 * opens All Music at it and plays from there, so Next walks All Music rather than the results.
 */
@Composable
private fun PlaylistSearch(
    vm: BrowserViewModel,
    lists: List<Playlist>,
    query: String,
    onOpenList: (String) -> Unit,
    onSong: (VPath) -> Unit,
) {
    val colors = Filet.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dirs by vm.prefs.allMusicFolders.collectAsState()
    val minMs by vm.prefs.allMusicMinMs.collectAsState()
    val maxMs by vm.prefs.allMusicMaxMs.collectAsState()
    var songs by remember { mutableStateOf<List<dev.niccc2007.filet.home.CategoryQuery.AudioRow>?>(null) }
    LaunchedEffect(dirs, minMs, maxMs) {
        songs = runCatching {
            dev.niccc2007.filet.home.CategoryQuery.readAudio(
                ctx, MusicSortKey.NAME.name, descending = false, folders = dirs, minMs = minMs, maxMs = maxMs,
            ).second
        }.getOrNull().orEmpty()
    }
    val hitLists = remember(lists, query) { lists.filter { dev.niccc2007.filet.ui.TextMatch.matches(query, it.name) } }
    val hitSongs = remember(songs, query) {
        songs.orEmpty().filter { dev.niccc2007.filet.ui.TextMatch.matches(query, it.title, it.artist, it.album, it.node.name) }
    }
    // Not kept: these are search results, and a search re-runs into a different order as it
    // answers, so a saved position would name a different row.
    LazyColumn(Modifier.fillMaxSize()) {
        if (hitLists.isNotEmpty()) {
            item { SectionRow("Playlists", note = "${hitLists.size}") }
            items(hitLists, key = { "l:" + it.id }) { list ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenList(list.id) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(vm.trackCards, list, 44.dp, radius = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        dev.niccc2007.filet.browser.ListNameText(
                            text = dev.niccc2007.filet.ui.highlighted(list.name, query, colors.accent),
                            color = MaterialTheme.colorScheme.onSurface,
                            startSp = 13.5f,
                            lines = 1,
                            isDir = true,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(if (list.size == 0) "Empty" else plural(list.size, "track", "tracks"), fontSize = 11.sp, color = colors.fg3)
                    }
                }
            }
        }
        item { SectionRow("Songs", note = songs?.let { "${hitSongs.size}" }) }
        when {
            songs == null -> item { DlgCaption("Reading the media index") }
            hitSongs.isEmpty() && hitLists.isEmpty() -> item { dev.niccc2007.filet.ui.NoMatch(query) }
            hitSongs.isEmpty() -> item { DlgCaption("No songs match.") }
        }
        items(hitSongs, key = { "s:" + it.node.path }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onSong(row.node.path) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Audio, null, tint = colors.fg3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    dev.niccc2007.filet.browser.ListNameText(
                        text = dev.niccc2007.filet.ui.highlighted(row.title ?: row.node.name, query, colors.accent),
                        color = MaterialTheme.colorScheme.onSurface,
                        startSp = 13f,
                        lines = 1,
                        isDir = row.title != null,
                    )
                    val by = listOfNotNull(row.artist, row.album).joinToString(" · ")
                    if (by.isNotEmpty()) {
                        dev.niccc2007.filet.browser.ListNameText(
                            text = dev.niccc2007.filet.ui.highlighted(by, query, colors.accent),
                            color = colors.fg3,
                            startSp = 10.5f,
                            lines = 1,
                            isDir = true,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── the shelf of lists ────────────────────────────────────────────────────────

/** All Music's banner: three rows of covers, about the height of a kept list's cover. */
private val MOSAIC_BANNER = 156.dp

@Composable
private fun PlaylistList(
    vm: BrowserViewModel,
    lists: List<Playlist>,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onShuffle: (String) -> Unit,
    onBeatMaps: (String) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onNew: () -> Unit,
) {
    val colors = Filet.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // A handful of real tracks for All Music's cover, so the built-in list looks like the ones
    // beside it instead of being the one grey square on the shelf.
    //
    // Read once per sort change and capped at six: `PlaylistCover` consults the first few
    // tracks to build its four-up, and reading more than it will draw is work for nothing. The
    // filter is honoured, so the cover is made of the music that is actually in the list.
    val dirs by vm.prefs.allMusicFolders.collectAsState()
    val minMs by vm.prefs.allMusicMinMs.collectAsState()
    val maxMs by vm.prefs.allMusicMaxMs.collectAsState()
    var allMusicHeads by remember { mutableStateOf<List<dev.niccc2007.filet.vfs.VPath>>(emptyList()) }
    LaunchedEffect(dirs, minMs, maxMs) {
        val read = runCatching {
            dev.niccc2007.filet.home.CategoryQuery.readAudio(
                ctx, MusicSortKey.ADDED.name, descending = true, limit = COVER_HEADS,
                folders = dirs, minMs = minMs, maxMs = maxMs,
            )
        }.getOrNull()
        allMusicHeads = read?.second?.map { it.node.path }.orEmpty()
    }
    // No wash behind the shelf. A tint over the whole page was colour standing in for design;
    // the page is now shaped instead - All Music as its banner, the kept lists under a heading.
    val mosaic = rememberAllMusicMosaic(vm)
    // Counted by the index under All Music's own filter, so the number on the banner is the
    // number of rows behind it.
    val trackCount by androidx.compose.runtime.produceState<Int?>(null, dirs, minMs, maxMs) {
        value = dev.niccc2007.filet.home.CategoryQuery.countAudio(ctx, dirs, minMs, maxMs)
    }
    val grid by vm.prefs.playlistsGrid.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The banner's Play and Shuffle start the same queue All Music's own buttons would: the
    // whole list, in its sort, from the top.
    fun playAllMusic(shuffle: Boolean) {
        scope.launch {
            val sort = AllMusic.sortOf(vm.prefs)
            val rows = runCatching {
                dev.niccc2007.filet.home.CategoryQuery.readAudio(
                    ctx, sort.key.name, sort.descending,
                    folders = dirs, minMs = minMs, maxMs = maxMs,
                ).second
            }.getOrNull().orEmpty()
            if (rows.isEmpty()) return@launch
            vm.playback.play(tracksOf(rows.map { it.node }), 0, asAudio = true, from = AllMusic.NAME)
            if (shuffle) vm.playback.setShuffle(true)
            vm.maximisePlayer()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("playlists")) {
        // All Music is the banner rather than the first row: it is the one list that is never
        // empty on a phone with music on it, and the door to everything else on the shelf.
        item(key = AllMusic.ID) {
            AllMusicBanner(
                vm = vm,
                mosaic = mosaic,
                heads = allMusicHeads,
                tracks = trackCount,
                onOpen = { onOpen(AllMusic.ID) },
                onPlay = { playAllMusic(shuffle = false) },
                onShuffle = { playAllMusic(shuffle = true) },
            )
        }
        item(key = "shelf-heading") {
            ShelfHeading(grid = grid, onGrid = { vm.prefs.setPlaylistsGrid(it) }, onNew = onNew)
        }
        if (grid) {
            items(lists.chunked(2), key = { pair -> pair.joinToString("|") { it.id } }) { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (list in pair) {
                        PlaylistCard(
                            vm, list, Modifier.weight(1f),
                            onOpen, onPlay, onShuffle, onBeatMaps, onRename, onDelete,
                        )
                    }
                    // A lone last card keeps its column's width rather than stretching across.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (!grid) items(lists, key = { it.id }) { list ->
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
                    ShelfMenu(list, menu, { menu = false }, onOpen, onPlay, onShuffle, onBeatMaps, onRename, onDelete)
                }
                PlaylistCover(vm.trackCards, list, 54.dp, radius = 13.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    dev.niccc2007.filet.browser.ListNameText(
                        name = list.name,
                        color = androidx.compose.material3.LocalContentColor.current,
                        startSp = 14.5f,
                        isDir = true,
                        fontWeight = FontWeight.SemiBold,
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
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * What can be done to one kept list, from a held row or a held card.
 *
 * Declared once so the two shapes of the shelf cannot offer different things.
 */
@Composable
private fun ShelfMenu(
    list: Playlist,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onShuffle: (String) -> Unit,
    onBeatMaps: (String) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    PopMenu(expanded = expanded, onDismiss = onDismiss, below = 8.dp) {
        PopRow(FiletIcons.Open, "Open") { onDismiss(); onOpen(list.id) }
        if (list.size > 0) {
            PopRow(FiletIcons.Play, "Play") { onDismiss(); onPlay(list.id) }
            PopRow(FiletIcons.Shuffle, "Shuffle") { onDismiss(); onShuffle(list.id) }
            // Ahead of time, for the whole list. The pulse is then ready when the list is put
            // on, instead of each track being measured while somebody waits for it.
            // Already-measured tracks are skipped without being read.
            PopRow(FiletIcons.Audio, "Make beat maps") { onDismiss(); onBeatMaps(list.id) }
        }
        // The two a built-in list does not have: Favourites is not a playlist somebody made and
        // cannot be renamed out of existence.
        if (!list.builtIn) {
            PopSeparator()
            PopRow(FiletIcons.Rename, "Rename") { onDismiss(); onRename(list) }
            PopRow(FiletIcons.Delete, "Delete", danger = true) { onDismiss(); onDelete(list) }
        }
    }
}

/**
 * All Music, as the top of the Playlists tab.
 *
 * With enough covers it is the wall of them, fading into the page, with the name, the counts
 * and the two buttons over its foot. Under [CoverMosaic.HOME_WALL_MIN] distinct covers the wall
 * would be a few pictures repeated, so it is the same words and buttons beside the four-up
 * instead - a banner that still says what it is, without the decoration it cannot fill.
 */
@Composable
private fun AllMusicBanner(
    vm: BrowserViewModel,
    mosaic: Mosaic,
    heads: List<dev.niccc2007.filet.vfs.VPath>,
    tracks: Int?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val colors = Filet.colors
    val wall = mosaic.full
    // The track count alone. The number of playlists sat beside it, and under the All Music
    // heading it read as All Music holding that many playlists - which it does not; it holds
    // every track, and the playlists are the shelf below. Empty while the count is still being
    // read, which keeps the line's height so the row does not jump when it arrives.
    val figures = tracks?.let { plural(it, "track", "tracks") }.orEmpty()
    Box(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        if (wall) MosaicBanner(mosaic, Modifier.fillMaxWidth().height(MOSAIC_BANNER + 28.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp, end = 14.dp,
                    // The words sit where the wall has faded, so they are read against the page.
                    top = if (wall) MOSAIC_BANNER - 44.dp else 12.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!wall) {
                PlaylistCover(
                    cards = vm.trackCards,
                    list = Playlist(id = AllMusic.ID, name = AllMusic.NAME, tracks = heads, builtIn = true),
                    side = 64.dp,
                    radius = 14.dp,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                // The chevron is what says the banner opens. Without it the wall reads as a
                // picture at the top of the page: the whole banner was tappable and nothing on
                // it said so. Same mark as a row that leads somewhere.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(AllMusic.NAME, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(FiletIcons.Chevron, null, tint = colors.fg3, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(figures, fontSize = 11.5.sp, color = colors.fg3, maxLines = 1)
            }
            if ((tracks ?: 1) > 0) {
                ShufflePill(colors.accent, onShuffle)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.accent).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FiletIcons.PlaySolid, "Play All Music", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** "PLAYLISTS", the list or grid switch, and the way to make another. */
@Composable
private fun ShelfHeading(grid: Boolean, onGrid: (Boolean) -> Unit, onNew: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "PLAYLISTS",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = colors.fg3,
            modifier = Modifier.weight(1f),
        )
        // One switch showing the shape it will change TO, the convention every gallery uses.
        Icon(
            if (grid) FiletIcons.Rows else FiletIcons.Grid,
            if (grid) "Show as a list" else "Show as a grid",
            tint = colors.fg2,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { onGrid(!grid) }.padding(8.dp),
        )
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onNew).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FiletIcons.Plus, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("New", fontSize = 12.5.sp, color = colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One kept list as a card in the grid: the cover large, the play button on it, the name under. */
@Composable
private fun PlaylistCard(
    vm: BrowserViewModel,
    list: Playlist,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onShuffle: (String) -> Unit,
    onBeatMaps: (String) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val colors = Filet.colors
    var menu by remember(list.id) { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = { onOpen(list.id) }, onLongClick = { menu = true })
            .padding(4.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth
            PlaylistCover(vm.trackCards, list, side, radius = 14.dp)
            if (list.size > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable { onPlay(list.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FiletIcons.PlaySolid, "Play ${list.name}", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                }
            }
            ShelfMenu(list, menu, { menu = false }, onOpen, onPlay, onShuffle, onBeatMaps, onRename, onDelete)
        }
        Spacer(Modifier.height(7.dp))
        Text(list.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            if (list.size == 0) "Empty" else plural(list.size, "track", "tracks"),
            fontSize = 11.sp,
            color = colors.fg3,
        )
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
    query: String,
    target: SearchTarget?,
    onPick: (VPath) -> Unit,
    onTargetDone: () -> Unit,
) {
    val colors = Filet.colors
    // Without the position: this list - every row and its card lookup - recomposed five
    // times a second off the ticker while anything played. See `Playback.status`.
    val state by rememberStatus(vm.playback)
    // The bars are an infinite transition, so they stop under a viewer opened over this pane
    // as well as on pause. See [shouldAnimate].
    val covered = vm.openRequest.collectAsStateWithLifecycle().value != null
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

    // Which entries no longer stat. Probed when the list is opened and whenever its contents
    // change - a track removed from somewhere else leaves a stale mark otherwise - and read
    // live, so the "clean up" banner disappears the moment it has done its job.
    val missing by vm.missingTracks.collectAsState()
    LaunchedEffect(list.id, list.tracks.size) { vm.checkPlaylistTracks(list.id) }
    val goneHere = remember(missing, list.tracks) { list.tracks.count { it.toString() in missing } }
    val cleanable = remember(missing, list.tracks) {
        list.tracks.count { it.toString() in missing && PlaylistHealth.removable(it.toString()) }
    }

    // Selecting several tracks at once, to file them somewhere else in one go.
    //
    // Held by path string rather than by index, for the same reason playing is: the list under
    // it changes - a reorder, a clean-up, a track taken out - and a set of positions survives
    // none of those while a set of paths survives all of them.
    var picked by remember(list.id) { mutableStateOf(setOf<String>()) }
    val selecting = picked.isNotEmpty()
    // Dropped when the list changes under it, so a removal cannot leave a count that no longer
    // matches anything on screen.
    LaunchedEffect(list.tracks) { picked = picked.intersect(list.tracks.map { it.toString() }.toSet()) }

    // The bar floats over the list rather than scrolling with it - see [SelectionBar] - and
    // the list is padded by exactly the bar's measured height, so the last rows can always be
    // scrolled clear of it.
    var barPx by remember { mutableIntStateOf(0) }
    val barPad = with(LocalDensity.current) { (if (selecting) barPx else 0).toDp() }
    val listState = dev.niccc2007.filet.browser.rememberKeptListState("playlist:" + list.id)
    // While the box has letters in it: the tracks whose title, artist, album or file name has
    // them, in list order. Reordering is off while narrowed - up and down would mean "past
    // rows that are hidden", which nobody could see happen.
    val narrowed = query.isNotBlank()
    val showing = remember(list.tracks, cards, query) {
        list.tracks.withIndex().filter { (i, p) ->
            !narrowed || dev.niccc2007.filet.ui.TextMatch.matches(query, cards[i].title, cards[i].artist, cards[i].album, p.name)
        }
    }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        val i = list.tracks.indexOf(t.path)
        if (i < 0) { onTargetDone(); return@LaunchedEffect }
        // The header, and the clean-up line when it is showing, sit above the first track.
        val above = 1 + if (goneHere > 0 && !selecting) 1 else 0
        listState.animateScrollToItem(above + i)
        vm.playPlaylistTrack(list.id, t.path)
        kotlinx.coroutines.delay(dev.niccc2007.filet.ui.PULSE_MS.toLong())
        onTargetDone()
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = barPad)) {
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
        // A list that still shows files somebody deleted is half the defect; the other half was
        // that the queue silently dropped them while the rows kept their positions. Both are
        // repaired by the same thing - knowing which entries are gone - so the banner and the
        // tap read the same set. See `PlaylistHealth`.
        if (goneHere > 0 && !selecting) {
            item(key = "gone") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(FiletIcons.Warn, null, tint = colors.warn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (goneHere == 1) {
                            "1 track is no longer where it was."
                        } else {
                            "$goneHere tracks are no longer where they were."
                        },
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.weight(1f),
                    )
                    // Only when at least one of them is a file on this phone. A share that did
                    // not answer offers nothing to clean up, and a button that would refuse is
                    // worse than no button.
                    if (cleanable > 0 && !list.builtIn) {
                        Text(
                            "Clean up",
                            fontSize = 11.sp,
                            color = colors.accent,
                            modifier = Modifier
                                .clickable { vm.prunePlaylistMissing(list.id) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        if (narrowed && showing.isEmpty() && list.size > 0) item { dev.niccc2007.filet.ui.NoMatch(query) }
        items(showing, key = { it.value.toString() }) { (i, path) ->
            val mine = state.current?.id == path.toString()
            Box(Modifier.highlightPulse(target?.takeIf { it.path == path }?.tick)) {
            TrackRow(
                path = path,
                card = cards[i],
                playing = mine && state.active,
                sounding = mine && state.playing && !covered,
                tint = tint,
                missing = path.toString() in missing,
                selected = if (selecting) path.toString() in picked else null,
                // By PATH, never by row number: the queue leaves out what has gone, so handing
                // over `i` played the song N places further down, N being the number of dead
                // entries above the tap.
                //
                // While a selection is open a tap picks rather than plays, which is how every
                // other list in this app behaves - a list where a tap means two different
                // things depending on a mode is one where the mode has to be visible, and the
                // selection bar is what makes it so.
                onPlay = {
                    if (selecting) {
                        picked = if (path.toString() in picked) picked - path.toString()
                        else picked + path.toString()
                    } else if (narrowed) {
                        onPick(path)
                    } else {
                        vm.playPlaylistTrack(list.id, path)
                    }
                },
                onSelect = {
                    picked = if (path.toString() in picked) picked - path.toString()
                    else picked + path.toString()
                },
                onUp = if (i > 0 && !narrowed) ({ vm.playlists.move(list.id, i, i - 1) }) else null,
                onDown = if (i < list.size - 1 && !narrowed) {
                    ({ vm.playlists.move(list.id, i, i + 1) })
                } else {
                    null
                },
                onRemove = { vm.playlists.remove(list.id, path) },
                onAddTo = { vm.askAddToPlaylistPath(path) },
                onReveal = { vm.revealPathInFolder(path) },
                onBeatMap = { vm.makeBeatMapForPath(path) },
            )
            }
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
        if (selecting) {
            SelectionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onHeight = { barPx = it },
                    count = picked.size,
                    all = picked.size >= list.size && list.size > 0,
                    canRemove = !list.builtIn,
                    tint = tint,
                    onAll = {
                        picked = if (picked.size >= list.size) emptySet()
                        else list.tracks.map { it.toString() }.toSet()
                    },
                    onAddTo = {
                        vm.askAddToPlaylistPaths(list.tracks.filter { it.toString() in picked })
                        picked = emptySet()
                    },
                    onRemove = {
                        for (p in list.tracks.filter { it.toString() in picked }) {
                            vm.playlists.remove(list.id, p)
                        }
                        picked = emptySet()
                    },
                    onClear = { picked = emptySet() },
                )
        }
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
            dev.niccc2007.filet.browser.ListNameText(
                text = androidx.compose.ui.text.AnnotatedString(list.name),
                color = androidx.compose.material3.LocalContentColor.current,
                startSp = 23f,
                lines = 2,
                isDir = true,
                fontWeight = FontWeight.Bold,
                lineHeightFor = { it + 5f },
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
    /**
     * The file behind this entry did not stat.
     *
     * Drawn rather than hidden, because a row that disappears on its own gives nobody the
     * chance to ask where it went - and on a share "did not stat" can mean the server is
     * asleep, in which case removing the row would be destroying the playlist over a cable.
     */
    missing: Boolean,
    /**
     * Null when no selection is open; true or false while one is.
     *
     * Three states rather than a boolean, because the row looks different in all three: no
     * selection draws the cover, a selection draws the cover with a tick over it, and an
     * unpicked row during a selection has to show that it COULD be picked.
     */
    selected: Boolean? = null,
    onPlay: () -> Unit,
    /** Long press, which starts a selection or adds to the one already open. */
    onSelect: (() -> Unit)? = null,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    /** Null where taking a track out means nothing - All Music is a query, not a list. */
    onRemove: (() -> Unit)?,
    onAddTo: () -> Unit,
    onReveal: () -> Unit,
    /** Measure this one track's beat, away from any screen. Null where it makes no sense. */
    onBeatMap: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected == true -> tint.copy(alpha = 0.22f)
                    playing -> tint.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            // Long press selects, matching every other list in the app; the menu is on the
            // dots. It used to be the other way round, which meant the one gesture that means
            // "I want several of these" did something else here than everywhere else.
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onSelect ?: { menu = true },
            )
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
            if (selected != null) {
                // Over the cover rather than beside it. A column of boxes in front of the
                // artwork would push every row across and make the list a different shape in
                // selection mode than out of it.
                Box(
                    Modifier.fillMaxSize().background(
                        Color.Black.copy(alpha = if (selected) 0.30f else 0.46f),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) FiletIcons.Check else FiletIcons.Circle, null,
                        tint = if (selected) tint else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(if (selected) 20.dp else 15.dp),
                    )
                }
            } else if (playing) {
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
            dev.niccc2007.filet.browser.ListNameText(
                // The strike rides on the text, so a missing track is crossed out at whatever
                // size its title fits at.
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append(dev.niccc2007.filet.ui.highlighted(card.title, dev.niccc2007.filet.ui.LocalPaneQuery.current, tint))
                    if (missing) addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough), 0, length)
                },
                startSp = 13f,
                lines = 1,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    missing -> colors.fg3
                    playing -> tint
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Said on the row rather than left to be discovered: a video in a playlist is
                // deliberate, and the badge is also the answer to "why has that one got no
                // artist" before anybody asks it.
                if (card.video && !missing) {
                    Icon(
                        FiletIcons.Video, null,
                        tint = colors.fg3, modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                if (missing) {
                    Icon(
                        FiletIcons.Warn, null,
                        tint = colors.warn, modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                dev.niccc2007.filet.browser.ListNameText(
                    name = if (missing) {
                        // Two different facts, because the repair is different: one is a
                        // deleted file and the other is a share that did not answer.
                        if (PlaylistHealth.removable(path.toString())) {
                            "The file is no longer on this device"
                        } else {
                            "Out of reach - the place it is on did not answer"
                        }
                    } else {
                        card.byline(path)
                    },
                    startSp = 10.5f,
                    color = if (missing) colors.warn else colors.fg3,
                    isDir = true,
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
            PopMenu(expanded = menu, onDismiss = { menu = false }) {
                onUp?.let { PopRow(FiletIcons.Up, "Move up") { menu = false; it() } }
                onDown?.let { PopRow(FiletIcons.Down, "Move down") { menu = false; it() } }
                PopRow(FiletIcons.Plus, "Add to another playlist") { menu = false; onAddTo() }
                PopRow(FiletIcons.FolderOpen, "Open containing folder") { menu = false; onReveal() }
                onBeatMap?.let { PopRow(FiletIcons.Audio, "Make beat map") { menu = false; it() } }
                onRemove?.let {
                    PopSeparator()
                    PopRow(FiletIcons.Close, "Take out of this playlist") { menu = false; it() }
                }
            }
        }
    }
}

/**
 * What a multi-selection can do, floating over the bottom of the list.
 *
 * It used to be the list's second item, under the playlist header - so after selecting tracks
 * further down, filing them meant scrolling all the way back up to find it. Now it is a card
 * pinned to the bottom of the screen, where the thumb that just selected is, and it stays put
 * however far the list scrolls. The list is padded by the card's measured height ([onHeight]),
 * so nothing ends up permanently underneath it: the last row scrolls clear like any other.
 *
 * "Add to playlist" opens the same tick-box dialogue a single track opens, which is what makes
 * filing forty tracks into a list the same gesture as filing one - see [AddToPlaylistDialog].
 */
@Composable
private fun SelectionBar(
    count: Int,
    all: Boolean,
    canRemove: Boolean,
    tint: Color,
    onAll: () -> Unit,
    onAddTo: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
) {
    val colors = Filet.colors
    // The margin counts as part of the height, so the list's padding includes the gap too.
    DisposableEffect(Unit) { onDispose { onHeight(0) } }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .background(tint.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FiletIcons.Close, "Stop selecting",
                tint = colors.fg2,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClear).padding(7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$count selected",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            BarAction(if (all) "None" else "All", colors.fg2, onAll)
            if (canRemove) BarAction("Take out", colors.warn, onRemove)
            BarAction("Add to…", colors.accent, onAddTo)
        }
    }
}

@Composable
private fun BarAction(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.5.sp,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
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

// ── All Music ─────────────────────────────────────────────────────────────────

/**
 * Every audio file, sorted, played as a real queue in the order on screen.
 *
 * Re-read on open and whenever the sort changes - see [AllMusic] for why it is never stored.
 */
@Composable
private fun AllMusicDetail(
    vm: BrowserViewModel,
    onBack: () -> Unit,
    query: String,
    target: SearchTarget?,
    onPick: (VPath) -> Unit,
    onTargetDone: () -> Unit,
) {
    val colors = Filet.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sort by remember { mutableStateOf(AllMusic.sortOf(vm.prefs)) }
    var read by remember { mutableStateOf<Pair<dev.niccc2007.filet.home.CategoryResult, List<dev.niccc2007.filet.home.CategoryQuery.AudioRow>>?>(null) }
    // What qualifies. Read live and keyed into the query, so changing the filter re-reads
    // rather than waiting for the screen to be left and come back. See `AllMusicFilter`.
    val dirs by vm.prefs.allMusicFolders.collectAsState()
    val minMs by vm.prefs.allMusicMinMs.collectAsState()
    val maxMs by vm.prefs.allMusicMaxMs.collectAsState()
    var filtering by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(sort, dirs, minMs, maxMs) {
        read = null
        read = dev.niccc2007.filet.home.CategoryQuery.readAudio(
            ctx, sort.key.name, sort.descending,
            folders = dirs, minMs = minMs, maxMs = maxMs,
        )
    }
    val state by rememberStatus(vm.playback)
    val covered = vm.openRequest.collectAsStateWithLifecycle().value != null
    val rows = read?.second.orEmpty()

    fun playFrom(i: Int, maximise: Boolean = true) {
        if (rows.isEmpty()) return
        // The queue IS the list on screen, in its sort, so Next walks the order being looked at.
        vm.playback.play(tracksOf(rows.map { it.node }), i.coerceIn(0, rows.lastIndex), asAudio = true, from = AllMusic.NAME)
        if (maximise) vm.maximisePlayer()
    }

    // Narrowed by the box: title, artist or album as the media index has them, or the file
    // name. The queue a tap starts is still the whole list - see [onPick].
    val narrowed = query.isNotBlank()
    val shown = remember(rows, query) {
        rows.withIndex().filter { (_, r) ->
            !narrowed || dev.niccc2007.filet.ui.TextMatch.matches(query, r.title, r.artist, r.album, r.node.name)
        }
    }

    // Selecting several at once, the same way the kept lists do it. This is the list it matters
    // most on: filing twenty songs off the device into a new playlist used to be twenty trips
    // through the row menu.
    var picked by remember { mutableStateOf(setOf<String>()) }
    val selecting = picked.isNotEmpty()
    fun flip(id: String) { picked = if (id in picked) picked - id else picked + id }

    // Wrapped so the fast scroller can sit over the list. All Music runs to two thousand
    // rows, where a flick is about a screen and reaching a letter is luck.
    val listState = dev.niccc2007.filet.browser.rememberKeptListState("allmusic")
    var barPx by remember { mutableIntStateOf(0) }
    val barPad = with(LocalDensity.current) { (if (selecting) barPx else 0).toDp() }
    // A search result's track: brought into view in the full list, marked, and played from -
    // with the player left down, so the mark is what is looked at.
    LaunchedEffect(target, rows) {
        val t = target ?: return@LaunchedEffect
        if (read == null) return@LaunchedEffect
        val i = rows.indexOfFirst { it.node.path == t.path }
        if (i < 0) { onTargetDone(); return@LaunchedEffect }
        listState.animateScrollToItem(1 + i)
        playFrom(i, maximise = false)
        kotlinx.coroutines.delay(dev.niccc2007.filet.ui.PULSE_MS.toLong())
        onTargetDone()
    }
    // The banner every kept list has, made of this list's own covers. See [CoverMosaic].
    val mosaic = rememberAllMusicMosaic(vm)
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = barPad)) {
        item {
            Box(Modifier.fillMaxWidth()) {
            MosaicBanner(mosaic, Modifier.fillMaxWidth().height(MOSAIC_BANNER))
            Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)) {
                Icon(
                    FiletIcons.Back, "Back to the playlists",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(10.dp),
                )
                // Down to where the wall has faded, so the name is read against the page.
                if (mosaic.full) Spacer(Modifier.height(MOSAIC_BANNER - 76.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(AllMusic.NAME, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                        Text(
                            when {
                                read == null -> "Reading the media index"
                                rows.isEmpty() -> "Nothing matches the filter"
                                else -> plural(rows.size, "track", "tracks") + "  ·  " +
                                    AllMusicFilter.clock(minMs) + " to " + AllMusicFilter.clock(maxMs)
                            },
                            fontSize = 11.sp, color = colors.fg3,
                            maxLines = 1,
                        )
                    }
                    // Always offered, including when the list is empty - an empty list is the
                    // moment somebody most needs to see what is being filtered out.
                    Icon(
                        FiletIcons.Sort, "Filter",
                        tint = colors.fg2,
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                            .clickable { filtering = true }.padding(9.dp),
                    )
                    if (rows.isNotEmpty()) {
                        ShufflePill(colors.accent) {
                            playFrom(0)
                            vm.playback.setShuffle(true)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(colors.accent).clickable { playFrom(0) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(FiletIcons.PlaySolid, "Play All Music", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Sideways, with a fade at the edge so the hidden chips are announced.
                dev.niccc2007.filet.ui.HScroll(
                    ground = MaterialTheme.colorScheme.background,
                    contentPadding = 8.dp,
                    spacing = 6.dp,
                ) {
                        for (k in MusicSortKey.entries) {
                            val on = k == sort.key
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (on) colors.accent.copy(alpha = 0.18f) else colors.sunken)
                                    .clickable {
                                        sort = sort.pick(k)
                                        AllMusic.saveSort(vm.prefs, sort)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    k.label + if (on) (if (sort.descending) "  ↓" else "  ↑") else "",
                                    fontSize = 12.sp,
                                    color = if (on) colors.accent else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                }
            }
            }
        }
        when (val r = read?.first) {
            null -> Unit
            dev.niccc2007.filet.home.CategoryResult.Refused -> item {
                DlgCaption("Filet needs access to files to see the music on this device.")
                Text(
                    "Allow access", fontSize = 13.sp, color = colors.accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { vm.requestAllFiles() },
                )
            }
            is dev.niccc2007.filet.home.CategoryResult.Failed -> item { DlgCaption("The media index refused the query: ${r.reason}") }
            dev.niccc2007.filet.home.CategoryResult.Empty -> item {
                // An empty list is never just "no audio files" now - the filter is far more
                // likely to be the reason than the phone being empty, and an empty screen that
                // does not say which is indistinguishable from the feature being broken. So it
                // names what is being scanned and offers the one tap out of it.
                if (dirs.isNotEmpty() || minMs > 0L || maxMs > 0L) {
                    DlgCaption(
                        "Nothing matched. Scanning " +
                            (if (dirs.isEmpty()) "everywhere" else dirs.joinToString(", ")) +
                            ", " + AllMusicFilter.clock(minMs) + " to " + AllMusicFilter.clock(maxMs) + ".",
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "Scan everywhere",
                            fontSize = 12.5.sp,
                            color = colors.accent,
                            modifier = Modifier.clickable {
                                vm.prefs.setAllMusicFolders(emptyList())
                                vm.prefs.setAllMusicMinMs(0L)
                                vm.prefs.setAllMusicMaxMs(0L)
                            }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp),
                        )
                        Text(
                            "Change the filter",
                            fontSize = 12.5.sp,
                            color = colors.fg2,
                            modifier = Modifier.clickable { filtering = true }
                                .padding(vertical = 4.dp),
                        )
                    }
                } else {
                    DlgCaption("No audio files on this device's shared storage.")
                }
            }
            is dev.niccc2007.filet.home.CategoryResult.Found -> Unit
        }
        if (narrowed && shown.isEmpty() && rows.isNotEmpty()) item { dev.niccc2007.filet.ui.NoMatch(query) }
        items(shown, key = { it.value.node.path.toString() }) { (i, row) ->
            val path = row.node.path
            val mine = state.current?.id == path.toString()
            Box(Modifier.highlightPulse(target?.takeIf { it.path == path }?.tick)) {
            TrackRow(
                path = path,
                // Per visible row: artwork for two thousand tracks is never read up front.
                card = rememberTrackCard(vm.trackCards, path).value,
                playing = mine && state.active,
                sounding = mine && state.playing && !covered,
                tint = colors.accent,
                // Never: All Music is a live query over the media index, so an entry in it is
                // a row the index just returned rather than a path saved last month.
                missing = false,
                selected = if (selecting) path.toString() in picked else null,
                onPlay = {
                    when {
                        selecting -> flip(path.toString())
                        narrowed -> onPick(path)
                        else -> playFrom(i)
                    }
                },
                onSelect = { flip(path.toString()) },
                onUp = null,
                onDown = null,
                onRemove = null,
                onAddTo = { vm.askAddToPlaylistPath(path) },
                onReveal = { vm.revealPathInFolder(path) },
                onBeatMap = { vm.makeBeatMapForPath(path) },
            )
            }
        }
        if (rows.size >= dev.niccc2007.filet.home.CategoryQuery.LIMIT) {
            item { DlgCaption("Showing the first ${rows.size} by ${sort.key.label.lowercase()}. Change the sort to reach the rest.") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

        // The scrubber, over the list. Its bubble is decided by the ORDERING - a letter when
        // sorted by name, a spelled date when by date, a running time when by length - which
        // is the whole reason it is useful. See `MusicScrollLabel`.
        //
        // The index is offset by the header rows the list puts above the tracks, so the thumb
        // points at the row it names rather than at one a few places off. The header is one
        // item, always: the selection bar floats over the list now instead of being a row in it.
        // Stated as an argument because a comment saying so did not make it true.
        // The rows just outside the screen, warmed once the list stops. `warm` is the low tier
        // of the same queue the visible rows use, so this never delays a row somebody is
        // looking at - see LoadQueue.
        dev.niccc2007.filet.ui.WarmAhead(
            state = listState,
            count = shown.size,
            offset = 1,
        ) { i -> shown.getOrNull(i)?.let { vm.trackCards.warm(it.value.node.path) } }

        FastScroller(
            state = listState,
            itemCount = shown.size,
            indexOffset = 1,
            labelAt = { i ->
                shown.getOrNull(i)?.value?.let { row ->
                    MusicScrollLabel.of(
                        key = sort.key,
                        name = row.node.name,
                        addedAtMs = row.addedAtMs,
                        modifiedAtMs = row.node.mtime,
                        sizeBytes = row.node.size,
                        durationMs = row.durationMs,
                    )
                }
            },
        )
        // Over the list and the scroller, at the bottom where the thumb already is - see
        // [SelectionBar]. Drawn last so it is on top.
        if (selecting) {
            SelectionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onHeight = { barPx = it },
                    count = picked.size,
                    all = picked.size >= rows.size && rows.isNotEmpty(),
                    // Nothing to take out of: All Music is a query over the media index, not a
                    // list somebody built, so "take out" would mean deleting the file.
                    canRemove = false,
                    tint = colors.accent,
                    onAll = {
                        picked = if (picked.size >= rows.size) emptySet()
                        else rows.map { it.node.path.toString() }.toSet()
                    },
                    onAddTo = {
                        vm.askAddToPlaylistPaths(
                            rows.map { it.node.path }.filter { it.toString() in picked },
                        )
                        picked = emptySet()
                    },
                    onRemove = {},
                    onClear = { picked = emptySet() },
                )
        }
    }

    if (filtering) AllMusicFilterDialog(vm) { filtering = false }
}

/**
 * What All Music counts as music.
 *
 * Two questions, asked here rather than buried in Settings, because the only moment anybody
 * wants to change them is while looking at a list that has too much or too little in it. The
 * reasoning for both - and why "every audio file on the device" was never a usable list - is in
 * [AllMusicFilter].
 */
@Composable
private fun AllMusicFilterDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val dirs by vm.prefs.allMusicFolders.collectAsState()
    val minMs by vm.prefs.allMusicMinMs.collectAsState()
    val maxMs by vm.prefs.allMusicMaxMs.collectAsState()

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Audio,
            title = "What counts as music",
            sub = "Folders to scan, and how long a track has to be",
            onClose = onDismiss,
        )
        DlgBody {
            DlgSection("Folders")
            if (dirs.isEmpty()) {
                DlgCaption("Nothing listed, so the whole device is scanned - tones and voice notes included.")
            }
            for (d in dirs) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(FiletIcons.Folder, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(10.dp))
                    dev.niccc2007.filet.browser.ListNameText(
                        name = d,
                        color = MaterialTheme.colorScheme.onSurface,
                        startSp = 12f,
                        isDir = true,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        FiletIcons.Close, "Stop scanning $d",
                        tint = colors.fg3,
                        modifier = Modifier.size(26.dp).clip(CircleShape)
                            .clickable { vm.prefs.removeAllMusicFolder(d) }.padding(7.dp),
                    )
                }
            }
            DlgAction(FiletIcons.Plus, "Add a folder", onClick = { onDismiss(); vm.pickAllMusicFolder() })

            DlgSection("Length")
            // Sliders rather than a text field: these are a judgement about what a track feels
            // like, not a number anybody knows, and the readout names the answer in minutes.
            DurationRow("At least", minMs) { vm.prefs.setAllMusicMinMs(it) }
            DurationRow("At most", maxMs) { vm.prefs.setAllMusicMaxMs(it) }
            DlgCaption(
                "Either end at 0:00 means no limit. A track whose length the index never read " +
                    "is kept either way.",
            )
        }
        DlgFooter {
            DlgBtn("Reset") { vm.prefs.resetAllMusicFilter() }
            DlgSpacer()
            DlgBtn("Done", kind = BtnKind.PRIMARY, onClick = onDismiss)
        }
    }
}

/** One length bound, in whole fifteen-second steps so the thumb lands somewhere sayable. */
@Composable
private fun DurationRow(label: String, value: Long, onChange: (Long) -> Unit) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(AllMusicFilter.clock(value), fontSize = 12.sp, color = colors.accent)
        }
        androidx.compose.material3.Slider(
            value = (value.toFloat() / AllMusicFilter.CEILING_MS).coerceIn(0f, 1f),
            onValueChange = {
                val raw = (it * AllMusicFilter.CEILING_MS).toLong()
                onChange(raw / STEP_MS * STEP_MS)
            },
        )
    }
}

/** Fifteen seconds. Finer than that is a thumb's width of difference nobody meant. */
private const val STEP_MS = 15_000L

/** How many tracks All Music's cover is built from. `PlaylistCover` draws at most four. */
private const val COVER_HEADS = 6
