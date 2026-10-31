package dev.niccc2007.filet.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Loads the rows just outside the viewport, once the list has stopped moving.
 *
 * ## Why it waits for the list to settle
 *
 * Pre-rendering during a fling is pre-rendering rows that are about to be wrong. Worse, it
 * competes for the same two workers as the rows actually under the finger. The useful moment is
 * the one after somebody stops: the next flick then finds its rows already drawn, which is what
 * makes a list feel like it was always ready rather than like it is catching up.
 *
 * ## Why it reaches backwards as well
 *
 * Scrolling back up is the direction that was reported as re-loading everything. The cache fixed
 * the re-reading; warming behind the viewport as well as ahead of it is what makes the row
 * already be there when it arrives.
 *
 * ## Why this is not the `LazyColumn`'s own prefetch
 *
 * That one composes the next item's layout. It does not know that a row's picture is an
 * asynchronous read with a queue behind it, which is the expensive part and the only part worth
 * scheduling. [warm] is given a row index and decides for itself what loading that row means.
 */
@Composable
fun WarmAhead(
    state: LazyListState,
    /** How many rows the list addresses, excluding anything above them. */
    count: Int,
    /** How many list items sit above the rows - headers, a selection bar. */
    offset: Int = 0,
    /** How far past the last visible row to reach. */
    reach: Int = 12,
    /** How far back above the first visible row. Scrolling up is the reported direction. */
    behind: Int = 6,
    warm: suspend (Int) -> Unit,
) {
    val load by rememberUpdatedState(warm)
    LaunchedEffect(state, count, offset) {
        snapshotFlow {
            val info = state.layoutInfo.visibleItemsInfo
            Triple(
                state.isScrollInProgress,
                info.firstOrNull()?.index ?: 0,
                info.lastOrNull()?.index ?: 0,
            )
        }.collectLatest { (scrolling, first, last) ->
            if (scrolling || count <= 0) return@collectLatest
            val top = (first - offset).coerceAtLeast(0)
            val bottom = (last - offset).coerceAtLeast(0)
            // Forwards first, because that is the direction somebody is most likely to go next.
            // `collectLatest` cancels the whole walk the moment the list moves again, so a
            // half-finished pre-render costs only what it had already started - and the queue
            // keeps that.
            for (i in (bottom + 1) until (bottom + 1 + reach).coerceAtMost(count)) load(i)
            for (i in (top - 1).downTo((top - behind).coerceAtLeast(0))) load(i)
        }
    }
}

/**
 * The same, for the tile view.
 *
 * A separate overload rather than one function over an interface, because the two states share
 * no supertype that exposes `visibleItemsInfo`. The tile view needs it MORE than the list does:
 * a tile asks for a picture several times the area of a row's, and a grid shows three times as
 * many at once.
 */
@Composable
fun WarmAhead(
    state: LazyGridState,
    count: Int,
    offset: Int = 0,
    reach: Int = 24,
    behind: Int = 12,
    warm: suspend (Int) -> Unit,
) {
    val load by rememberUpdatedState(warm)
    LaunchedEffect(state, count, offset) {
        snapshotFlow {
            val info = state.layoutInfo.visibleItemsInfo
            Triple(
                state.isScrollInProgress,
                info.firstOrNull()?.index ?: 0,
                info.lastOrNull()?.index ?: 0,
            )
        }.collectLatest { (scrolling, first, last) ->
            if (scrolling || count <= 0) return@collectLatest
            val top = (first - offset).coerceAtLeast(0)
            val bottom = (last - offset).coerceAtLeast(0)
            for (i in (bottom + 1) until (bottom + 1 + reach).coerceAtMost(count)) load(i)
            for (i in (top - 1).downTo((top - behind).coerceAtLeast(0))) load(i)
        }
    }
}
