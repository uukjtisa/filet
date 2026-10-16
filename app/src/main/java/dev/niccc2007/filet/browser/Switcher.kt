package dev.niccc2007.filet.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.BuildConfig
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.reduceMotion
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The Trawl switcher, ported.
 *
 * Rows arrive from -30px over 460 ms at a 50 ms stagger with a 60 ms first delay, on Trawl's
 * MenuItemEasing. Values read out of `TrawlSwitcher.kt`; the push transform itself lives in
 * [BrowserScreen] because it applies to the app card, not to this menu.
 *
 * ## Every row is a destination
 *
 * Back and Up used to sit at the top of this list as a pair of pill buttons, and they were the
 * only two things in here that were not a place to go. They were also a second copy of controls
 * the toolbar already carries at every width, so opening a drawer to press Back meant two taps
 * for what the bar does in one. Gone, and the rule is worth keeping: this panel answers "where
 * to", never "how to get there".
 */
@Composable
fun Switcher(vm: BrowserViewModel, tabs: List<PaneController>) {
    val colors = Filet.colors
    val app by vm.state.collectAsState()
    val bookmarks by vm.bookmarks.items.collectAsState()
    val jobs by vm.ledger.jobs.collectAsState()
    val recents by vm.recents.items.collectAsState()
    val openStorage by vm.prefs.drawerStorage.collectAsState()
    val openRecent by vm.prefs.drawerRecent.collectAsState()
    val openPlaces by vm.prefs.drawerPlaces.collectAsState()
    val trawl = remember { vm.trawlInstalled() }
    val reduced = reduceMotion()

    Column(
        Modifier
            .fillMaxHeight()
            .width(236.dp)
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The APP icon, not the maker's mark. `FiletIcons.Mark` is the author's signature - it
            // belongs on About, where it means "who made this", and nowhere else.
            Icon(
                appIcon(body = colors.fg2, flap = colors.accent),
                null,
                tint = Color.Unspecified,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Filet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR_LABEL}",
                    fontSize = 10.sp,
                    color = colors.fg3,
                )
            }
            // Closing a drawer by tapping outside it is a thing you have to know; a button is
            // a thing you can see.
            Icon(
                FiletIcons.Close, "Close", tint = colors.fg2,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { vm.openSwitcher(false) }
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))

        // The stagger index. Shared across the groups so the rows animate in reading order
        // rather than each group starting its own wave.
        var i = 0

        // ── grouped, and most of it folded away ──
        //
        // Every destination used to be a flat column of a dozen identical rows: four volumes,
        // Home, eight places, Activity, Settings, About. Reported as crowded, and it was - a
        // list that long is read by scanning rather than by looking, which is the opposite of
        // what a navigation drawer is for.
        //
        // Three groups, each folded on its own. The states persist, so the drawer somebody
        // arranged stays arranged; and the group holding what they use is the one they leave
        // open, which is a thing only they can know.
        SwitcherGroup(
            "Storage",
            open = openStorage,
            onToggle = { vm.prefs.setDrawerStorage(!openStorage) },
        ) {
            app.volumes.forEach { v ->
                SwitcherRow(FiletIcons.Storage, v.label, i++, reduced) {
                    vm.focusedPane()?.navigateTo(v.node.path); vm.openSwitcher(false)
                }
            }
        }

        // The recent files themselves, not a row that leads to them. This is the one list in
        // the app somebody opens the drawer FOR - asked for by name - and a link to it is one
        // more tap than the thing it links to is worth.
        SwitcherGroup(
            "Recent",
            open = openRecent,
            onToggle = { vm.prefs.setDrawerRecent(!openRecent) },
        ) {
            if (recents.isEmpty()) {
                Text(
                    "Nothing yet",
                    fontSize = 11.sp,
                    color = colors.fg3,
                    modifier = Modifier.padding(start = 34.dp, top = 2.dp, bottom = 6.dp),
                )
            }
            for (entry in recents.take(RECENT_IN_DRAWER)) {
                SwitcherRow(
                    if (entry.isDir) FiletIcons.Folder else FiletIcons.File,
                    entry.label,
                    i++,
                    reduced,
                ) {
                    vm.openRecent(entry)
                    vm.openSwitcher(false)
                }
            }
        }

        // Built from SpecialPanes, not from a copy of it. The switcher is the only route to
        // a special pane on a PHONE and the rail is the only route on a tablet, so two
        // hand-maintained lists meant a destination could exist on one and not the other -
        // which is exactly how the Apps tab shipped unreachable on a phone.
        SwitcherGroup(
            "Places and tools",
            open = openPlaces,
            onToggle = { vm.prefs.setDrawerPlaces(!openPlaces) },
        ) {
            for (d in SpecialPanes.DESTINATIONS) {
                if (d.kind == PaneKind.HISTORY) continue
                SwitcherRow(
                    d.icon,
                    d.label,
                    i++,
                    reduced,
                    count = if (d.kind == PaneKind.BOOKMARKS) {
                        bookmarks.size.takeIf { it > 0 }?.toString()
                    } else {
                        null
                    },
                ) {
                    vm.focusedPane()?.openSpecial(d.kind, d.label); vm.openSwitcher(false)
                }
            }
            // Trawl is a different APP, so it sits with the tools rather than among Filet's
            // own destinations - a row that leaves the app should not look like a row that
            // changes the pane. It only appears when it is actually installed; an entry that
            // opens a store page is an advertisement, and this is a file manager.
            if (trawl) {
                SwitcherRow(FiletIcons.Trawl, "Trawl", i++, reduced) {
                    vm.openTrawl(); vm.openSwitcher(false)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        // Down here with Activity, Settings and About rather than at the top. These are the
        // two somebody reaches for without reading, so they belong where the thumb already
        // is on a phone - which is the bottom of a drawer, not the top of it.
        SwitcherRow(FiletIcons.Home, "Home", i++, reduced) {
            vm.focusedPane()?.openHome(); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Download, "New files", i++, reduced) {
            vm.focusedPane()?.openSpecial(
                PaneKind.HISTORY,
                dev.niccc2007.filet.home.HomeSections.ARRIVED_TAB,
            )
            vm.openSwitcher(false)
        }
        SwitcherRow(
            FiletIcons.Jobs, "Activity", i++, reduced,
            count = jobs.count { it.running }.takeIf { it > 0 }?.toString(),
        ) {
            vm.openActivity(true); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Cog, "Settings", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.SETTINGS, "Settings"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Info, "About", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.ABOUT, "About"); vm.openSwitcher(false)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** How many recent files the drawer shows before it would become the thing it replaced. */
private const val RECENT_IN_DRAWER = 6

/**
 * A foldable group of rows.
 *
 * The heading is the control - a separate chevron to hit beside a label that does nothing is
 * two targets for one action, and the smaller of them is the one that works.
 */
@Composable
private fun SwitcherGroup(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Filet.colors
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = colors.fg3,
            modifier = Modifier.weight(1f),
        )
        Icon(
            FiletIcons.Down, null,
            tint = colors.fg3,
            modifier = Modifier.size(13.dp).graphicsLayer { rotationZ = turn },
        )
    }
    AnimatedVisibility(visible = open) {
        Column(content = content)
    }
}

@Composable
private fun SwitcherRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    index: Int,
    reduced: Boolean,
    count: String? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val enter by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = if (reduced) 0 else 460,
            delayMillis = if (reduced) 0 else 60 + index * 50,
            easing = Motion.Out,
        ),
        label = "switcherRow$index",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = -30f * (1f - enter) }
            .alpha(enter)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(
                count, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(colors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
