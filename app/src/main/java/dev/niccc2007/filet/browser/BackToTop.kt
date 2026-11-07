package dev.niccc2007.filet.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The pill that takes a long list back to its top: one per tab, for whichever list the tab shows.
 *
 * One pill, not one per screen. Every list in a tab already goes through [rememberKeptListState]
 * or [rememberKeptGridState], and the folder listing registers its own two states, so a screen
 * gets the pill by scrolling a list at all and none of them draws one of its own.
 */
object BackToTop {

    /**
     * How many screens long a list must be before it has a pill. Two screens back up is one
     * flick; at three it starts to be a chore, and three is still short enough to cover a
     * category of a few dozen files, not only the long ones.
     */
    const val SCREENS = 3

    /**
     * Whether the pill shows: the list is [SCREENS] screens long and has been scrolled past its
     * first screen. [first] is the first row shown, [visible] how many are shown, [total] how many
     * there are; a grid counts cells for all three, so the measure is the same.
     */
    fun shows(first: Int, visible: Int, total: Int): Boolean =
        visible > 0 && total >= visible * SCREENS && first >= visible

    /** One list the pill can act on. */
    class Target(val showing: State<Boolean>, val top: suspend () -> Unit)

    /**
     * The lists a tab is drawing. The pill acts on the one last scrolled; until one has been, on
     * the one drawn last, so a page that has just opened answers for itself.
     */
    class Holder {
        private val drawn = mutableStateListOf<Target>()
        private var moved by mutableStateOf<Target?>(null)

        val target: Target? get() = moved?.takeIf { it in drawn } ?: drawn.lastOrNull()

        fun add(t: Target) = drawn.add(t)
        fun remove(t: Target) = drawn.remove(t)
        fun moved(t: Target) {
            moved = t
        }
    }
}

/** The pill's list holder for the tab being drawn, or null outside one. */
val LocalBackToTop = staticCompositionLocalOf<BackToTop.Holder?> { null }

/** Puts [state] under the tab's pill. A sideways list never is: its top is not a place. */
@Composable
fun FollowBackToTop(state: LazyListState) = Follow(
    key = state,
    vertical = { state.layoutInfo.orientation == Orientation.Vertical },
    moving = { state.isScrollInProgress },
    first = { state.firstVisibleItemIndex },
    visible = { state.layoutInfo.visibleItemsInfo.size },
    total = { state.layoutInfo.totalItemsCount },
    top = { state.scrollToItem(0) },
)

/** [FollowBackToTop] for a grid. */
@Composable
fun FollowBackToTop(state: LazyGridState) = Follow(
    key = state,
    vertical = { state.layoutInfo.orientation == Orientation.Vertical },
    moving = { state.isScrollInProgress },
    first = { state.firstVisibleItemIndex },
    visible = { state.layoutInfo.visibleItemsInfo.size },
    total = { state.layoutInfo.totalItemsCount },
    top = { state.scrollToItem(0) },
)

@Composable
private fun Follow(
    key: Any,
    vertical: () -> Boolean,
    moving: () -> Boolean,
    first: () -> Int,
    visible: () -> Int,
    total: () -> Int,
    top: suspend () -> Unit,
) {
    val holder = LocalBackToTop.current ?: return
    val target = remember(holder, key) {
        BackToTop.Target(derivedStateOf { vertical() && BackToTop.shows(first(), visible(), total()) }, top)
    }
    DisposableEffect(holder, target) {
        holder.add(target)
        onDispose { holder.remove(target) }
    }
    LaunchedEffect(holder, target) {
        snapshotFlow { moving() && vertical() }.filter { it }.collect { holder.moved(target) }
    }
}

/**
 * The pill itself, at the top of the tab's list, where it is out of the way of the dock and
 * the bars at the bottom. Straight to the top on a tap rather than animated: animated, it draws
 * every row between, hundreds in a long list.
 */
@Composable
fun BackToTopPill(holder: BackToTop.Holder) {
    val target = holder.target
    val shown = target?.showing?.value == true
    val scope = rememberCoroutineScope()
    val colors = Filet.colors
    Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Row(
                Modifier
                    .shadow(5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.high)
                    .clickable { target?.let { t -> scope.launch { t.top() } } }
                    .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Up, null, tint = colors.fg2, modifier = Modifier.size(16.dp))
                Text("Top", color = colors.fg2, fontSize = 13.sp)
            }
        }
    }
}
