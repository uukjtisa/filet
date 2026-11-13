package dev.niccc2007.filet.shortcuts

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.browser.tabGlyph
import dev.niccc2007.filet.ui.tabs.GoText
import dev.niccc2007.filet.ui.tabs.Pill
import dev.niccc2007.filet.ui.theme.Filet
import java.util.Locale

// The pieces of the Shortcuts tab: a heading that folds its list, a catalogue row, and the glyph
// each kind of shortcut is drawn with.

/** The glyph for an action, in the list and on its catalogue row. */
fun actionGlyph(action: AppAction): ImageVector = when (action) {
    AppAction.SEARCH -> FiletIcons.Search
    AppAction.INDEX_NOW -> FiletIcons.Refresh
    AppAction.SHARE_NEARBY, AppAction.STOP_SHARING -> FiletIcons.Wifi
    AppAction.BOOKMARKS -> FiletIcons.Star
    AppAction.RECENT -> FiletIcons.Clock
}

/** The glyph a listed shortcut is drawn with: the same one its catalogue row has. */
fun recordGlyph(record: ShortcutRecord): ImageVector = when (record.kind) {
    ShortcutKind.TAB -> TabTarget.byId(record.target)?.let(::tabGlyph) ?: FiletIcons.Pin
    ShortcutKind.ACTION -> AppAction.parse(record.target)?.let(::actionGlyph) ?: FiletIcons.Pin
    ShortcutKind.SCRIPT -> FiletIcons.Script
    ShortcutKind.FILE -> when (record.dir) {
        true -> FiletIcons.Folder
        false -> FiletIcons.File
        null -> FiletIcons.Pin
    }
}

/** A glyph on a small raised square, at the start of a row. */
@Composable
fun GlyphTile(icon: ImageVector) {
    val colors = Filet.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(16.dp))
    }
}

/**
 * A section heading that folds the list under it, with the list's count.
 *
 * The count stays when the list is folded: it is what says there is something in there to open.
 * While a search is typed every list is open and the heading cannot fold, because a match folded
 * out of sight reads as no match. A list with a cap shows it beside the count, as "3 of 4".
 */
@Composable
fun FoldHead(label: String, count: Int, folded: Boolean, foldable: Boolean = true, of: Int? = null, onToggle: () -> Unit) {
    val colors = Filet.colors
    val shut = folded && foldable
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (foldable) Modifier
                    .clickable(onClickLabel = if (shut) "Show" else "Hide", role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = if (shut) "Folded" else "Open" }
                else Modifier
            )
            .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (foldable) {
            Icon(
                if (shut) FiletIcons.Chevron else FiletIcons.CaretDown,
                null,
                tint = colors.fg3,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            label.uppercase(Locale.US),
            fontSize = 9.5.sp,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg3,
            modifier = Modifier.weight(1f),
        )
        Text(if (of == null) count.toString() else "$count of $of", fontSize = 10.sp, color = colors.fg3)
    }
}

/** The name of a group of tabs, inside the Tabs list. */
@Composable
fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 17.dp, end = 15.dp, top = 6.dp, bottom = 6.dp),
    )
}

/**
 * One thing that could go on the home screen.
 *
 * One already there says so and does not offer itself again: asking twice for the same id gives a
 * second dialog for an icon the launcher already has.
 */
@Composable
fun CatalogueRow(
    icon: ImageVector,
    label: String,
    /** The tab icon mock's id for this row's icon; the glyph [icon] is drawn only where there is none. */
    art: String? = null,
    desc: String? = null,
    placed: Boolean,
    warn: Boolean = false,
    onAdd: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 7.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .clickable(enabled = !placed, onClickLabel = "Add to home screen", onClick = onAdd)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        val plain = dev.niccc2007.filet.icons.LocalIconStyle.current == dev.niccc2007.filet.icons.IconStyle.PLAIN
        if (!plain && art != null && artDrawable(art) != null) ArtTile(art) else GlyphTile(icon)
        Column(Modifier.weight(1f)) {
            dev.niccc2007.filet.browser.ListNameText(
                text = dev.niccc2007.filet.ui.highlighted(label, dev.niccc2007.filet.ui.LocalPaneQuery.current, colors.accent),
                color = androidx.compose.material3.LocalContentColor.current,
                startSp = 13f,
                lines = 1,
                isDir = true,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(desc, fontSize = 10.sp, color = if (warn) colors.warn else colors.fg3, lineHeight = 14.sp)
            }
        }
        if (placed) Pill("On home screen") else Text("Add", fontSize = 11.sp, color = colors.accent, maxLines = 1)
    }
}

/**
 * The Shortcuts tab with nothing on the home screen yet: the three ways one gets made.
 *
 * A file has no catalogue here, because the file is picked where it lives; the card says where.
 */
@Composable
fun ShortcutsEmpty(onTabs: () -> Unit, onActions: () -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .background(colors.raised)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            GlyphTile(FiletIcons.Pin)
            Text("Nothing on your home screen yet", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.fg2)
        }
        Spacer(Modifier.height(10.dp))
        EmptyWay(FiletIcons.Home, "A tab, opened in one tap", "Show tabs", onTabs)
        EmptyWay(FiletIcons.Folder, "A file or folder: select it, then Shortcut", null, null)
        EmptyWay(FiletIcons.Search, "An action or a script", "Show actions", onActions)
    }
}

@Composable
private fun EmptyWay(icon: ImageVector, text: String, go: String?, onGo: (() -> Unit)?) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = colors.fg3, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, color = colors.fg3, modifier = Modifier.weight(1f))
        if (go != null && onGo != null) GoText(go, onGo)
    }
}
