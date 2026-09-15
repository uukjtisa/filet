package dev.niccc2007.filet.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
 */
@Composable
fun Switcher(vm: BrowserViewModel, tabs: List<PaneController>) {
    val colors = Filet.colors
    val app by vm.state.collectAsState()
    val bookmarks by vm.bookmarks.items.collectAsState()
    val jobs by vm.ledger.jobs.collectAsState()
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
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwitcherAction(FiletIcons.Back, "Back") {
                vm.focusedPane()?.let { if (!it.goBack()) it.goUp() }
                vm.openSwitcher(false)
            }
            SwitcherAction(FiletIcons.Up, "Up") {
                vm.focusedPane()?.goUp()
                vm.openSwitcher(false)
            }
        }
        Spacer(Modifier.height(6.dp))

        var i = 0
        app.volumes.forEach { v ->
            SwitcherRow(FiletIcons.Storage, v.label, i++, reduced) {
                vm.focusedPane()?.navigateTo(v.node.path); vm.openSwitcher(false)
            }
        }
        SwitcherRow(FiletIcons.Home, "Home", i++, reduced) { vm.focusedPane()?.openHome(); vm.openSwitcher(false) }
        SwitcherRow(FiletIcons.Clock, "Recent", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.RECENT, "Recent"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Star, "Bookmarks", i++, reduced, count = bookmarks.size.takeIf { it > 0 }?.toString()) {
            vm.focusedPane()?.openSpecial(PaneKind.BOOKMARKS, "Bookmarks"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Wifi, "Nearby", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.NEARBY, "Nearby"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Device, "Remotes", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.REMOTES, "Remotes"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Home, "Shortcuts", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.SHORTCUTS, "Shortcuts"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Script, "Scripts", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.SCRIPTS, "Scripts"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Jobs, "Activity", i++, reduced, count = jobs.count { it.running }.takeIf { it > 0 }?.toString()) {
            vm.openActivity(true); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Cog, "Settings", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.SETTINGS, "Settings"); vm.openSwitcher(false)
        }
        SwitcherRow(FiletIcons.Info, "About", i++, reduced) {
            vm.focusedPane()?.openSpecial(PaneKind.ABOUT, "About"); vm.openSwitcher(false)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "TAP THE WINDOW TO GO BACK",
            fontSize = 8.5.sp,
            letterSpacing = 1.1.sp,
            color = colors.fg3,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun SwitcherAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.5.sp, color = colors.fg2)
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
