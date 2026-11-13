package dev.niccc2007.filet.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionSpec
import dev.niccc2007.filet.data.SectionLayout
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Home's edit state, as Desktop/filet-home-edit-mock.html draws it; the translation spec is
 * .unlazy/home-edit-mock/SPEC.md. Home stays Home: every card is drawn in full and live, each
 * under a strip that moves it, opens its options or hides it.
 */

/** ease-out as CSS spells it: the mock's `.18s ease-out`. */
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** How long an arrow's move keeps the card lifted, the mock's `move()`. */
private const val ARROW_LIFT_MS = 260L

// ── the header ───────────────────────────────────────────────────────────────────────────

/**
 * The pane's header while Home is being edited. It takes the header's place and height - 36dp,
 * the pane header's 26dp row and its 5dp above and below, where the mock's header is 40 - so
 * nothing below it moves when the state is entered.
 *
 * @param undo whether the last thing done was Reset, in which case the same spot takes it back.
 */
@Composable
fun HomeEditHeader(undo: Boolean, onReset: () -> Unit, onUndo: () -> Unit, onDone: () -> Unit) {
    val colors = Filet.colors
    val accentDim = MaterialTheme.colorScheme.primaryContainer
    BackHandler(onBack = onDone)
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(accentDim)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(FiletIcons.Rename, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(
            "Editing Home",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (undo) "Undo" else "Reset",
            fontSize = 12.sp,
            color = if (undo) colors.accent else colors.fg2,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = if (undo) onUndo else onReset)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            "Done",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(colors.accent)
                .clickable(onClick = onDone)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ── the cards ────────────────────────────────────────────────────────────────────────────

/**
 * Every card Home shows, each in its frame under its strip, then the hidden ones in a tray.
 *
 * A card is dragged by its handle the way [dev.niccc2007.filet.browser.SectionEditor] drags its
 * rows - the held card follows the finger while the cards it passes slide out of its way, and the
 * drop is one [onLayout] - with one addition the list of names never needed: these cards are
 * tall, so a card held near the top or bottom edge scrolls the list under it. The arrows are the
 * same move one place at a time.
 *
 * @param options by card id, that card's options; a card missing from the map has nothing to set.
 * @param body draws a card's own contents, live.
 * @param viewport the list's bounds in the window, for the edge scroll and for bringing an
 *   opened card's options into view.
 */
@Composable
internal fun HomeEditCards(
    specs: List<SectionSpec>,
    layout: SectionLayout,
    coach: Boolean,
    listState: LazyListState,
    viewport: () -> androidx.compose.ui.geometry.Rect?,
    onTouched: () -> Unit,
    onLayout: (SectionLayout) -> Unit,
    options: Map<String, @Composable () -> Unit>,
    body: @Composable (String) -> Unit,
) {
    val colors = Filet.colors
    val byId = specs.associateBy { it.id }
    val visible = layout.visible.filter { it in byId }
    val hidden = layout.hiddenOnes.filter { it in byId }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var open by remember { mutableStateOf<String?>(null) }
    var arrowLifted by remember { mutableStateOf<String?>(null) }
    val heightOf = remember { mutableStateMapOf<String, Float>() }
    /** Each card's top in the window. Read only in handlers, so a plain map: nothing redraws on it. */
    val topOf = remember { HashMap<String, Float>() }
    val fallback = with(density) { 120.dp.toPx() }
    val heights = visible.map { heightOf[it] ?: fallback }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    /** The finger's height in the window while a card is held, for the edge scroll. */
    var fingerY by remember { mutableFloatStateOf(0f) }
    val target = dragging?.let { dev.niccc2007.filet.browser.SectionDrag.target(heights, it, dragDy) }
    val heldPx = dragging?.let { heights.getOrNull(it) } ?: 0f
    val current = rememberUpdatedState(layout)
    val sizes = rememberUpdatedState(heights)
    val change = rememberUpdatedState(onLayout)
    val touched = rememberUpdatedState(onTouched)

    // The edge scroll. A held card near the list's top or bottom edge scrolls the list under it,
    // faster the nearer it is, and the card is carried by the same amount so it stays under the
    // finger: without this a card could only be dragged as far as the screen it started on.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        val zone = with(density) { 56.dp.toPx() }
        val most = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val v = viewport() ?: continue
            val step = when {
                fingerY < v.top + zone -> -most * (1f - ((fingerY - v.top) / zone).coerceIn(0f, 1f))
                fingerY > v.bottom - zone -> most * (1f - ((v.bottom - fingerY) / zone).coerceIn(0f, 1f))
                else -> 0f
            }
            if (step != 0f) dragDy += listState.scrollBy(step)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (coach) {
            Text(
                buildAnnotatedString {
                    val strong = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    append("Everything here is live. ")
                    withStyle(strong) { append("Drag") }
                    append(" a card by its handle, tap ")
                    withStyle(strong) { append("Options") }
                    append(" to change it, or the ")
                    withStyle(strong) { append("eye") }
                    append(" to hide it.")
                },
                fontSize = 11.5.sp,
                lineHeight = 15.5.sp,
                color = colors.fg2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.sel)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
        visible.forEachIndexed { i, id ->
            key(id) {
                val spec = byId.getValue(id)
                val shift = remember { Animatable(0f) }
                val want = when {
                    dragging == null || i == dragging || target == null -> 0f
                    dragging!! < i && i <= target -> -heldPx
                    dragging!! > i && i >= target -> heldPx
                    else -> 0f
                }
                LaunchedEffect(want, dragging) {
                    if (dragging == null) shift.snapTo(0f) else shift.animateTo(want, tween(180, easing = EaseOut))
                }
                val index by rememberUpdatedState(i)
                val lifted = i == dragging || arrowLifted == id
                val lift by animateFloatAsState(if (lifted) 1f else 0f, tween(180, easing = EaseOut), label = "cardLift")
                val opts = options[id]
                EditFrame(
                    lift = lift,
                    modifier = Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (i == dragging) dragDy else shift.value }
                        .onSizeChanged { heightOf[id] = it.height.toFloat() }
                        .onGloballyPositioned { topOf[id] = it.boundsInWindow().top },
                ) {
                    EditStrip(
                        name = spec.title,
                        optionsOpen = open == id,
                        first = i == 0,
                        last = i == visible.lastIndex,
                        handle = Modifier.pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    touched.value()
                                    open = null
                                    dragging = index
                                    dragDy = 0f
                                    fingerY = (topOf[id] ?: 0f) + at.y
                                },
                                onDrag = { c, delta ->
                                    c.consume()
                                    dragDy += delta.y
                                    fingerY += delta.y
                                },
                                onDragEnd = {
                                    val from = dragging
                                    val to = from?.let { dev.niccc2007.filet.browser.SectionDrag.target(sizes.value, it, dragDy) }
                                    if (from != null && to != null && to != from) change.value(current.value.move(from, to))
                                    dragging = null
                                    dragDy = 0f
                                },
                                onDragCancel = { dragging = null; dragDy = 0f },
                            )
                        },
                        onOptions = {
                            touched.value()
                            open = if (open == id) null else id
                            if (open == id) {
                                // To the card's top, as the mock scrolls it, so the options and
                                // the card they change are on screen together.
                                scope.launch {
                                    delay(30)
                                    val v = viewport() ?: return@launch
                                    val top = topOf[id] ?: return@launch
                                    listState.animateScrollBy(top - v.top, tween(260, easing = EaseOut))
                                }
                            }
                        },
                        onMove = { by ->
                            touched.value()
                            val to = index + by
                            if (to in visible.indices) {
                                onLayout(current.value.move(index, to))
                                scope.launch {
                                    arrowLifted = id
                                    delay(ARROW_LIFT_MS)
                                    if (arrowLifted == id) arrowLifted = null
                                }
                            }
                        },
                        onHide = {
                            touched.value()
                            if (open == id) open = null
                            onLayout(current.value.hide(id, emptySet()))
                        },
                    )
                    if (open == id) {
                        OptPanel { if (opts != null) opts() else Hint("This card has nothing to set.") }
                    }
                    body(id)
                }
            }
        }
        if (hidden.isNotEmpty()) {
            HiddenTray(hidden.map { byId.getValue(it) }) { id ->
                touched.value()
                onLayout(current.value.show(id))
            }
        }
        Text(
            "Saved as you go, with your other settings. Export and Import carry it.",
            fontSize = 10.5.sp,
            color = colors.fg3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        )
    }
}

