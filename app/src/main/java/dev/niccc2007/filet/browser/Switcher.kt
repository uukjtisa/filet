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

        var i = 0
        app.volumes.forEach { v ->
            SwitcherRow(FiletIcons.Storage, v.label, i++, reduced) {
                vm.focusedPane()?.navigateTo(v.node.path); vm.openSwitcher(false)
            }
        }
        SwitcherRow(FiletIcons.Home, "Home", i++, reduced) { vm.focusedPane()?.openHome(); vm.openSwitcher(false) }
        // Built from SpecialPanes, not from a copy of it. The switcher is the only route to
        // a special pane on a PHONE and the rail is the only route on a tablet, so two
        // hand-maintained lists meant a destination could exist on one and not the other -
        // which is exactly how the Apps tab shipped unreachable on a phone.
        for (d in SpecialPanes.DESTINATIONS) {
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
