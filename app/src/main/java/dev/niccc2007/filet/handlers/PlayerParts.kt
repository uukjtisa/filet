package dev.niccc2007.filet.handlers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.playback.Medium
import dev.niccc2007.filet.playback.NowPlaying
import dev.niccc2007.filet.playback.Repeat
import dev.niccc2007.filet.playback.Track
import dev.niccc2007.filet.playback.marqueeFade
import dev.niccc2007.filet.playback.rememberMarquee
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The pieces every player face is built from.
 *
 * ## Why this file exists
 *
 * There are four faces. Four faces written as four screens is four transports, four scrub
 * bars and four queue panels, and the fourth copy of a control is the one that silently stops
 * matching the others - the player ends up with a repeat button that cycles the wrong way on
 * one layout and nobody notices for a month.
 *
 * So a face is only an ARRANGEMENT. Every control in it comes from here, and the only thing
 * `AudioScreen` does per face is decide what goes where and how big. That is also what keeps a
 * face cheap enough to be worth having: adding one is a layout, not a player.
 *
 * Measurements are the mock's, taken off the CSS rather than matched by eye - see
 * `filet-bar-dock-mock.html` section 3. Where a number appears here with no comment it is that
 * file's value at `423dp`.
 */

// ── the top bar ───────────────────────────────────────────────────────────────

/**
 * The bar across the top: close, where this is playing from, and the mode.
 *
 * Replaces `ViewerBar` on the three ambient faces. The difference is not decoration: a
 * `ViewerBar` states the FILE, and on a player the file's name is already the title in the
 * middle of the screen. What is missing there - and what the mock promotes to the bar - is the
 * CONTEXT: which folder this queue came from, and whether anything is allowed to interrupt it.
 *
 * `.pltop{display:flex;align-items:center;gap:10px;padding:12px 12px 2px}` with 36dp icons.
 *
 * @param eyebrow the small upper line. The word "Uninterrupted" when that is on, because a lit
 *   glyph is a state somebody has to learn, and otherwise what the queue is.
 * @param context the line under it: the folder, as a path tail.
 */
@Composable
fun PlayerTopBar(
    eyebrow: String,
    eyebrowLit: Boolean,
    context: String,
    uninterrupted: Boolean,
    onClose: () -> Unit,
    onUninterrupted: () -> Unit,
    tint: Color,
    overflow: @Composable () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A chevron DOWN, not a back arrow. The player is a thing that comes up over the app
        // and goes back down to a strip; an arrow pointing left promises the file list.
        BarIcon(FiletIcons.Down, "Minimise the player", onClick = onClose)

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                eyebrow,
                fontSize = 8.5.sp,
                letterSpacing = 1.5.sp,
                fontWeight = if (eyebrowLit) FontWeight.Bold else FontWeight.Normal,
                color = if (eyebrowLit) tint else colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                context,
                fontSize = 11.5.sp,
                color = colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Uninterrupted lives HERE and not in the chip row. It is a mode, and a mode belongs
        // in the chrome where its state can be read without anything being opened - and it is
        // not trivia: it is the switch that decides whether a phone call takes the sound away.
        BarIcon(
            FiletIcons.Shield,
            if (uninterrupted) "Uninterrupted — nothing will pause this" else "Uninterrupted playback",
            lit = uninterrupted,
            tint = tint,
            onClick = onUninterrupted,
        )
        overflow()
    }
}

