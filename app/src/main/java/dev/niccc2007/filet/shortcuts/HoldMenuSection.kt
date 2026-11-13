package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.GripRow
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.browser.dragTarget
import dev.niccc2007.filet.browser.shiftFor
import dev.niccc2007.filet.browser.tabGlyph
import dev.niccc2007.filet.icons.IconStyle
import dev.niccc2007.filet.icons.LocalIconStyle
import dev.niccc2007.filet.ui.theme.Filet

// The Shortcuts tab's Hold menu: which four entries show when Filet's icon is held on the home
// screen, arranged by dragging, with a picture of the launcher's popup above them.

/**
 * The chosen entries: what the section is for, the popup as a launcher draws it, then the four
 * as rows to drag, each with a cross while there is more than one.
 */
@Composable
internal fun HoldMenuChosen(ids: List<String>, sharing: Boolean, write: (List<String>) -> Unit) {
    val colors = Filet.colors
    val entries = remember(ids, sharing) { ids.mapNotNull { HoldMenu.entryOf(it, sharing) } }
    Column {
        // PROSE OK: the only line that says what this section changes, which nothing else on the tab can show.
        Text(
            "What shows when you hold Filet's icon on the home screen. Most launchers show four, so four is the most.",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.fg3,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        LauncherPopup(entries)
        ChosenRows(entries, write)
        if (entries.size >= LauncherShortcuts.MAX) {
            Text(
                "Four in. Take one out to add another.",
                fontSize = 10.5.sp,
                color = colors.fg3,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/** The entries as rows, in their order, arranged by the grip. The bar picker's drag. */
@Composable
private fun ChosenRows(entries: List<HoldMenu.Entry>, write: (List<String>) -> Unit) {
    val ids = entries.map { it.id }
    val centres = remember(ids) { mutableStateMapOf<Int, Float>() }
    val heights = remember(ids) { mutableStateMapOf<Int, Float>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else dragTarget(dragging, dragY, ids.indices.map { centres[it] ?: 0f })
    // One frame with the rows' slide turned off, so a dropped row does not glide in from its old place.
    var landing by remember { mutableStateOf(false) }
    LaunchedEffect(landing) {
        if (landing) {
            withFrameNanos { }
            landing = false
        }
    }
    Column(Modifier.padding(horizontal = 6.dp)) {
        entries.forEachIndexed { i, entry ->
            key(entry.id) {
                GripRow(
                    label = entry.long,
                    sub = entryNote(entry),
                    lifted = i == dragging,
                    offsetY = shiftFor(i, dragging, target, dragY, centres, heights),
                    settle = i != dragging && !landing,
                    onCentre = { c, h -> centres[i] = c; heights[i] = h },
                    onDragStart = { dragging = i; dragY = centres[i] ?: 0f },
                    onDrag = { dragY += it },
                    onDragEnd = {
                        if (dragging >= 0 && target >= 0 && target != dragging) {
                            landing = true
                            write(HoldMenu.moved(ids, dragging, target))
                        }
                        dragging = -1
                    },
                    onUp = if (i > 0) ({ write(HoldMenu.moved(ids, i, i - 1)) }) else null,
                    onDown = if (i < ids.size - 1) ({ write(HoldMenu.moved(ids, i, i + 1)) }) else null,
                    removeLabel = "Take ${entry.long} out of the hold menu",
                    // The last one stays: an empty menu reads back as the default four.
                    onRemove = if (ids.size > 1) ({ write(HoldMenu.removed(ids, entry.id)) }) else null,
                ) { EntryTile(entry) }
            }
        }
    }
}

/**
 * Something that could go in: its tile, its name and Add. With four in, the row stays to show what
 * else there is, dimmed and with nothing to press.
 */
@Composable
internal fun HoldMenuAdd(id: String, sharing: Boolean, full: Boolean, onAdd: () -> Unit) {
    val entry = remember(id, sharing) { HoldMenu.entryOf(id, sharing) } ?: return
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .then(if (full) Modifier.alpha(0.45f) else Modifier.clickable(onClickLabel = "Add to the hold menu", onClick = onAdd))
            .padding(start = 36.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EntryTile(entry)
        Text(entry.long, fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
        if (!full) Text("Add", fontSize = 11.sp, color = colors.accent, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
    }
}

/** Under an entry's name: that it is a tab, or what the action does. */
private fun entryNote(entry: HoldMenu.Entry): String? =
    if (tabIdOrNull(entry.id) != null) "Tab" else AppAction.parse(entry.route)?.description

/** An entry's icon in the list: its drawing, or the plainer glyph where the Shortcuts tab asks for those. */
@Composable
private fun EntryTile(entry: HoldMenu.Entry) {
    val plain = LocalIconStyle.current == IconStyle.PLAIN
    if (!plain && artDrawable(entry.art) != null) {
        ArtTile(entry.art)
        return
    }
    val tab = tabIdOrNull(entry.id)?.let(TabTarget::byId)
    GlyphTile(tab?.let(::tabGlyph) ?: AppAction.parse(entry.route)?.let(::actionGlyph) ?: FiletIcons.Pin)
}

/**
 * The popup a launcher shows over the held icon, in the launcher's colours rather than Filet's:
 * the entries with their home-screen icons, App info under a rule, and Filet's own icon below.
 *
 * Launchers draw this themselves and each a little differently; this is the common shape, so the
 * order and the names can be judged before going to the home screen to look.
 */
@Composable
private fun LauncherPopup(entries: List<HoldMenu.Entry>) {
    val context = LocalContext.current
    val app = remember(context) {
        val pm = context.packageManager
        val icon = runCatching { pm.getApplicationIcon(context.applicationInfo).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        icon to context.applicationInfo.loadLabel(pm).toString()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                // CSS's 160deg: down and a little to the right, the line long enough to reach the corners.
                val dx = 0.342f
                val dy = 0.940f
                val half = kotlin.math.abs(size.width / 2 * dx) + kotlin.math.abs(size.height / 2 * dy)
                val c = Offset(size.width / 2, size.height / 2)
                drawRect(
                    Brush.linearGradient(
                        0f to Color(0xFF30475A),
                        0.6f to Color(0xFF1B2630),
                        1f to Color(0xFF141A20),
                        start = c - Offset(dx, dy) * half,
                        end = c + Offset(dx, dy) * half,
                    ),
                )
            }
            .padding(top = 16.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .width(214.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .background(Color(0xFFF3F2F0), RoundedCornerShape(16.dp))
                .padding(vertical = 6.dp),
        ) {
            for (entry in entries) {
                PopupRow(entry.long) { HomeIcon(entry.art) }
            }
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color(0x11000000)))
            PopupRow("App info", system = true) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFE4E2DE)))
            }
        }
        Spacer(Modifier.height(10.dp))
        val (icon, label) = app
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(54.dp).shadow(4.dp, CircleShape).clip(CircleShape))
        }
        Text(
            label,
            style = TextStyle(shadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 4f)),
            fontSize = 11.5.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun PopupRow(label: String, system: Boolean = false, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(
            label,
            fontSize = if (system) 12.sp else 13.sp,
            color = if (system) Color(0xFF555555) else Color(0xFF1D1C1A),
            maxLines = 1,
        )
    }
}

/**
 * An entry's icon as the launcher shows it: this build's home-screen drawing in a circle, cut to
 * the middle 72 of its 108 units the way a launcher's mask cuts it.
 */
@Composable
private fun HomeIcon(art: String) {
    val res = homeArt(art)
    Box(Modifier.size(28.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (res != null) {
            Image(painterResource(res), contentDescription = null, modifier = Modifier.requiredSize(28.dp * (108f / 72f)))
        }
    }
}
