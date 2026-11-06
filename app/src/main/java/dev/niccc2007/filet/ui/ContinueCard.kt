package dev.niccc2007.filet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.delay

/**
 * The card that offers a place to carry on from: top right, over what is open, two lines and a
 * close. One card for every viewer that has one - the documents' reading spot, and the video and
 * music players' place - so they look and behave as one thing.
 *
 * Small and to one side so it covers a corner of the first screen, not the first line of it. It
 * slides in from the right after what it sits over is up.
 *
 * @param key what is being offered; null shows nothing. A new key shows the card again.
 * @param title the first line, what the card is about.
 * @param detail the second line, in the accent: where, or what a tap does.
 * @param closeLabel what the close button does, for a screen reader.
 * @param holdMs how long it stays before it slides away by itself; null to stay until tapped or
 *   closed, for a screen where nothing is playing under it.
 * @param onGo the card was tapped.
 */
@Composable
fun ContinueCard(
    key: Any?,
    title: String,
    detail: String,
    closeLabel: String,
    modifier: Modifier = Modifier,
    holdMs: Long? = CONTINUE_CARD_MS,
    onGo: () -> Unit,
) {
    val colors = Filet.colors
    var shown by remember(key) { mutableStateOf(false) }
    val go by rememberUpdatedState(onGo)
    LaunchedEffect(key, holdMs) {
        if (key == null) return@LaunchedEffect
        shown = true
        if (holdMs != null) {
            delay(holdMs)
            shown = false
        }
    }
    AnimatedVisibility(
        visible = shown && key != null,
        modifier = modifier,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier
                .padding(top = 10.dp, end = 10.dp)
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(colors.high)
                .border(1.dp, colors.lineSoft, shape)
                .clickable {
                    shown = false
                    go()
                }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, fontSize = 12.sp, color = colors.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    fontSize = 11.5.sp,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { shown = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(FiletIcons.Close, closeLabel, tint = colors.fg3, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** How long a [ContinueCard] over something open stays before it slides away by itself. */
const val CONTINUE_CARD_MS = 10_000L