/** One 36dp round button in the top bar. `.pltop .ico{width:36px;height:36px;border-radius:18px}` */
@Composable
fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    lit: Boolean = false,
    tint: Color = Filet.colors.accent,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            // `.ico.mode.on{color:var(--pal);background:var(--palSoft)}`
            .background(if (lit) tint.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = if (lit) tint else colors.fg2,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** The overflow, with the actions that are neither modes nor frequent. */
@Composable
fun PlayerOverflow(
    face: dev.niccc2007.filet.playback.PlayerFace,
    onFace: (dev.niccc2007.filet.playback.PlayerFace) -> Unit,
    onExternal: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onSaveQueue: (() -> Unit)? = null,
    /** Aurora's own switch. Null on every other face, where it does nothing. */
    scratch: Boolean? = null,
    onScratch: (() -> Unit)? = null,
    /** The background glow, which every face has. */
    glow: Boolean? = null,
    onGlow: (() -> Unit)? = null,
    /** The glow answering the music. Null when the glow is off - it is its sub-switch. */
    pulse: Boolean? = null,
    onPulse: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var faces by remember { mutableStateOf(false) }
    val colors = Filet.colors
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box {
        BarIcon(FiletIcons.More, "More") { open = true; faces = false }
        if (open) {
            Popup(
                popupPositionProvider = BelowPositionProvider(gap),
                onDismissRequest = { open = false; faces = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(254.dp)
                        .shadow(24.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.raised)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(22.dp))
                        .padding(vertical = 14.dp),
                ) {
                    if (faces) {
                        PanelHead("THE LOOK")
                        for (f in dev.niccc2007.filet.playback.PlayerFace.entries) {
                            FaceRow(f, f == face) { open = false; faces = false; onFace(f) }
                        }
                    } else {
                        PanelHead("NOW PLAYING")
                        // The face switcher is here rather than only in Settings. Five looks
                        // are worth nothing if changing them means leaving the thing you are
                        // looking at - this is the one screen where the difference shows.
                        PanelAction(
                            "Look",
                            FiletIcons.Eye,
                            trailing = face.label,
                        ) { faces = true }
                        if (onAddToPlaylist != null) {
                            PanelAction("Add to a playlist", FiletIcons.Rows) {
                                open = false
                                onAddToPlaylist()
                            }
                        }
                        if (onSaveQueue != null) {
                            // "Save the queue" rather than "new playlist": the queue is
                            // already a list somebody arranged by playing it, and this keeps
                            // THAT rather than opening an empty one to fill in again.
                            PanelAction("Save the queue", FiletIcons.Save) {
                                open = false
                                onSaveQueue()
                            }
                        }
                        // Only where they mean something. The record's switch belongs to
                        // the record, and a switch on a face with no record on it is a
                        // control that does nothing visible - worse than one not offered.
                        if (scratch != null && onScratch != null) {
                            PanelRule()
                            PanelSwitch("Hear it while you drag", scratch, onScratch)
                        }
                        if (glow != null && onGlow != null) {
                            PanelRule()
                            PanelSwitch("Living glow", glow, onGlow)
                            // The pulse is the glow answering the music, so it appears
                            // UNDER the glow and only while the glow is on. Indented,
                            // because a sub-switch at the same margin as its parent is
                            // just a second switch.
                            if (pulse != null && onPulse != null) {
                                PanelSwitch("Pulse on the beat", pulse, onPulse, sub = true)
                            }
                        }
                        PanelRule()
                        PanelAction("Open in another app", FiletIcons.Open) {
                            open = false
                            onExternal()
                        }
                        PanelAction("Share", FiletIcons.Share) { open = false; onShare() }
                        PanelAction("Properties", FiletIcons.Info) {
                            open = false
                            onProperties()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The player's panel, and the strip's, are the same object.
 *
 * It was a `DropdownMenu` - square corners, full-bleed rows, a divider drawn edge to edge -
 * hanging off a player whose every other surface is rounded. Asked for directly: make it look
 * like the one on the minimised player. So it is the same shape, the same 22dp radius, the
 * same hairline border and the same eyebrow, and the two now read as one app.
 */
@Composable
private fun PanelHead(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 17.dp, bottom = 9.dp),
    )
}

/** A hairline that stops short of the edge, so the panel's corners stay round. */
@Composable
private fun PanelRule() {
    Box(
        Modifier
            .padding(horizontal = 17.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Filet.colors.lineSoft),
    )
}

/** One action: a glyph, a name, and - where it has one - the state it is in. */
@Composable
private fun PanelAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 12.5.sp, color = colors.fg2, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, color = colors.accent)
            Spacer(Modifier.width(4.dp))
            Icon(
                FiletIcons.Forward, null,
                tint = colors.fg3, modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** A row that is on or off, in the same panel as the actions. */
@Composable
private fun PanelSwitch(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    /** A switch that belongs to the one above it: indented, and a shade quieter. */
    sub: Boolean = false,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (sub) 31.dp else 17.dp, end = 17.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (sub) 11.5.sp else 12.5.sp,
            color = if (on) colors.accent else colors.fg2,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(width = 34.dp, height = 19.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) colors.accent.copy(alpha = 0.3f) else colors.sunken),
        ) {
            Box(
                Modifier
                    .padding(start = if (on) 17.dp else 2.dp)
                    .align(Alignment.CenterStart)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.accent else colors.fg3),
            )
        }
    }
}

/** One look, with the sentence that says what it is. */
@Composable
private fun FaceRow(
    f: dev.niccc2007.filet.playback.PlayerFace,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                f.label,
                fontSize = 12.5.sp,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (picked) colors.accent else colors.fg2,
            )
            Text(f.about, fontSize = 9.5.sp, lineHeight = 12.sp, color = colors.fg3)
        }
        if (picked) {
            Spacer(Modifier.width(10.dp))
            Icon(
                FiletIcons.Check, null,
                tint = colors.accent, modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Under the button and right-aligned to it: this one hangs off the top bar. */
private class BelowPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val room = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, room)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

// ── the artwork ───────────────────────────────────────────────────────────────

/**
 * The square: cover art, a looping peek of the video, or the placeholder.
 *
 * Shrinks when paused, and the shadow goes with it - the only feedback a player screen can
 * give about its own state without words. The shadow is animated rather than fixed because a
 * card that changes size under a constant shadow reads as a scaling bitmap rather than as an
 * object lifting.
 *
 * `.art{border-radius:20px;box-shadow:0 22px 50px rgba(0,0,0,.6)}`,
 * `.art.paused{transform:scale(.885)}`.
 */
@Composable
fun PlayerArt(
    art: android.graphics.Bitmap?,
    isVideo: Boolean,
    playing: Boolean,
    source: android.net.Uri?,
    plain: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    badge: Boolean = true,
) {
    val colors = Filet.colors
    val lift by animateFloatAsState(
        if (playing || plain) 1f else 0.885f,
        animationSpec = tween(380),
        label = "art lift",
    )
    val still: @Composable () -> Unit = {
        val picture = art
        if (picture != null) {
            Image(
                picture.asImageBitmap(), "Cover art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // No embedded art is normal, not a failure, so the placeholder is a piece of the
            // design rather than an error state.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (isVideo) FiletIcons.Video else FiletIcons.Audio, null,
                    tint = colors.accent.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxSize(0.32f),
                )
            }
        }
    }
    Box(
        modifier
            .then(
                if (plain) Modifier
                else Modifier
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .shadow(
                        elevation = (34 * lift * lift).dp,
                        shape = RoundedCornerShape(radius),
                        clip = false,
                    ),
            )
            .clip(RoundedCornerShape(if (plain) 14.dp else radius))
            .background(colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            dev.niccc2007.filet.playback.VideoPeekView(
                source = source,
                playing = playing,
                modifier = Modifier.fillMaxSize(),
                placeholder = still,
            )
        } else {
            still()
        }
    }
}

