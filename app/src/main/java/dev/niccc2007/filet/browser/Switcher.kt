package dev.niccc2007.filet.browser

import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
fun Switcher(
    vm: BrowserViewModel,
    tabs: List<PaneController>,
    widthDp: Float,
    /** The drag handle on the drawer's edge; see SideEdge. */
    edge: @Composable () -> Unit,
) {
    val colors = Filet.colors
    val app by vm.state.collectAsState()
    val reduced = reduceMotion()

    val reach by vm.prefs.reachMode.collectAsState()

    // In reach mode the drawer's contents sit on its bottom edge rather than hanging from the
    // status bar. The drawer is already ordered with the rows reached for without reading at the
    // end, so anchoring it low puts those directly under the thumb and moves the rarely used
    // groups up to where the reach runs out - the same trade the listing makes.
    //
    // Bottom-anchoring inside a scrolling column needs a minimum height: a scrolling column is
    // measured unbounded, so `Arrangement.Bottom` on its own has no bottom to push towards.
    Row(Modifier.fillMaxHeight()) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .width(widthDp.dp)
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            val tall = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = tall)
                    .padding(vertical = 10.dp),
                verticalArrangement = if (reach) Arrangement.Bottom else Arrangement.Top,
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

                // Shared with the landscape rail, the Filet card included - see SideFolds.
                androidx.compose.runtime.CompositionLocalProvider(LocalSideBarWidth provides widthDp) {
                    SideFolds(vm, app, reduced) { vm.openSwitcher(false) }
                }

                Spacer(Modifier.height(10.dp))
            }
        }
        edge()
    }
}


/**
 * A foldable group of rows.
 *
 * The heading is the control - a separate chevron to hit beside a label that does nothing is
 * two targets for one action, and the smaller of them is the one that works.
 *
 * ## Why it looks like the APK inspector's folds now
 *
 * It was an 8.5sp uppercase micro-label with a chevron, floating on the drawer's own ground.
 * At that weight a heading is a caption: it reads as a label ABOVE a list rather than as the
 * lid ON one, so a shut group looked like a heading whose contents had failed to load, and
 * there was nothing to tell the eye where one group ended and the next began.
 *
 * The inspector solved the same problem on a screen with four of these, and the answer there is
 * a card: a sunken ground, a hairline border, a title at reading size and a detail line beside
 * it. The frame is what says "this is a container", and [detail] is what makes a SHUT one still
 * worth having - a group that answers "how many" without being opened is a group most people
 * never need to open.
 *
 * @param foldable false for a card that is always open: a title and a frame with no chevron and
 *   no tap on the heading. For rows that must never be one tap further away than they look.
 * @param trailing a control at the end of the heading, before the chevron - a mode switch that
 *   belongs to the group rather than taking a row of its own inside it. It takes its own taps;
 *   the rest of the heading still folds the card.
 * @param onLongClick a hold on the heading: the card's own menu, see [SectionMenu]. The rows
 *   inside keep their own holds.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SwitcherGroup(
    title: String,
    detail: String,
    open: Boolean,
    onToggle: () -> Unit,
    foldable: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Filet.colors
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        onLongClick != null -> Modifier.combinedClickable(
                            onClick = if (foldable) onToggle else ({}),
                            onLongClick = onLongClick,
                        )
                        foldable -> Modifier.clickable(onClick = onToggle)
                        else -> Modifier
                    },
                )
                .padding(horizontal = 13.dp, vertical = if (foldable) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                // One line at any width the bar can be dragged to: a heading that wraps pushes
                // every fold below it down by a line, which reads as the bar jumping.
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                detail,
                fontSize = 10.5.sp,
                color = colors.fg3,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
            if (foldable) {
                Icon(
                    FiletIcons.Down, if (open) "Collapse" else "Expand",
                    tint = colors.fg3,
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
        AnimatedVisibility(visible = open || !foldable) {
            Column(Modifier.padding(bottom = 6.dp), content = content)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SwitcherRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    index: Int,
    reduced: Boolean,
    count: String? = null,
    /** A quiet note at the end of the row - when a recent file was opened. */
    trailing: String? = null,
    /** Dimmer, for a fold's last "see all" row, so it reads as a way out rather than an item. */
    quiet: Boolean = false,
    /** A file to show its own preview in place of [icon]: the photo, the video frame, the cover. */
    thumb: dev.niccc2007.filet.vfs.VNode? = null,
    onLongClick: (() -> Unit)? = null,
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            FileThumb(
                thumb,
                size = 20.dp,
                fallbackTint = colors.fg2,
                glyphSize = 16.dp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(7.dp))
        } else {
            Icon(icon, null, tint = if (quiet) colors.fg3 else colors.fg2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(11.dp))
        }
        // Whole, never dotted: it gives up size down to 11sp, then fades. See ListName.
        ListNameText(
            name = label,
            color = if (quiet) colors.fg2 else androidx.compose.material3.LocalContentColor.current,
            startSp = if (quiet) 12f else 13f,
            isDir = thumb == null,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(trailing, fontSize = 10.sp, color = colors.fg3, maxLines = 1)
        }
        if (count != null) {
            Text(
                count, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(colors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