/**
 * A card's frame: a dashed accentDim outline that turns solid accent while the card is lifted,
 * when it also grows by 2% and casts a shadow.
 */
@Composable
private fun EditFrame(lift: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val accentDim = MaterialTheme.colorScheme.primaryContainer
    val accent = Filet.colors.accent
    val ground = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
            .graphicsLayer {
                val s = 1f + 0.02f * lift
                scaleX = s
                scaleY = s
                shadowElevation = 13.dp.toPx() * lift
                this.shape = shape
                clip = false
            }
            .clip(shape)
            .background(ground)
            .drawBehind {
                val w = 1.5.dp.toPx()
                val solid = lift > 0.5f
                drawRoundRect(
                    color = if (solid) accent else accentDim,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(15.dp.toPx() - w / 2),
                    style = Stroke(
                        w,
                        pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            },
    ) { content() }
}

/** The strip on top of a card: handle, name, Options, up, down, eye. */
@Composable
private fun EditStrip(
    name: String,
    optionsOpen: Boolean,
    first: Boolean,
    last: Boolean,
    handle: Modifier,
    onOptions: () -> Unit,
    onMove: (Int) -> Unit,
    onHide: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 34.dp, height = 30.dp).then(handle), contentAlignment = Alignment.Center) {
            Icon(FiletIcons.Menu, "Drag to move $name", tint = colors.fg3, modifier = Modifier.size(17.dp))
        }
        Text(
            name.uppercase(Locale.US),
            fontSize = 11.sp,
            letterSpacing = 0.7.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StripButton(FiletIcons.Tune, "Options", on = optionsOpen, onClick = onOptions)
        StripButton(FiletIcons.CaretUp, null, arrow = true, enabled = !first, describe = "Move $name up") { onMove(-1) }
        StripButton(FiletIcons.CaretDown, null, arrow = true, enabled = !last, describe = "Move $name down") { onMove(1) }
        StripButton(FiletIcons.Eye, null, describe = "Hide $name", onClick = onHide)
    }
}