/**
 * The VIDEO pill on the artwork.
 *
 * Said on the picture rather than in a sentence under it, because the picture is the thing
 * that looks wrong: a music player holding a video is confusing until something names it.
 */
@Composable
fun BoxScope.VideoBadge(onWatch: () -> Unit, inset: Dp = 10.dp) {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(inset)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onWatch)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Video, null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            "WATCH",
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── the title block ───────────────────────────────────────────────────────────

/**
 * The title, what it came from, and the heart.
 *
 * Left-aligned on the ambient faces and centred on Classic. A centred title under a centred
 * square is a poster; a left-aligned one beside an action is a player, and the mock chose the
 * second because the row then has somewhere for the heart to live that is not a floating
 * button.
 *
 * `.meta{padding:24px 28px 0}`, `.ttl{font-size:22px;font-weight:750;letter-spacing:-.5px}`.
 *
 * ## There is no Watch here any more
 *
 * There used to be, and it meant Ambient, Editorial and Aurora each carried TWO - a badge on
 * the artwork and a chip beside the artist - which is what got reported. The badge is the one
 * that stays: it is on the thing it acts on, so pressing the picture to get the picture moving
 * is the obvious gesture, and it also marks the artwork as a still frame rather than a cover.
 * [WatchChip] exists for Immersive alone, where the artwork is the whole screen and has no
 * corner to put a badge in, and Classic keeps its own "playing as audio - Watch" line because
 * that face explains itself in words. One per face, and this block does one job.
 */
