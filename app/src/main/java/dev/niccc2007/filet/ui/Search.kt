package dev.niccc2007.filet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What the pane's search box holds, for a screen that narrows its own rows - every pane kind
 * that `searchPlan` calls FILTER. Empty when the box is shut, so a screen only ever has to ask
 * whether there is anything to narrow by.
 *
 * Provided once, where the pane draws its special screens, so a screen that is given only the
 * view model still hears the box of the pane it is in, not whichever pane has focus.
 */
val LocalPaneQuery = compositionLocalOf { "" }

/**
 * Matching a query against a row. Pure, so what a box of letters finds is a test.
 *
 * Every word of the query has to be in the row, each in any of its fields, ignoring case: "beat
 * glow" finds the row with both, and the order the words are typed in does not matter.
 */
object TextMatch {

    fun terms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** True for a blank query, so an empty box narrows nothing. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val words = terms(query)
        if (words.isEmpty()) return true
        return words.all { w -> fields.any { it != null && it.contains(w, ignoreCase = true) } }
    }

    /** Where the query's words fall in [text], merged where they touch, in order. */
    fun spans(text: String, query: String): List<IntRange> {
        val hits = ArrayList<IntRange>()
        for (w in terms(query)) {
            var from = 0
            while (true) {
                val at = text.indexOf(w, from, ignoreCase = true)
                if (at < 0) break
                hits += at until at + w.length
                from = at + w.length
            }
        }
        if (hits.isEmpty()) return hits
        hits.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var cur = hits[0]
        for (r in hits.drop(1)) {
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { merged += cur; r }
        }
        merged += cur
        return merged
    }
}

/**
 * [text] with the query's letters marked by a wash of [ink] behind them, so why a row is here is
 * visible.
 *
 * Bug identified here: the match used to be marked by drawing its letters in [ink] and nothing
 * else. On a dark theme the accent is far lighter than the dark ground and stands apart from the
 * pale body text; on a light theme every palette's accent is a dark colour close to the dark body
 * text - the Ink palette's is #2E2E2C against #161615 - so the matched letters looked like the
 * rest of the name and the highlight seemed to be missing.
 *
 * A wash marks the match by its ground rather than its letters, which works whatever the text
 * colour is: the letters keep the row's own colour, the strongest contrast it has, and the wash
 * is [ink], at the strength [matchWash] gives it, over whatever is beneath, so a tinted card
 * washes in its own tint.
 * `SearchHighlightTest` holds every palette, light and dark, to a readable result.
 */
fun highlighted(text: String, query: String, ink: Color): AnnotatedString {
    val spans = TextMatch.spans(text, query)
    if (spans.isEmpty()) return AnnotatedString(text)
    val style = matchStyle(ink)
    return buildAnnotatedString {
        append(text)
        for (r in spans) addStyle(style, r.first, r.last + 1)
    }
}

/**
 * How a matched run is drawn, everywhere a search marks one. The one declaration: the file list's
 * rows (`matchedName`) and every other list ([highlighted]) both read it, so a change to how a
 * match looks cannot reach some lists and not others again.
 */
fun matchStyle(ink: Color): SpanStyle = SpanStyle(background = matchWash(ink), fontWeight = FontWeight.SemiBold)

/**
 * How strongly a match's wash carries its ink, by how light the ink is. Checked against every
 * palette: see [highlighted].
 *
 * Decision: two strengths, not one. A single value has to suit both themes, and the two pull
 * apart. A light ink is a dark theme's accent, under pale letters, and every step stronger takes
 * contrast from them: at 0.32 the Ember dark theme's secondary text fell under 3:1. A dark ink is
 * a light theme's accent, and at 0.28 its band on white was faint enough to pass unnoticed. Each
 * side is given as much as its own letters allow.
 *
 * Split by the ink's luminance, which needs no theme passed in: every dark theme's accent is
 * above 0.35 and every light theme's below 0.2, and a card tint passed as the ink lands on the
 * side its own lightness says.
 */
internal const val MATCH_WASH_LIGHT_INK = 0.28f
internal const val MATCH_WASH_DARK_INK = 0.36f
internal const val LIGHT_INK = 0.27f

/** The colour behind a matched run: [ink], see-through to the strength its lightness allows. */
internal fun matchWash(ink: Color): Color =
    ink.copy(alpha = if (ink.luminance() > LIGHT_INK) MATCH_WASH_LIGHT_INK else MATCH_WASH_DARK_INK)

/**
 * A short accent wash behind a row, for the one a search result just brought into view.
 *
 * Fires each time [trigger] changes to something other than null - a fresh value per arrival,
 * so landing on the same row twice pulses twice. In over a fifth of a second, held, and out by
 * 1.2s, so the eye is drawn without the row looking selected afterwards.
 */
fun Modifier.highlightPulse(trigger: Any?): Modifier = composed {
    val ink = Filet.colors.accent
    val glow = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        glow.snapTo(0f)
        glow.animateTo(
            0f,
            keyframes {
                durationMillis = PULSE_MS
                0.28f at 220
                0.28f at 600
                0f at PULSE_MS
            },
        )
    }
    drawBehind { if (glow.value > 0f) drawRect(ink.copy(alpha = glow.value)) }
}

const val PULSE_MS = 1200

/** What a narrowed list says when the box matched nothing in it, so empty is not mistaken for broken. */
@androidx.compose.runtime.Composable
fun NoMatch(query: String) {
    androidx.compose.material3.Text(
        "Nothing here matches “$query”",
        fontSize = 12.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
