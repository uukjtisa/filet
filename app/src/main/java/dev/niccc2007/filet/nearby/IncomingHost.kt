package dev.niccc2007.filet.nearby

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.ui.theme.DarkGround
import kotlin.math.roundToInt

/**
 * A send from another Filet, asked about while Filet is on screen: the same side card, mini card
 * or full dialog that asks over other apps, drawn over Filet's own screen - the browser, a viewer,
 * whatever is up. View opens the same viewer, full screen or in a floating window.
 *
 * Bug identified here: Filet on screen asked with a dialogue of its own, a second design for the
 * same question that showed less than the card over other apps - no thumbnails, nothing to view
 * before accepting - and took the whole screen for a question the card asks from a corner. One
 * look now, decided by [IncomingRules.look] for both places.
 *
 * Composed last at the root of Filet's screen, so it is above everything there. Where nothing is
 * drawn, touches go through to the screen underneath.
 */
@Composable
fun IncomingHost(vm: BrowserViewModel) {
    val inbox = vm.nearby.inbox
    val asking by inbox.asking.collectAsState()
    val pairing by inbox.pairing.collectAsState()
    val settings by vm.nearby.incoming.settings.collectAsState()
    // The last offer, so the card can slide out with what it said once it has been answered.
    var last by remember { mutableStateOf<PushOffer?>(null) }
    asking?.let { if (last?.id != it.id) last = it }
    val offer = last ?: return
    // A pairing question goes first; it is Filet's own dialogue, in NearbyPopups.
    val up = asking != null && pairing == null
    var viewing by remember(offer.id) { mutableStateOf<Int?>(null) }
    var full by remember(offer.id) { mutableStateOf(false) }
    val look = IncomingRules.look(settings, offer.files.size)
    val thumbs = rememberThumbs(offer)

    DarkGround(CARD_BOTTOM) {
        Box(Modifier.fillMaxSize()) {
            val v = viewing
            when {
                !up -> Unit
                v != null && settings.view == ViewStyle.FLOAT && !full -> FloatInFilet(
                    offer, v, settings, thumbs,
                    onClose = { viewing = null },
                    onFull = { full = true },
                )
                v != null -> {
                    BackHandler { viewing = null; full = false }
                    FullViewer(
                        offer, v, settings, thumbs,
                        onClose = { viewing = null; full = false },
                        onSave = { inbox.accept(offer, always = false) },
                        onOpenFilet = null,
                        onDecline = { inbox.decline(offer) },
                    )
                }
                look == AskLook.DIALOG -> {
                    BackHandler { inbox.later() }
                    FullDialog(
                        offer, settings, thumbs,
                        onAccept = { inbox.accept(offer, always = false) },
                        onDecline = { inbox.decline(offer) },
                        onClose = { inbox.later() },
                        onView = { viewing = it },
                    )
                }
                else -> Unit
            }
            // The card stays composed while it slides out, and while a viewer is open it is
            // hidden rather than gone, so closing the viewer brings it straight back.
            AnimatedVisibility(
                visible = up && viewing == null && look != AskLook.DIALOG,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp),
                enter = slideInHorizontally(tween(SLIDE_MS, easing = SLIDE)) { (it * 1.2f).roundToInt() } +
                    fadeIn(tween(SLIDE_MS, easing = SLIDE)),
                exit = slideOutHorizontally(tween(EXIT_MS)) { (it * 1.2f).roundToInt() } + fadeOut(tween(EXIT_MS)),
            ) {
                AskCard(offer, look, inbox) { viewing = it }
            }
        }
    }
}

/**
 * The ask in its card looks, as both places draw it: the mini card for one file, the side card
 * for several. Swiped toward its edge it goes, and the send waits as the close button leaves it.
 */
@Composable
internal fun AskCard(offer: PushOffer, look: AskLook, inbox: PushInbox, onView: (Int) -> Unit) {
    val later = { inbox.later() }
    Swipeable(onGone = later) {
        if (look == AskLook.MINI) {
            MiniCard(offer, onGet = { inbox.accept(offer, always = false) }, onClose = later, onView = onView)
        } else {
            SideCard(
                offer,
                onAccept = { always -> inbox.accept(offer, always) },
                onDecline = { inbox.decline(offer) },
                onClose = later,
                onView = onView,
            )
        }
    }
}

/**
 * The floating viewer inside Filet: a box over the screen, dragged by its body and sized by its
 * corner, starting in the bottom corner clear of the bars.
 *
 * Decision: drawn in Filet's own layout rather than in a window of its own. A window moved under
 * the finger reports the finger against where the window now is, so every step of a drag partly
 * undoes itself; inside the layout each position is measured against the current place and the
 * drag is exact. Over other apps there is no layout to draw in, and the overlay moves its window
 * by the screen's own coordinates instead.
 */
@Composable
private fun FloatInFilet(
    offer: PushOffer,
    start: Int,
    settings: IncomingSettings,
    thumbs: Map<String, ImageBitmap>,
    onClose: () -> Unit,
    onFull: () -> Unit,
) {
    BackHandler(onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val density = LocalDensity.current
        val roomW = constraints.maxWidth.toFloat()
        val roomH = constraints.maxHeight.toFloat()
        val margin = with(density) { 12.dp.toPx() }
        val minW = with(density) { FLOAT_MIN_W.toPx() }
        val minH = with(density) { FLOAT_MIN_H.toPx() }
        var w by remember { mutableFloatStateOf(with(density) { FLOAT_W.toPx() }.coerceAtMost(roomW)) }
        var h by remember { mutableFloatStateOf(with(density) { FLOAT_H.toPx() }.coerceAtMost(roomH)) }
        var x by remember { mutableFloatStateOf((roomW - w - margin).coerceAtLeast(0f)) }
        var y by remember { mutableFloatStateOf((roomH - h - margin).coerceAtLeast(0f)) }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() }),
        ) {
            FloatViewer(
                offer, start, settings, thumbs,
                onClose = onClose,
                onFull = onFull,
                onMove = { dx, dy ->
                    x = (x + dx).coerceIn(0f, (roomW - w).coerceAtLeast(0f))
                    y = (y + dy).coerceIn(0f, (roomH - h).coerceAtLeast(0f))
                },
                onResize = { dx, dy ->
                    w = (w + dx).coerceIn(minW.coerceAtMost(roomW - x), roomW - x)
                    h = (h + dy).coerceIn(minH.coerceAtMost(roomH - y), roomH - y)
                },
            )
        }
    }
}

/** `.card{animation:slide .32s cubic-bezier(.2,.9,.25,1)}`, shared with the overlay. */
internal const val SLIDE_MS = 320
internal const val EXIT_MS = 200
internal val SLIDE = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/** `.float` in the incoming mock: its size at first, and the smallest it can be made. */
internal val FLOAT_W = 240.dp
internal val FLOAT_H = 150.dp
internal val FLOAT_MIN_W = 160.dp
internal val FLOAT_MIN_H = 100.dp
