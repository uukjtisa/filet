package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Attach "pull past either end to refresh" to a scrollable.
 *
 * A nested-scroll connection rather than a drag handler, because the list has to get first
 * refusal on every delta: only what the list could NOT consume is a pull past its end, and
 * that distinction is the whole gesture. Reading the raw drag instead would fire on an
 * ordinary scroll that happened to begin at the top.
 *
 * The decision of what counts lives in [EdgePull], which has tests. This part is the plumbing:
 * where the list is, what it did not consume, and when the finger came off.
 */
@Composable
fun Modifier.edgePullRefresh(
    atTop: () -> Boolean,
    atBottom: () -> Boolean,
    onRefresh: () -> Unit,
): Modifier {
    val threshold = with(LocalDensity.current) { EdgePull.THRESHOLD_DP.dp.toPx() }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            private var state = PullState()

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only a real finger. A programmatic scroll - the reveal jump, or the scroll
                // to top on entering a folder - lands at an end with leftover delta and would
                // otherwise count as somebody asking for a refresh.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                state = EdgePull.onScroll(state, available.y, atTop(), atBottom())
                // Nothing is consumed: the list keeps its own overscroll animation, so the
                // gesture still looks and feels like the end of a list.
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val (next, fire) = EdgePull.onRelease(state, threshold)
                state = next
                if (fire) onRefresh()
                return Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}