@Composable
fun PlayerTitleBlock(
    title: String,
    sub: String,
    isVideo: Boolean,
    centred: Boolean,
    titleSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    bookmarked: Boolean,
    tint: Color,
    onHeart: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A centred title with a 38dp button only on its right is not centred: the text sits
        // half a button to the left of the middle, which is just enough to read as a mistake
        // rather than as a choice. An empty one of the same size on the other side fixes it
        // with no control nobody asked for.
        if (centred) Spacer(Modifier.size(38.dp))
        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            MarqueeTitle(title, centred = centred, size = titleSize)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isVideo) {
                    Icon(
                        FiletIcons.Video, null,
                        tint = colors.fg3, modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    sub,
                    fontSize = 13.sp,
                    color = colors.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        // Bookmarks, not a second list of favourites. Filet already has one place that means
        // "keep this where I can find it", and a heart that fed a different one would be two
        // answers to the same question - with the second invisible everywhere else in the app.
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onHeart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (bookmarked) FiletIcons.HeartFilled else FiletIcons.Heart,
                if (bookmarked) "In Favourites \u2014 tap to remove" else "Add to Favourites",
                tint = if (bookmarked) tint else colors.fg3,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/**
 * A title that scrolls only when it does not fit.
 *
 * A filename is not a song title: it is long, it has no spaces and it cannot wrap, so ellipsis
 * always lost the end of it - which on `VID_20261009_120104` is the only part that differs
 * from its neighbours. Measured off the laid-out text rather than estimated from a character
 * count, because the answer depends on the font and on how wide the phone is.
 */
@Composable
fun MarqueeTitle(
    text: String,
    centred: Boolean,
    size: androidx.compose.ui.unit.TextUnit,
    colour: Color = MaterialTheme.colorScheme.onSurface,
) {
    var overflow by remember(text) { mutableFloatStateOf(0f) }
    val travel = rememberMarquee(overflow)
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .marqueeFade(overflow > 2f),
        contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = colour,
            // Read INSIDE the graphicsLayer block, so a moving title invalidates the draw
            // phase rather than recomposing this text on every frame. Same fault as the glow
            // had, and it has been here longer - a title that does not fit is the common
            // case, not the rare one. See `rememberMarquee`.
            modifier = Modifier.graphicsLayer { translationX = travel.value },
            onTextLayout = { layout ->
                overflow = maxOf(
                    0f,
                    layout.multiParagraph.maxIntrinsicWidth - layout.size.width,
                )
            },
        )
    }
}

// ── scrub and transport ───────────────────────────────────────────────────────

/** The bar and its two clocks. `.bar{margin:22px 28px 0}` + `.times{margin:6px 28px 0}`. */
@Composable
fun PlayerScrub(
    shownPosition: Long,
    state: NowPlaying,
    tint: Color,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    val colors = Filet.colors
    ScrubBar(
        positionMs = shownPosition,
        durationMs = state.durationMs,
        enabled = state.durationMs > 0,
        fill = tint,
        onScrub = onScrub,
        onScrubEnd = onScrubEnd,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            clockOf(shownPosition),
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
        )
        Spacer(Modifier.weight(1f))
        Text(
            remainingOf(shownPosition, state.durationMs),
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
        )
    }
}

/** Shuffle, previous, play, next, repeat. `.tb.sm 40 / .tb.mid 48 / .tb.big 68`. */
@Composable
fun PlayerTransport(
    state: NowPlaying,
    tint: Color,
    onTint: Color,
    scale: Float = 1f,
    playback: dev.niccc2007.filet.playback.Playback,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            FiletIcons.Shuffle, "Shuffle", size = (40 * scale).dp,
            active = state.shuffle, enabled = state.size > 2,
            fill = tint, onFill = onTint,
        ) { playback.setShuffle(!state.shuffle) }

        // Restart-then-jump is the player's rule, not the screen's. It used to be a
        // `position > 3_000L` branch written here, which meant the notification's Previous
        // button and this one behaved differently.
        TransportButton(FiletIcons.Prev, "Previous", size = (48 * scale).dp) { playback.previous() }

        TransportButton(
            if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
            if (state.playing) "Pause" else "Play",
            filled = true,
            size = (68 * scale).dp,
            enabled = state.current != null,
            // Borrowed from the artwork with a contrast floor forced onto it, so a dark track
            // cannot produce an invisible button. See ArtPalette.
            fill = tint,
            onFill = onTint,
        ) { playback.toggle() }

        TransportButton(FiletIcons.Next, "Next", size = (48 * scale).dp) { playback.next() }

        TransportButton(
            // The glyph carries the state. See FiletIcons.RepeatOne.
            if (state.repeat == Repeat.ONE) FiletIcons.RepeatOne else FiletIcons.Repeat,
            when (state.repeat) {
                Repeat.OFF -> "Repeat off"
                Repeat.ALL -> "Repeating the queue"
                Repeat.ONE -> "Repeating this track"
            },
            size = (40 * scale).dp,
            active = state.repeat != Repeat.OFF,
            fill = tint, onFill = onTint,
        ) {
            // The cycle lives on the player, because the notification's repeat button runs
            // the same one. Two copies of off-all-one-off is two places for the order to drift.
            playback.cycleRepeat()
        }
    }
}