@Composable
private fun StripButton(
    icon: ImageVector,
    label: String?,
    on: Boolean = false,
    arrow: Boolean = false,
    enabled: Boolean = true,
    describe: String? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ink = if (on) colors.accent else colors.fg2
    Row(
        Modifier
            .height(30.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(RoundedCornerShape(9.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (arrow) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, describe ?: label, tint = ink, modifier = Modifier.size(15.dp))
        if (label != null) Text(label, fontSize = 11.5.sp, color = ink, maxLines = 1)
    }
}

/** Hidden cards: a dashed tray after the last card, one line each with Show. */
@Composable
private fun HiddenTray(cards: List<SectionSpec>, onShow: (String) -> Unit) {
    val colors = Filet.colors
    val line = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(
                    color = line,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(13.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(vertical = 6.dp),
    ) {
        Text(
            "HIDDEN",
            fontSize = 9.5.sp,
            letterSpacing = 0.9.sp,
            color = colors.fg3,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 3.dp),
        )
        for (c in cards) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(FiletIcons.EyeOff, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
                Text(c.title, fontSize = 12.5.sp, color = colors.fg3, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    "Show",
                    fontSize = 12.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onShow(c.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * A card's contents while editing, drawn and live but not pressable: a tap there would open a
 * file and leave Home mid-edit. The cover takes the press and consumes nothing, so the list
 * still scrolls when the drag starts on a card.
 */
@Composable
internal fun Inert(content: @Composable () -> Unit) {
    Box {
        content()
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
        )
    }
}

/** What a card says in the edit state when it has nothing to show right now. */
@Composable
internal fun EditPlaceholder(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
    )
}

// ── options ──────────────────────────────────────────────────────────────────────────────

/** A card's options, under its strip and above its contents, so the effect is right below the control. */
@Composable
private fun OptPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Filet.colors.sunken)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) { content() }
}

@Composable
private fun OptRow(label: String, top: Boolean = false, content: @Composable () -> Unit) {
    Row(verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = Filet.colors.fg3, modifier = Modifier.width(96.dp).padding(top = if (top) 6.dp else 0.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 10.5.sp, lineHeight = 14.sp, color = Filet.colors.fg3)
}

/**
 * The mock's `.seg`: choices side by side in one bordered strip, the chosen ones filled. Wraps
 * when they do not fit, as the mock's does, rather than squeezing the words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Seg(choices: List<Pair<T, String>>, isOn: (T) -> Boolean, onPick: (T) -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(8.dp)
    FlowRow(Modifier.clip(shape).border(1.dp, colors.lineSoft, shape)) {
        for ((value, label) in choices) {
            val on = isOn(value)
            Text(
                label,
                fontSize = 11.5.sp,
                maxLines = 1,
                softWrap = false,
                color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier
                    .background(if (on) colors.accent else Color.Transparent)
                    .clickable { onPick(value) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

/** Storage's options: the layout, folders from one app, and the capacity bars. */
@Composable
internal fun StorageOptions(
    layout: HomeArrange.Layout,
    onLayout: (HomeArrange.Layout) -> Unit,
    perApp: HomeArrange.PerApp,
    onPerApp: (HomeArrange.PerApp) -> Unit,
    bars: Boolean,
    onBars: (Boolean) -> Unit,
) {
    OptRow("Layout") { Seg(HomeArrange.Layout.entries.map { it to it.label }, { it == layout }, onLayout) }
    OptRow("Folders from one app") { Seg(HomeArrange.PerApp.entries.map { it to it.label }, { it == perApp }, onPerApp) }
    OptRow("Capacity bars") { Seg(listOf(true to "Show", false to "Hide"), { it == bars }, onBars) }
    Hint("Hold a drive and drag it to move it. The eye on a drive takes just that drive off Home.")
}

/** New files' and Recently opened's options: how many rows, how tight, and whether both share it. */
@Composable
internal fun FeedOptions(
    count: Int,
    onCount: (Int) -> Unit,
    density: Float,
    onDensity: (Float) -> Unit,
    linked: Boolean,
    onLinked: (Boolean) -> Unit,
) {
    OptRow("Show") { Seg(FeedCard.COUNTS.map { it to it.toString() }, { it == count }, onCount) }
    OptRow("Density", top = true) {
        Column {
            FeedDensitySlider(density, onDensity)
            Row {
                Text("Comfortable", fontSize = 9.5.sp, color = Filet.colors.fg3, modifier = Modifier.weight(1f))
                Text("Compact", fontSize = 9.5.sp, color = Filet.colors.fg3)
            }
        }
    }
    OptRow("Both feeds") { Seg(listOf(true to "Same density", false to "Each its own"), { it == linked }, onLinked) }
}

/** Categories' options: which show, and list or gallery for the three that offer it. */
@Composable
internal fun CategoryOptions(
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    layouts: Map<String, CategoryLayout>,
    onLayout: (MediaCategory, CategoryLayout) -> Unit,
) {
    OptRow("Shown", top = true) {
        Seg(MediaCategory.entries.map { it to it.label }, { it.id !in hidden }) { onToggle(it.id) }
    }
    val offered = MediaCategory.entries.filter(CategoryLayout::offeredFor)
    OptRow("Opens as", top = true) {
        Seg(
            offered.map { c ->
                val now = CategoryLayout.of(layouts, c)
                c to "${c.label}: ${if (now == CategoryLayout.GALLERY) "gallery" else "list"}"
            },
            { CategoryLayout.of(layouts, it) == CategoryLayout.GALLERY },
        ) { c ->
            val now = CategoryLayout.of(layouts, c)
            onLayout(c, if (now == CategoryLayout.GALLERY) CategoryLayout.LIST else CategoryLayout.GALLERY)
        }
    }
}

/**
 * The feeds' density slider, at the size of the words beside it: a 4dp thumb on a 10dp track, as
 * the Settings page draws its compact sliders.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FeedDensitySlider(value: Float, onChange: (Float) -> Unit) {
    val colors = Filet.colors
    val sliderColors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.raised,
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = sliderColors,
            interactionSource = interaction,
            thumb = {
                androidx.compose.material3.SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = sliderColors,
                    thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 24.dp),
                )
            },
            track = { state ->
                androidx.compose.material3.SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(10.dp),
                    colors = sliderColors,
                )
            },
        )
    }
}

/**
 * A hold on whatever part of Home nothing else answered: the space between and around the cards.
 * This, and Edit Home in a held heading's menu, are the ways into the edit state; the header has
 * no control for it, since a button there either did not fit beside search or sat oddly in a
 * header every tab shares.
 *
 * Every row, drive, chip, link and heading on Home takes its own press - a tap detector consumes
 * the press as it lands - so waiting for an unconsumed press is exactly "nothing here wanted it".
 * The press itself is left unconsumed, so the list still scrolls and the pane still takes focus on
 * a tap; a drag that starts the scroll cancels the hold. Only once the hold has fired is the rest
 * of the press taken, so its release is not read as a tap by anything around it.
 */
internal suspend fun PointerInputScope.holdOnEmpty(onHold: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        val held = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation()
            }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (!held) return@awaitEachGesture
        onHold()
        do {
            val e = awaitPointerEvent()
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
    }
}
