package dev.niccc2007.filet.browser

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where the pane under a tab swipe is, and the animation that finishes the swipe.
 *
 * The pane follows the finger now. The old swipe only showed a label while you pushed and then
 * either jumped or did nothing, so nothing on screen said the gesture had been understood until
 * it was over - which is most of why it felt unresponsive. A pane that moves with the thumb
 * answers on the first frame of the drag.
 *
 * [offset] is read in the draw phase only ([swipeFollow]), so a drag redraws one layer and never
 * recomposes the listing under it.
 */
@Stable
class SwipeMotion(private val scope: CoroutineScope) {
    /** Which pane is moving, or null when none is. */
    var side by mutableStateOf<Side?>(null)
        private set

    /** Pixels; negative is moving left, towards the next tab. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The tab a release would land on, by title, or null when there is none that way. */
    var to by mutableStateOf<String?>(null)
        private set

    /** The same tab by index, so [SwipeNeighbour] can draw it. */
    var toIndex by mutableStateOf<Int?>(null)
        private set

    /** Whether there is a tab that way at all. False rubber-bands rather than moving freely. */
    var can by mutableStateOf(false)
        private set

    /** 0 to 1: how much of the way to a commit the drag has come. Drives the peek card. */
    var progress by mutableFloatStateOf(0f)
        private set

    /**
     * A committed swipe whose switch the screen has not shown yet.
     *
     * While true the motion stays where the commit left it - the arriving tab fully in view as
     * the neighbour - and [SwipePanes] lets go of it in the composition where that tab has become
     * the side's current pane. See [release] for why it cannot simply reset.
     */
    var holding by mutableStateOf(false)
        private set

    private var width = 1f
    private var running: Job? = null

    fun drag(
        side: Side,
        dxPx: Float,
        paneWidthPx: Float,
        to: String?,
        can: Boolean,
        progress: Float,
        toIndex: Int? = null,
    ) {
        running?.cancel()
        holding = false
        this.side = side
        this.width = paneWidthPx.coerceAtLeast(1f)
        this.to = to
        this.toIndex = toIndex
        this.can = can
        this.progress = progress
        offset = if (can) dxPx else dxPx * RUBBER
    }

    /**
     * The finger came off. A commit carries on the way the finger was going - the pane leaves
     * and the real neighbour, already half in, finishes arriving - and then switches; anything
     * short of one springs back.
     *
     * ## Why a commit holds instead of resetting
     *
     * Bug identified here: the drag was smooth and the OLD tab flashed for a frame or two the
     * moment the finger lifted. The trace showed why. [commit] changes the view model's active
     * tab, but the screen learns of it through a collected state that arrives a dispatch later -
     * 20 to 30 ms on the phone - while the motion reset straight after [commit] reached the very
     * next frame. For those frames the side still had the old pane as current, at offset zero,
     * with no neighbour: the old tab, full screen.
     *
     * So the motion is left exactly where the commit put it, with the arriving tab fully in view,
     * and the hold ends in the composition where that tab IS the current pane ([settle]). Nothing
     * here guesses how long the switch takes. [HOLD_MAX_MS] only stops a switch that never lands
     * from leaving the side frozen.
     */
    fun release(fire: Boolean, step: Int, commit: () -> Unit) {
        running?.cancel()
        running = scope.launch {
            if (!fire) {
                animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
                reset()
                return@launch
            }
            animate(offset, -step * width, animationSpec = tween(OUT_MS)) { v, _ -> offset = v }
            holding = true
            commit()
            kotlinx.coroutines.delay(HOLD_MAX_MS)
            if (holding) reset()
        }
    }

    /** The arriving tab is the current pane now: let go of the finished frame. */
    fun settle() {
        if (!holding) return
        running?.cancel()
        reset()
    }

    private fun reset() {
        holding = false
        side = null
        to = null
        toIndex = null
        progress = 0f
        offset = 0f
    }

    companion object {
        /** How much of the finger's travel a pane with no tab that way follows. */
        const val RUBBER = 0.25f

        /** How long a committed swipe takes to finish what the finger started. */
        const val OUT_MS = 180

        /**
         * The longest a finished swipe waits for the switch to show before giving up the hold.
         * Far past the dispatch the switch actually takes, so it never decides a normal swipe.
         */
        const val HOLD_MAX_MS = 400L
    }
}

@Composable
fun rememberSwipeMotion(): SwipeMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { SwipeMotion(scope) }
}

/** Moves a pane with the swipe, in the draw phase. */
fun Modifier.swipeFollow(motion: SwipeMotion, side: Side): Modifier = graphicsLayer {
    if (motion.side == side) {
        translationX = motion.offset
        alpha = 1f - (kotlin.math.abs(motion.offset) / size.width.coerceAtLeast(1f)) * 0.35f
    }
}