// ── the chip row ──────────────────────────────────────────────────────────────

/**
 * Speed and the sleep timer.
 *
 * The genuinely secondary switches, and the row exists so they are not in the transport. Both
 * are real: the speed is `MediaPlayer.playbackParams` on the shared player, and the timer
 * pauses it. A chip that only looks like a control is worse than no chip.
 *
 * `.chip{font-size:10.5px;padding:6px 11px;border-radius:50px}`.
 */
@Composable
fun PlayerChips(
    speed: Float,
    maxSpeed: Float,
    sleepMinutesLeft: Int,
    pitch: dev.niccc2007.filet.playback.PitchMode,
    tint: Color,
    onTint: Color,
    playback: dev.niccc2007.filet.playback.Playback,
    onPitch: (dev.niccc2007.filet.playback.PitchMode) -> Unit,
) {
    var speedOpen by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PlayerChip(
            FiletIcons.Speed,
            speedLabel(speed),
            lit = speed != 1f,
            tint = tint,
        ) {
            // A sheet with a slider, not a cycle.
            //
            // The first cut cycled through five presets on tap, on the reasoning that the
            // values anybody uses fit in one button. Asked for directly: a slider, so a
            // speed can be SET rather than arrived at - and tapping a chip four times to go
            // from 1.0 to 0.75 is a control that makes you count.
            speedOpen = true
        }
        PlayerChip(
            FiletIcons.Timer,
            if (sleepMinutesLeft > 0) "${sleepMinutesLeft}m" else "Sleep",
            lit = sleepMinutesLeft > 0,
            tint = tint,
        ) {
            // 15, 30, 60, off. Same reasoning as the speed chip, and the same reason there is
            // no "end of track": a queue of two-second clips makes that meaningless.
            val next = when (sleepMinutesLeft) {
                0 -> 15
                in 1..15 -> 30
                in 16..30 -> 60
                else -> 0
            }
            playback.setSleepTimer(next)
        }
    }
    if (speedOpen) {
        // The pitch switch lives inside the sheet rather than beside the speed chip: it is a
        // question ABOUT the speed and is meaningless without one, since at 1.0x all three
        // modes leave the audio untouched. Where the speed is set is also where somebody
        // notices the warble and goes looking for the reason.
        SpeedSheet(
            speed = speed,
            maxSpeed = maxSpeed,
            pitch = pitch,
            tint = tint,
            onTint = onTint,
            onSpeed = { playback.setSpeed(it) },
            onPitch = onPitch,
            onDismiss = { speedOpen = false },
        )
    }
}



@Composable
private fun PlayerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    lit: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (lit) tint.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (lit) tint else colors.fg3,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.5.sp, color = if (lit) tint else colors.fg2)
    }
}

// ── the queue ─────────────────────────────────────────────────────────────────

/** The strip that pulls the queue up. `.qhandle{padding:13px 22px 16px}`. */
@Composable
fun QueueHandle(state: NowPlaying, onClick: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Rows, null, tint = colors.fg2, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text("Up next", fontSize = 12.5.sp, color = colors.fg2)
        Spacer(Modifier.weight(1f))
        Text(
            "${state.position} of ${state.size}",
            fontSize = 11.sp, color = colors.fg3,
        )
    }
}

/**
 * The queue, as a sheet that comes up over the player rather than a panel that pushes it.
 *
 * It used to be an `AnimatedVisibility` Column at the bottom of the layout, which shoved the
 * artwork and the transport up by 190dp every time it opened - so opening the queue moved the
 * Play button out from under the thumb that was about to press it. A sheet covers; it does not
 * rearrange.
 *
 * `.qsheet{top:30%;border-radius:20px 20px 0 0}`.
 */
