package dev.niccc2007.filet.jobs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.delay

/**
 * What the started cards decide, kept apart from how they look so it can be tested.
 *
 * Bug identified here: starting a copy, a delete or an extraction said nothing until it ended.
 * The message at the bottom arrives when the work is done, so a long job looked like a press
 * that had not registered, and the usual answer to that is pressing again. A card naming the job
 * the moment it starts is the acknowledgement.
 */
object StartedCards {

    /** Cards on screen at once. A fourth pushes the oldest out early. */
    const val MAX = 3

    /** How long a card stays. The hairline along its bottom edge counts it down. */
    const val LIFE_MS = 3_000L

    /**
     * The jobs that started after [seen], oldest first.
     *
     * By id, not by state: ids only grow, so "after the newest one already seen" is exactly
     * "started since", and a job that finished before the screen noticed it - a delete of one
     * file takes milliseconds - still gets its card. Every kind counts, because every kind is a
     * row in Activity and the card is the promise that the row exists.
     */
    fun newSince(seen: Long, jobs: List<Job>): List<Job> = jobs.filter { it.id > seen }.sortedBy { it.id }

    /** The newest id in [jobs], or 0: where counting starts when the screen comes up. */
    fun newest(jobs: List<Job>): Long = jobs.maxOfOrNull { it.id } ?: 0L

    /**
     * The card's two lines: what is happening, and where. The second is null when the job named
     * no place, which makes a card of one line rather than one with an empty second.
     *
     * The first is the job's title as Activity shows it, so the card and the row it leads to read
     * the same - with "item(s)" made into the number's own word, which Activity's title leaves to
     * the reader.
     */
    fun words(job: Job): Pair<String, String?> {
        val title = job.title
            .replace(Regex("\\b1 item\\(s\\)"), "1 item")
            .replace("item(s)", "items")
        val where = when (job.kind) {
            JobKind.COPY, JobKind.MOVE, JobKind.EXTRACT -> folderOf(job.to)?.let { "to $it" }
            JobKind.DELETE -> folderOf(job.from)?.let { "in $it" }
            JobKind.COMPRESS -> job.subtitle.ifBlank { null }?.let { "into $it" }
            JobKind.SAVE, JobKind.INDEX, JobKind.MEASURE, JobKind.BEAT, JobKind.SCRIPT,
            JobKind.APK, JobKind.FETCH, JobKind.DOWNLOAD, JobKind.SERVED, JobKind.RECEIVED, JobKind.OTHER,
            -> job.subtitle.ifBlank { null } ?: job.source.takeIf { it != "Filet" }
        } ?: job.subtitle.ifBlank { null }
        return title to where
    }

    /** The last part of a stored path, `local:///storage/emulated/0/DCIM` -> `DCIM`. */
    private fun folderOf(raw: String): String? =
        raw.trimEnd('/').substringAfterLast('/').ifBlank { null }
}

/** ease-out and ease-in, as CSS spells them: the mock's cardIn and cardOut. */
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

private class Shown(val job: Job) {
    var leaving by mutableStateOf(false)
}

/**
 * The stack of started cards, at most [NoticePrefs.stack] of them, in the corner Settings >
 * Activity chose (top right by default, where they have always been).
 *
 * Counting starts at the newest job when this comes up, so history read back from disk and jobs
 * that ran while the screen was away never raise a burst of cards on arrival. A kind switched off
 * still moves the count on - it is seen, just not shown - so turning it back on later does not
 * replay every job it skipped.
 *
 * At the top the newest card is on top; at the bottom the stack grows upward, so the newest card
 * is the one nearest the edge either way and the eye finds it in the same place.
 *
 * @param onOpen a card was tapped: open Activity at that job.
 */
@Composable
fun StartedCardStack(
    ledger: JobLedger,
    prefs: NoticePrefs,
    modifier: Modifier = Modifier,
    onOpen: (Long) -> Unit,
) {
    val jobs by ledger.jobs.collectAsState()
    var seen by remember { mutableLongStateOf(StartedCards.newest(ledger.jobs.value)) }
    val cards = remember { mutableStateListOf<Shown>() }
    val open by rememberUpdatedState(onOpen)
    val now by rememberUpdatedState(prefs)

    LaunchedEffect(jobs) {
        val fresh = StartedCards.newSince(seen, jobs)
        if (fresh.isEmpty()) return@LaunchedEffect
        seen = fresh.maxOf { it.id }
        for (j in fresh) if (Notices.showsCard(j.kind, now)) cards.add(0, Shown(j))
        cards.filterNot { it.leaving }.drop(now.stack).forEach { it.leaving = true }
    }

    val bottom = prefs.place == CardPlace.BOTTOM_END || prefs.place == CardPlace.BOTTOM_START
    val start = prefs.place == CardPlace.TOP_START || prefs.place == CardPlace.BOTTOM_START
    Column(
        modifier.padding(
            top = if (bottom) 0.dp else 10.dp,
            bottom = if (bottom) 10.dp else 0.dp,
            start = if (start) 10.dp else 0.dp,
            end = if (start) 0.dp else 10.dp,
        ),
        horizontalAlignment = if (start) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in if (bottom) cards.asReversed() else cards) {
            key(c.job.id) {
                StartedCard(
                    c,
                    lifeMs = prefs.lifeMs,
                    fromStart = start,
                    onGone = { cards.remove(c) },
                    onOpen = { c.leaving = true; open(c.job.id) },
                )
            }
        }
    }
}

/**
 * One card. Slides in from its side in 180 ms, leaves the same way in 160 ms, after its
 * life (Settings > Activity > Stays for), on its ×, on a tap, or when one too many pushes it out.
 *
 * The life is a timer, not the hairline's animation: with animations turned off in the system the
 * line jumps, and the card must still leave at three seconds.
 */
@Composable
private fun StartedCard(c: Shown, lifeMs: Long, fromStart: Boolean, onGone: () -> Unit, onOpen: () -> Unit) {
    val colors = Filet.colors
    val accentDim = MaterialTheme.colorScheme.primaryContainer
    val shown = remember { Animatable(0f) }
    val life = remember { Animatable(1f) }
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(180, easing = EaseOut)) }
    LaunchedEffect(Unit) { life.animateTo(0f, tween(lifeMs.toInt(), easing = LinearEasing)) }
    LaunchedEffect(Unit) {
        delay(lifeMs)
        c.leaving = true
    }
    LaunchedEffect(c.leaving) {
        if (!c.leaving) return@LaunchedEffect
        shown.animateTo(0f, tween(160, easing = EaseIn))
        gone()
    }
    val (what, where) = remember(c.job.id) { StartedCards.words(c.job) }
    val shape = RoundedCornerShape(10.dp)
    val line = colors.accent.copy(alpha = 0.55f)

    Row(
        Modifier
            .graphicsLayer {
                translationX = (1f - shown.value) * size.width * 1.1f * if (fromStart) -1f else 1f
                alpha = shown.value
            }
            .widthIn(max = 280.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(colors.high)
            .border(1.dp, colors.lineSoft, shape)
            .drawWithContent {
                drawContent()
                val h = 2.dp.toPx()
                drawRect(line, topLeft = Offset(0f, size.height - h), size = Size(size.width * life.value, h))
            }
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(accentDim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(jobIcon(c.job), null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.widthIn(max = 190.dp)) {
            Text(what, fontSize = 12.sp, color = colors.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (where != null) {
                Text(where, fontSize = 11.5.sp, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable { c.leaving = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(FiletIcons.Close, "Close", tint = colors.fg3, modifier = Modifier.size(14.dp))
        }
    }
}
