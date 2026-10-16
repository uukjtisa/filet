package dev.niccc2007.filet.playback

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
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

/**
 * Where do these go.
 *
 * One tap per row, because adding to a playlist has no second question - there is nothing to
 * confirm and no state to set first. The footer therefore holds only Cancel and the way to
 * make a new list; a Save button would have nothing left to save.
 *
 * A row says when the track is already in that list rather than hiding it. Hiding it would
 * answer "why is Favourites not in this dialogue" with silence, and a greyed row that states
 * the reason is the shorter conversation.
 */
@Composable
fun AddToPlaylistDialog(vm: BrowserViewModel) {
    val nodes by vm.playlistFor.collectAsStateWithLifecycle()
    if (nodes.isEmpty()) return
    val lists by vm.playlists.items.collectAsStateWithLifecycle()
    val colors = Filet.colors
    var making by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    val paths = nodes.map { it.path }
    val dismiss = { vm.dismissPlaylistPicker() }

    Dlg(onDismiss = dismiss) {
        DlgHeader(
            icon = FiletIcons.Rows,
            title = if (nodes.size == 1) "Add to playlist" else "Add ${nodes.size} to playlist",
            sub = if (nodes.size == 1) nodes.first().name else null,
            onClose = dismiss,
        )
        DlgBody {
            if (making) {
                DlgField(label = "Name", value = name, onValue = { name = it })
                DlgCaption("The tracks go straight in.")
            } else {
                for (list in lists) {
                    // How many of this selection are already there - the number that decides
                    // whether pressing the row does anything.
                    val have = paths.count { list.contains(it) }
                    val all = have == paths.size
                    PlaylistRow(
                        cards = vm.trackCards,
                        list = list,
                        detail = when {
                            all && paths.size == 1 -> "Already in this playlist"
                            all -> "All ${paths.size} are already here"
                            have > 0 -> "${paths.size - have} to add  ·  $have already here"
                            list.size == 0 -> "Empty"
                            else -> dev.niccc2007.filet.browser.plural(list.size, "track", "tracks")
                        },
                        dim = all,
                    ) { if (!all) vm.addToPlaylist(list.id, nodes) }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .clickable { making = true }
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        FiletIcons.Plus, null,
                        tint = colors.accent, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(13.dp))
                    Text("New playlist", fontSize = 12.5.sp, color = colors.accent)
                }
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = dismiss)
            if (making) {
                DlgSpacer()
                DlgBtn("Create", kind = BtnKind.PRIMARY) { vm.newPlaylistWith(name, nodes) }
            } else {
                // Nothing to press: tapping a row IS the commit, so a second button here
                // would be a control with nothing left to do.
                DlgSpacer()
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    cards: TrackCards,
    list: Playlist,
    detail: String,
    dim: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = !dim, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The list's own cover, the same four-up the tab draws. A column of identical
        // grey squares is a list of names with an ornament in front of each; a column of
        // covers is a list somebody recognises without reading it.
        PlaylistCover(cards, list, 44.dp, radius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                list.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dim) colors.fg3 else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 10.sp, color = colors.fg3, maxLines = 1)
        }
        if (dim) {
            Icon(
                FiletIcons.Check, null,
                tint = colors.fg3, modifier = Modifier.size(14.dp),
            )
        }
    }
}