@Composable
fun BoxScope.QueueSheet(
    tracks: List<Track>,
    index: Int,
    tint: Color,
    visible: Boolean,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Filet.colors
    // The scrim first, so a tap outside closes it. Without indication: it is a dismiss target,
    // not a button.
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Swallows the scrim's tap. `clickable(enabled = false)` still consumes.
                .clickable(enabled = false) {},
        ) {
            // `.grab{width:36px;height:4px;margin:9px auto 4px}`
            Box(
                Modifier
                    .padding(top = 9.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.high),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("Up next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(9.dp))
                Text(
                    "${tracks.size} in this folder",
                    fontSize = 11.5.sp, color = colors.fg3,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    fontSize = 11.5.sp, color = colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            QueueRows(tracks, index, tint, onPick)
        }
    }
}

@Composable
private fun QueueRows(tracks: List<Track>, index: Int, tint: Color, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    val listState = rememberLazyListState()
    LaunchedEffect(index) {
        // Keep the playing row on screen as the queue advances, without yanking the list when
        // it has been scrolled somewhere else on purpose.
        val seen = listState.layoutInfo.visibleItemsInfo
        if (seen.isNotEmpty() && index !in seen.first().index..seen.last().index) {
            runCatching { listState.animateScrollToItem(index) }
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val here = i == index
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (here) tint.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onPick(i) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    if (here) {
                        Icon(
                            FiletIcons.PlaySolid, null,
                            tint = tint, modifier = Modifier.size(12.dp),
                        )
                    } else {
                        Text(
                            "${i + 1}",
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                            color = colors.fg3,
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        fontSize = 12.5.sp,
                        fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (here) tint else MaterialTheme.colorScheme.onSurface,
                    )
                    // Which entry is the odd one out is the useful fact in a FOLDER queue, and
                    // a row of identical rows does not carry it.
                    if (t.medium == Medium.VIDEO) {
                        Text(
                            "video, playing as audio",
                            fontSize = 10.sp, color = colors.fg3,
                        )
                    }
                }
            }
        }
    }
}

// ── the immersive face's retractable controls ────────────────────────────────

/**
 * "Watch", for a face whose artwork has no corner to put a badge in.
 *
 * Same words and same job as [VideoBadge], drawn as a standalone chip: the immersive face's
 * picture fills the screen, so a badge on it would be a label floating in the middle of a
 * photograph rather than attached to a card.
 */
@Composable
fun WatchChip(onWatch: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onWatch)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Video, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Watch",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * The floating pill: title, transport, repeat, queue.
 *
 * A glass panel across the bottom is a second screen stuck to the first - it covers a third of
 * the picture permanently, which defeats the only thing the immersive face is for. These are
 * small, and tapping the picture retracts them.
 *
 * They FADE rather than unmount, so a tap always lands on something: a control that is gone
 * cannot be brought back by pressing where it was.
 *
 * `.minibar{padding:8px 10px;border-radius:50px}`, `.mb{34px}`, `.mb.pp{42px}`.
 */