/**
 * The sideways swipe that moves one pane along the tab strip.
 *
 * ## A sideways list always wins
 *
 * Who owns the drag is decided once, by [PaneSwipe.owner], the moment it passes the touch slop
 * - and it is decided on the FINAL pass, after every child has had the event. A chip row, a card
 * row, a gallery strip: anything that scrolls sideways starts its own drag on that same event
 * and consumes it, and a consumed event is a child's drag for the rest of the gesture, even
 * past the end of the row. That is the rule asked for: a drag that starts on a sideways list
 * only ever scrolls that list. It used to watch the Initial pass, ahead of every child, and
 * never ask - so scrolling a row anywhere also dragged the tabs.
 *
 * Once the swipe owns a drag it reads the Initial pass and consumes, so the vertical list
 * underneath cannot start scrolling a pane that is already moving sideways.
 *
 * @param splitAt where the divider is, as a fraction of the width, or null in single pane.
 */
@Composable
fun tabSwipe(
    vm: BrowserViewModel,
    app: AppState,
    enabled: Boolean,
    at: PaneSwipe.Sensitivity,
    splitAt: Float?,
    motion: SwipeMotion,
): Modifier {
    val density = LocalDensity.current
    // Read through holders: `pointerInput` keys on the sensitivity alone, so a loop started an
    // hour ago is still the live one and a plain capture would be stale.
    val split = rememberUpdatedState(splitAt)
    val state = rememberUpdatedState(app)
    if (!enabled) return Modifier
    return Modifier.pointerInput(at) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val downAt = System.currentTimeMillis()
            val from = down.position
            val frac = split.value
            // Which pane, fixed at touch-down: a swipe that crosses the divider still belongs
            // to the pane it started in.
            val side = when {
                frac == null || size.width <= 0 -> Side.A
                from.x < size.width * frac -> Side.A
                else -> Side.B
            }
            val paneWidth = when {
                frac == null -> size.width.toFloat()
                side == Side.A -> size.width * frac
                else -> size.width * (1f - frac)
            }
            val slop = viewConfiguration.touchSlop
            var owner = PaneSwipe.Owner.UNDECIDED
            var travel = androidx.compose.ui.geometry.Offset.Zero
            var movedAt = 0L
            while (true) {
                val pass = if (owner == PaneSwipe.Owner.TABS) PointerEventPass.Initial else PointerEventPass.Final
                val event = awaitPointerEvent(pass)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUp()) break
                travel = change.position - from
                if (movedAt == 0L && travel.getDistance() > slop) movedAt = System.currentTimeMillis()
                owner = PaneSwipe.owner(owner, travel.x, travel.y, slop, change.isConsumed)
                if (owner == PaneSwipe.Owner.CHILD) return@awaitEachGesture
                if (owner != PaneSwipe.Owner.TABS) continue
                change.consume()
                val dx = with(density) { travel.x.toDp().value }
                val s = state.value
                val onSide = if (s.split == SplitMode.OFF) Side.A else side
                val current = if (onSide == Side.A) s.activeA else s.activeB
                val next = PaneSwipe.nextTab(current, PaneSwipe.stepFor(dx), vm.tabCount())
                motion.drag(
                    side = side,
                    dxPx = travel.x,
                    paneWidthPx = paneWidth,
                    to = next?.let { vm.tabTitleAt(it) },
                    can = next != null,
                    progress = (kotlin.math.abs(dx) / at.distanceDp).coerceIn(0f, 1f),
                    toIndex = next,
                )
            }
            if (owner != PaneSwipe.Owner.TABS) return@awaitEachGesture
            val upAt = System.currentTimeMillis()
            val dx = with(density) { travel.x.toDp().value }
            val dy = with(density) { travel.y.toDp().value }
            // AVERAGE speed over the movement: a flick's last sample reads as either standing
            // still or infinity depending on how it rounds.
            val began = if (movedAt == 0L) downAt else movedAt
            val speed = dx * 1000f / (upAt - began).coerceAtLeast(1L)
            val step = PaneSwipe.stepFor(dx)
            val fire = motion.can && PaneSwipe.fires(dx, dy, speed, began - downAt, at)
            motion.release(fire, step) { vm.stepTab(side, step) }
        }
    }
}

