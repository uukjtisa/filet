package dev.niccc2007.filet.browser

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import dev.niccc2007.filet.log.FiletLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a screen inside a tab keeps about itself, so the tab comes back as it was left: where
 * each of its lists was scrolled to, and which of its own views was showing.
 *
 * ## Why one of these and not a fix per screen
 *
 * Bug identified here: only a FOLDER put its scroll back. Every other screen a tab can show -
 * Home, a category, New files, Settings, the playlists, Apps, a dozen more - held its list
 * position in a `rememberLazyListState` and its sub-page in a `remember`, and both live only as
 * long as the composition. Switching to another tab disposes it; so does the app closing. Every
 * one of them came back at its top, and Settings came back on Appearance whatever page it was
 * left on.
 *
 * Fixing that screen by screen is how it went wrong in the first place: the folder listing was
 * fixed and nothing told the next screen it had the same problem. So there is one place a
 * screen's memory goes - the pane, which outlives its composition and is saved with the tab -
 * and three calls every screen uses: [rememberKeptListState], [rememberKeptGridState] and
 * [rememberKept]. `check-keptscroll.mjs` refuses a pane screen that scrolls a list without one.
 *
 * Outside a tab - a dialogue, a sheet - there is no pane, and the calls are ordinary state.
 *
 * ## Only a return puts anything back
 *
 * Bug identified here, in the first shape of this: every screen put its last position back
 * whenever it was drawn, so a drive or a screen opened FRESH - from Home, the side bar, a
 * bookmark - came up wherever it had been left some earlier time, which reads as the app
 * holding on to something nobody asked it to. What is kept is the TAB: a tab shown again, a tab
 * put back at launch, a screen reached by Back. A fresh arrival starts at the top and at the
 * screen's own default, and files THAT, so the next return comes back to what was last seen.
 */
interface PaneMemory {
    /**
     * Whether the tab is being RETURNED to - shown again, put back at launch, or reached by
     * Back - rather than arrived at fresh. Only a return puts a position or a view back.
     */
    fun returning(): Boolean
    fun marks(place: String): ScrollMemory.Marks?
    fun park(place: String, marks: ScrollMemory.Marks)
    fun kept(key: String): String?
    fun keep(key: String, value: String?)
}

/** The memory of the tab a screen is drawn in, or null outside one. */
val LocalPaneMemory = staticCompositionLocalOf<PaneMemory?> { null }

/**
 * A list's scroll state that comes back where it was left, in this tab, across tab switches and
 * restarts. [place] names the list within the tab: "home", "settings:LOOK", a playlist's id.
 */