@Composable
fun MiniControls(
    state: NowPlaying,
    title: String,
    sub: String,
    shownPosition: Long,
    tint: Color,
    onTint: Color,
    playback: dev.niccc2007.filet.playback.Playback,
    onQueue: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                clockOf(shownPosition),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.78f),
            )
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                ScrubBar(
                    positionMs = shownPosition,
                    durationMs = state.durationMs,
                    enabled = state.durationMs > 0,
                    fill = tint,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                )
            }
            Text(
                remainingOf(shownPosition, state.durationMs),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                // Its own ground, because it floats over a photograph: white text on an
                // unknown picture is unreadable about half the time.
                .background(Color.Black.copy(alpha = 0.46f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    title,
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        fontSize = 10.sp, color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MiniButton(FiletIcons.Prev, "Previous") { playback.previous() }
            MiniButton(
                if (state.playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                if (state.playing) "Pause" else "Play",
                size = 42.dp, ground = tint, ink = onTint,
            ) { playback.toggle() }
            MiniButton(FiletIcons.Next, "Next") { playback.next() }
            MiniButton(
                if (state.repeat == Repeat.ONE) FiletIcons.RepeatOne else FiletIcons.Repeat,
                when (state.repeat) {
                    Repeat.OFF -> "Repeat off"
                    Repeat.ALL -> "Repeating the queue"
                    Repeat.ONE -> "Repeating this track"
                },
                ink = if (state.repeat != Repeat.OFF) tint else Color.White,
            ) { playback.cycleRepeat() }
            MiniButton(FiletIcons.Rows, "Up next", onClick = onQueue)
        }
    }
}

@Composable
private fun MiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Dp = 34.dp,
    ground: Color = Color.Transparent,
    ink: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(ground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The immersive background: a blurred copy filling the screen, the sharp one contained on it.
 *
 * Filling a tall screen with one image crops it to a band, worst of all for a 9:16 video
 * frame. Two layers means nothing is stretched and nothing is lost - the blur is wallpaper and
 * the sharp copy is the picture.
 */
@Composable
fun ImmersiveBackdrop(
    art: android.graphics.Bitmap?,
    isVideo: Boolean,
    playing: Boolean,
    source: android.net.Uri?,
    modifier: Modifier = Modifier,
) {
    val picture = art ?: return
    val bitmap = picture.asImageBitmap()
    Box(modifier.fillMaxSize()) {
        // The wallpaper: a blurred STILL, even for a video.
        //
        // A `VideoView` is a SurfaceView, and a surface is composited by the display rather
        // than drawn into this layer - so `Modifier.blur` has nothing to blur and a second
        // decoder would be running a clip nobody can see through 44dp of blur anyway. The
        // frame is the right colour, which is all this layer is for.
        Image(
            bitmap, null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                .androidBlur(44.dp)
                .alpha(0.55f),
        )
        // The picture itself, contained so nothing is cropped - and MOVING when it is a
        // video. The other three faces loop the clip in their artwork square; this one drew
        // a frozen frame, which on the face whose whole point is the picture is the one place
        // it is most obviously wrong.
        Box(
            Modifier.fillMaxSize().padding(top = 56.dp, bottom = 128.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                dev.niccc2007.filet.playback.VideoPeekView(
                    source = source,
                    playing = playing,
                    modifier = Modifier.fillMaxSize(),
                    fit = true,
                    placeholder = {
                        Image(
                            bitmap, "Cover art",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                Image(
                    bitmap, "Cover art",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * `Modifier.blur`, which is a no-op below API 31 rather than a crash.
 *
 * Filet's minimum is below that, and a backdrop that throws on an older phone would take the
 * whole player down. The unblurred fill is still a reasonable background - it is cropped and
 * dimmed - so the degradation is visual and not functional.
 */
private fun Modifier.androidBlur(radius: Dp): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        this.then(Modifier.blur(radius))
    } else {
        this
    }

/**
 * The gradient the immersive face's chrome sits on.
 *
 * Top and bottom only: a veil across the whole frame would dim the picture, which is the one
 * thing this face is for. The two bands exist so white text has ground under it wherever the
 * picture happens to be pale.
 */
@Composable
fun ImmersiveVeil(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().drawWithContent {
            drawContent()
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.55f),
                    0.22f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1.00f to Color.Black.copy(alpha = 0.72f),
                ),
            )
        },
    )
}

/** How many whole minutes the sleep timer has left, or 0. Pure, so the chip can be tested. */
fun sleepMinutesLeft(deadline: Long, now: Long): Int =
    if (deadline <= 0L || deadline <= now) 0
    else (((deadline - now) + 59_999L) / 60_000L).toInt()

/** The row heights the faces share, so a change lands in all four. */
object PlayerMetrics {
    /** `.artwrap{padding:14px 28px 0}` and `.meta{padding:24px 28px 0}`. */
    val GUTTER = 28.dp
    val CHIPS_TOP = 16.dp
    val TRANSPORT_TOP = 18.dp
    val SCRUB_TOP = 22.dp
}

/**
 * Name the queue you are keeping.
 *
 * Its own small dialogue rather than the playlist picker, because the question is different:
 * the picker asks which existing list a track joins, and this one makes a list out of what is
 * already arranged. Offering the picker here would mean choosing "New playlist" every time,
 * which is a step that only ever has one answer.
 */
@Composable
fun SaveQueueDialog(
    suggested: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(suggested) }
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(
            icon = FiletIcons.Rows,
            title = "Save the queue",
            sub = "Everything playing, in this order",
            onClose = onDismiss,
        )
        DlgBody {
            DlgField(
                label = "Name",
                value = text,
                onValue = { text = it },
            )
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgSpacer()
            DlgBtn(
                "Save",
                kind = BtnKind.PRIMARY,
            ) { onSave(text) }
        }
    }
}