/**
 * A side's pane, and while it is being swiped, the tab it is being swiped to - drawn for real
 * and moving with the finger as one strip.
 *
 * A swipe used to slide the pane over empty space with a named chip in it, which read as
 * swiping onto nothing. Now the neighbour is there, at its own scroll position, one pane-width
 * along from the pane leaving.
 *
 * ## One composition, carried across the switch
 *
 * Each pane is drawn under `key(pane.id)` in this one parent. When a committed swipe makes the
 * neighbour the current pane, Compose moves that keyed group into the current slot instead of
 * composing the pane again. The first version drew the neighbour as a composition of its own
 * and dropped it on the switch, so the pane that took over started from nothing the moment the
 * motion had finished - every folder total and count, folder preview and not-yet-cached
 * thumbnail went back to empty and filled in again, which read as the tab appearing a second
 * time. Carried across, it is already filled in.
 *
 * [content] draws a pane the way the body does: it is told whether it is drawing the current
 * pane, and given the modifier carrying its offset, which it must apply. With no tab that way
 * there is no neighbour, and the chip saying so stays - see [SwipePeek].
 */
@Composable
fun BoxScope.SwipePanes(
    motion: SwipeMotion,
    side: Side,
    vm: BrowserViewModel,
    current: PaneController,
    content: @Composable (pane: PaneController, isCurrent: Boolean, modifier: Modifier) -> Unit,
) {
    val swiping = motion.side == side && motion.offset != 0f
    val target = if (swiping && motion.can) motion.toIndex?.let { vm.tabs.value.getOrNull(it) } else null
    // The switch has landed: the tab the swipe was going to is this side's current pane. The
    // motion is let go of after this composition, and THIS one already draws it as settled - at
    // rest, alone, with no peek - so the frame that shows the new tab current never shows it
    // anywhere but in place.
    val settled = SwipeHold.settled(motion.side == side && motion.holding, target?.id, current.id)
    SideEffect { if (settled) motion.settle() }
    val neighbour = target?.takeIf { it !== current }
    for (pane in listOfNotNull(current, neighbour)) {
        key(pane.id) {
            val isCurrent = pane === current
            // ONE call site for both roles, and that is the whole of the flinch fix.
            //
            // The key alone was not enough. This used to read `if (current) content(...) else
            // content(...)`, and Compose gives each branch of an `if` a group of its own - so on
            // commit the arriving tab moved from the else-branch to the if-branch, its group was
            // a different one, and everything under it was composed afresh: folder totals,
            // previews and thumbnails all restarting, which is the tab visibly re-appearing as
            // it settles. Only the modifier depends on the role now; the composition is the same
            // group before and after the commit, so the key carries it across intact.
            val modifier = if (isCurrent) {
                if (settled) Modifier.fillMaxSize() else Modifier.fillMaxSize().swipeFollow(motion, side)
            } else {
                Modifier.fillMaxSize().graphicsLayer {
                    // From the right for the next tab, from the left for the previous one.
                    translationX = if (motion.offset < 0f) motion.offset + size.width else motion.offset - size.width
                }
            }
            content(pane, isCurrent, modifier)
        }
    }
    if (swiping && neighbour == null && !settled) SwipePeek(motion, side)
}

/** When a finished tab swipe lets go. Pure so the rule behind the flash fix is tested. */
internal object SwipeHold {

    /**
     * Whether a held swipe is over: it is holding, and the tab it was going to is the side's
     * current pane. Until then the arriving tab stays drawn as the neighbour, fully in view, and
     * the tab being left stays off screen beside it.
     */
    fun settled(holding: Boolean, targetId: Int?, currentId: Int): Boolean =
        holding && targetId != null && targetId == currentId
}

/**
 * The next tab, peeking in from the edge it would arrive from, named.
 *
 * The part that makes the gesture discoverable and the part that makes it a choice: it says
 * which tab a release lands on, turns accent when a release would land, and says so when there
 * is no tab that way - an ignored swipe and one that has run out of strip look identical
 * otherwise.
 */
@Composable
fun BoxScope.SwipePeek(motion: SwipeMotion, side: Side) {
    if (motion.side != side || motion.offset == 0f) return
    val colors = Filet.colors
    val ready = motion.can && motion.progress >= 1f
    val fromRight = motion.offset < 0f
    Row(
        Modifier
            .align(if (fromRight) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(horizontal = 10.dp)
            .graphicsLayer {
                // Slides in with the drag, from just past the edge.
                val hidden = (1f - motion.progress) * 60.dp.toPx()
                translationX = if (fromRight) hidden else -hidden
                alpha = (motion.progress * 1.6f).coerceIn(0f, 1f)
            }
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .border(1.dp, if (ready) colors.accent.copy(alpha = 0.55f) else colors.lineSoft, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!fromRight) {
            Icon(FiletIcons.Back, null, tint = if (ready) colors.accent else colors.fg3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            motion.to ?: "No tab that way",
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = if (ready) colors.accent else colors.fg2,
        )
        if (fromRight) {
            Spacer(Modifier.width(8.dp))
            Icon(FiletIcons.Forward, null, tint = if (ready) colors.accent else colors.fg3, modifier = Modifier.size(14.dp))
        }
    }
}