@Composable
fun rememberKeptListState(place: String): LazyListState {
    val memory = LocalPaneMemory.current
    val saved = remember(memory, place) { memory?.takeIf { it.returning() }?.marks(place)?.list }
    val state = remember(memory, place) { LazyListState(saved?.index ?: 0, saved?.offset ?: 0) }
    KeepPosition(
        memory = memory,
        place = place,
        saved = saved,
        count = { state.layoutInfo.totalItemsCount },
        moving = { state.isScrollInProgress },
        mark = { ScrollMemory.Mark(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
        scrollTo = { i, o -> state.scrollToItem(i, o) },
        file = { m, old -> (old ?: ScrollMemory.Marks()).copy(list = m) },
    )
    FollowBackToTop(state)
    return state
}

/** [rememberKeptListState] for a grid. A list and a grid of one place are kept apart. */
@Composable
fun rememberKeptGridState(place: String): LazyGridState {
    val memory = LocalPaneMemory.current
    val saved = remember(memory, place) { memory?.takeIf { it.returning() }?.marks(place)?.grid }
    val state = remember(memory, place) { LazyGridState(saved?.index ?: 0, saved?.offset ?: 0) }
    KeepPosition(
        memory = memory,
        place = place,
        saved = saved,
        count = { state.layoutInfo.totalItemsCount },
        moving = { state.isScrollInProgress },
        mark = { ScrollMemory.Mark(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
        scrollTo = { i, o -> state.scrollToItem(i, o) },
        file = { m, old -> (old ?: ScrollMemory.Marks()).copy(grid = m) },
    )
    FollowBackToTop(state)
    return state
}

/**
 * Put a position back once there are rows to put it on, then file every position the list
 * comes to rest at.
 *
 * Bug identified here, in the first shape of this: a screen whose rows arrive after it is drawn
 * - New files, a category, Apps - was handed its saved position while it had no rows, and a list
 * with nothing in it clamps to the top and FILES the top, overwriting the very position it was
 * given. So nothing is filed until the position has been put back, and putting it back waits
 * for the row it names to exist. A list that never grows that far gives up after a moment and
 * starts filing from wherever it is.
 *
 * Bug identified here, in the second shape: that moment was four seconds of a list filing
 * nothing. A list shorter than its saved position, scrolled by hand in those four seconds and
 * then left, came back where it had been before rather than where it was put. Somebody
 * scrolling it ends the wait - they have chosen where it is - and it files from then on.
 *
 * Bug identified here, in the third shape: a list read again while it is on screen - a category
 * after a change, which reads a short first page before the whole - is cut short for a moment.
 * A list cut shorter than where it stood clamps to its new end, and that end was filed as the
 * place, so the list stayed there when the rest came back, and so did the next return. A list
 * cut short under where it stood now files nothing, and goes back to where it stood when it
 * grows past it again, unless somebody has scrolled it in between.
 */
@Composable
private fun KeepPosition(
    memory: PaneMemory?,
    place: String,
    saved: ScrollMemory.Mark?,
    count: () -> Int,
    moving: () -> Boolean,
    mark: () -> ScrollMemory.Mark,
    scrollTo: suspend (Int, Int) -> Unit,
    file: (ScrollMemory.Mark, ScrollMemory.Marks?) -> ScrollMemory.Marks,
) {
    if (memory == null) return
    var restored by remember(memory, place) { mutableStateOf(saved == null || saved.index == 0 && saved.offset == 0) }
    var shrunk by remember(memory, place) { mutableStateOf(false) }
    LaunchedEffect(memory, place) {
        if (!restored && saved != null) {
            withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { count() > saved.index || moving() }.first { it } }
            // Somebody already dragging it is reading it themselves now.
            if (count() > saved.index && !moving()) scrollTo(saved.index, saved.offset)
            restored = true
        }
        // The last place filed: where the list stood while it was whole.
        var stood: ScrollMemory.Mark? = null
        snapshotFlow { Triple(moving(), mark(), count()) }
            .distinctUntilChanged()
            .collect { (busy, at, rows) ->
                val was = stood
                when {
                    // Being scrolled: wherever it comes to rest is the place now.
                    busy -> {
                        shrunk = false
                        stood = null
                    }
                    // Cut short under where it stood: where it clamped to is not a place.
                    was != null && was.index > 0 && rows <= was.index -> shrunk = true
                    shrunk && was != null -> {
                        shrunk = false
                        FiletLog.i("scroll", "$place grew back past ${was.index}; put back")
                        // Its own child, so a finger landing mid-jump stops the jump, not this.
                        launch { scrollTo(was.index, was.offset) }
                    }
                    at != was -> {
                        stood = at
                        memory.park(place, file(at, memory.marks(place)))
                    }
                }
            }
    }
    // Leaving - a tab switch, a sub-page change - files where it stood, in case it never came
    // to rest after the last frame the collector saw. Not while cut short: what is filed is
    // where it stood before.
    DisposableEffect(memory, place) {
        onDispose { if (restored && !shrunk) memory.park(place, file(mark(), memory.marks(place))) }
    }
}

/**
 * A screen's own choice of view, kept with the tab: which Settings page, which playlist was
 * open. [key] is the screen's name for it. Text in and out, because it is saved as text; see
 * [rememberKeptEnum] and [rememberKeptSet] for the common shapes.
 */
@Composable
fun <T> rememberKept(key: String, default: T, save: (T) -> String?, load: (String) -> T?): MutableState<T> {
    val memory = LocalPaneMemory.current
    // Only a return puts the kept view back; a fresh arrival shows the default, and files it.
    val returning = remember(memory, key) { memory?.returning() == true }
    val state = remember(memory, key) {
        mutableStateOf((if (returning) memory?.kept(key)?.let(load) else null) ?: default)
    }
    LaunchedEffect(memory, key) {
        if (memory == null) return@LaunchedEffect
        val values = snapshotFlow { state.value }
        (if (returning) values.drop1() else values).collect { memory.keep(key, save(it)) }
    }
    return state
}

@Composable
inline fun <reified T : Enum<T>> rememberKeptEnum(key: String, default: T): MutableState<T> =
    rememberKept(key, default, save = { it.name }, load = { s -> enumValues<T>().firstOrNull { it.name == s } })

/** A set of short strings - the day groups folded shut, say. */
@Composable
fun rememberKeptSet(key: String): MutableState<Set<String>> =
    rememberKept(key, emptySet(), save = { it.joinToString("\n").ifEmpty { null } }, load = { s -> s.split('\n').filter { it.isNotEmpty() }.toSet() })

/** The first value is the one just read back; writing it again would only bump the save. */
private fun <T> kotlinx.coroutines.flow.Flow<T>.drop1() = kotlinx.coroutines.flow.flow {
    var first = true
    collect { if (first) first = false else emit(it) }
}

/** How long a saved position waits for its rows before the list is left where it is. */
private const val RESTORE_WAIT_MS = 4_000L
