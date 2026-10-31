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
 * Which playlists these are in.
 *
 * ## What this was, and why it is not that any more
 *
 * It was a list of buttons: tapping a row added the tracks and closed the dialogue, and a row
 * for a list the track was already in was greyed out and inert. Two things were wrong with it,
 * and they are the same thing from two sides.
 *
 * A dialogue that closes on the first tap cannot file one track into three playlists, which is
 * the ordinary case - so filing a song into three lists meant opening the same dialogue three
 * times from the same overflow menu.
 *
 * And a greyed row is a dead end. The dialogue was the only screen in the app that knew which
 * playlists hold a given track, and the one thing it would not let you do was take it out of
 * one - for that you had to remember which list it was in, open it, find the track in it, and
 * use the row menu there.
 *
 * So the rows are **tick boxes over membership** rather than buttons that perform an action. A
 * ticked box means the track is in that list; clearing it takes the track out. The dialogue
 * stays open until it is dismissed, and nothing is deferred behind a Save - every tap is the
 * edit, the same way the rest of this app treats a switch.
 *
 * With several tracks selected a box has three states, and which way a partial box resolves is
 * a real decision with a real argument behind it: see [PlaylistMembership].
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
            title = if (nodes.size == 1) "Playlists" else "Playlists for ${nodes.size} tracks",
            sub = if (nodes.size == 1) nodes.first().name else null,
            onClose = dismiss,
        )
        DlgBody {
            if (making) {
                DlgField(label = "Name", value = name, onValue = { name = it })
                DlgCaption("The tracks go straight in.")
            } else {
                for (list in lists) {
                    // How much of this selection the list already holds. Counted live off
                    // `lists`, which is a flow, so a tick appears from the edit itself rather
                    // than from a copy of the answer held in this composable - the version that
                    // keeps its own `checked` state is the version that drifts.
                    val have = paths.count { list.contains(it) }
                    val state = PlaylistMembership.state(have, paths.size)
                    PlaylistRow(
                        cards = vm.trackCards,
                        list = list,
                        detail = PlaylistMembership.detail(have, paths.size, list.size),
                        ticked = state.ticked,
                    ) {
                        vm.setPlaylistMembership(
                            list.id,
                            nodes,
                            member = PlaylistMembership.act(state) == PlaylistMembership.Act.ADD,
                        )
                    }
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
            if (making) {
                DlgBtn("Cancel") { making = false }
                DlgSpacer()
                DlgBtn("Create", kind = BtnKind.PRIMARY) { vm.newPlaylistWith(name, nodes) }
            } else {
                DlgSpacer()
                // "Done", not "Cancel". Every tick has already been applied, so a button
                // offering to cancel would be lying about what pressing it undoes.
                DlgBtn("Done", kind = BtnKind.PRIMARY, onClick = dismiss)
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    cards: TrackCards,
    list: Playlist,
    detail: String,
    /** True, false, or null for the dash - see [PlaylistMembership.State.ticked]. */
    ticked: Boolean?,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            // The whole row, not the box. A 20dp target at the end of a row is a control half
            // the people using it miss, and there is nothing else on the row to tap.
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The list's own cover, the same four-up the tab draws. A column of identical
        // grey squares is a list of names with an ornament in front of each; a column of
        // covers is a list somebody recognises without reading it.
        PlaylistCover(cards, list, 44.dp, radius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            dev.niccc2007.filet.browser.ListNameText(
                name = list.name,
                color = MaterialTheme.colorScheme.onSurface,
                startSp = 13f,
                isDir = true,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 10.sp, color = colors.fg3, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        TickBox(ticked)
    }
}

/**
 * A three-state box, drawn here rather than taken from Material.
 *
 * `TriStateCheckbox` exists and brings its own ripple, its own 48dp touch target and its own
 * colour scheme - three things this dialogue has already decided differently - so it would be
 * the one control on the sheet that belongs to a different design. The shape is the same
 * rounded square the rest of the kit uses, and the dash for the partial state is what makes
 * "some of these forty are in here" legible without reading the caption.
 */
@Composable
private fun TickBox(ticked: Boolean?) {
    val colors = Filet.colors
    val on = ticked != false
    Box(
        Modifier
            .size(21.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) colors.accent else colors.sunken),
        contentAlignment = Alignment.Center,
    ) {
        when (ticked) {
            true -> Icon(
                FiletIcons.Check, null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp),
            )
            // The dash, drawn as a bar rather than as a glyph so it cannot be mistaken for a
            // small tick at a glance.
            null -> Box(
                Modifier
                    .size(width = 10.dp, height = 2.dp)
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
            false -> Unit
        }
    }
}
