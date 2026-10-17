package dev.niccc2007.filet.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * One screen for every document format, because they are all read the same way.
 *
 * ## What this is for
 *
 * A reader turns its format into [Doc] and this draws it. So Markdown, DOCX and PPTX share
 * their scrolling, their typography, their tables, their empty state and their theming, and a
 * fix to any of those is one fix. The alternative - a screen per format - is four
 * implementations of the same reading experience that drift apart one change at a time.
 *
 * ## It reflows, deliberately
 *
 * No page geometry, no columns, no original fonts. A phone is 400 dp wide and an A4 page is
 * not, so a document reproduced faithfully is a document read at 40% zoom with horizontal
 * scrolling - which is what every "document viewer" that tries this feels like. Reflowed text
 * in the app's own type is readable, and being honestly a reader beats being a bad renderer.
 *
 * The one thing that does NOT reflow is code, which is wrapped in a horizontal scroller
 * instead: a wrapped line of code is worse than a scrolled one.
 */
@Composable
fun DocReader(doc: Doc, modifier: Modifier = Modifier) {
    val colors = Filet.colors
    if (doc.empty && doc.note == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to read in this one", fontSize = 12.sp, color = colors.fg3)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        doc.note?.let { note ->
            item {
                // At the TOP, and not as a toast. A document that is partly unread is a fact
                // about what is on screen for as long as it is on screen, and a message that
                // disappears after two seconds is not where that belongs.
                Text(
                    note,
                    fontSize = 11.sp,
                    color = colors.warn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.warn.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        items(doc.blocks.size) { i -> BlockView(doc.blocks[i]) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun BlockView(block: Block) {
    val colors = Filet.colors
    when (block) {
        is Block.Para -> {
            val size = headingSize(block.level)
            Spacer(Modifier.height(if (block.level > 0) 16.dp else 8.dp))
            Row {
                if (block.quote) {
                    // A rule down the side, which is what a quote looks like everywhere. An
                    // indent alone reads as a list item.
                    Box(
                        Modifier
                            .width(2.dp)
                            .height((size.value * 1.5f).dp)
                            .background(colors.accent.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    annotate(block.text, block.spans),
                    fontSize = size,
                    lineHeight = size * 1.5f,
                    fontWeight = if (block.level > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (block.quote) colors.fg2 else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        is Block.Item -> {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(start = (block.depth * 16).dp)) {
                Text(
                    if (block.ordered && block.number > 0) "${block.number}." else "•",
                    fontSize = 13.sp,
                    color = colors.fg3,
                    modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp),
                )
                Text(
                    annotate(block.text, block.spans),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        is Block.Code -> {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.sunken),
            ) {
                // Scrolled, not wrapped. A wrapped line of code is harder to read than a
                // scrolled one, and this is the one place reflowing is the wrong answer.
                //
                // Through `HScroll` rather than a bare `horizontalScroll`, so the edge says
                // there is more - which on a block of code with no visible right-hand edge is
                // the difference between a long line and a truncated one.
                dev.niccc2007.filet.ui.HScroll(
                    ground = colors.sunken,
                    contentPadding = 10.dp,
                ) {
                    Text(
                        block.text,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is Block.Table -> {
            Spacer(Modifier.height(12.dp))
            // The whole table scrolls as one, so the columns stay lined up. Scrolling each
            // row separately is what a Column of scrollable Rows does and it looks broken
            // the moment you touch it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.sunken),
            ) {
                // One scroller for the WHOLE table, so the columns stay lined up. A
                // scroller per row is what a Column of scrollable Rows gives and it comes
                // apart the moment it is touched.
                dev.niccc2007.filet.ui.HScroll(ground = colors.sunken, contentPadding = 2.dp) {
                    Column {
                    for ((r, row) in block.rows.withIndex()) {
                        val head = block.header && r == 0
                        Row {
                            for (cellText in row) {
                                Text(
                                    cellText,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (head) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Filet.colors.fg2
                                    },
                                    modifier = Modifier
                                        .width(TABLE_CELL)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                        if (head) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.lineSoft),
                            )
                        }
                        }
                    }
                }
            }
        }

        Block.Rule -> {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        }

        is Block.Slide -> {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SLIDE ${block.number}",
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.accent.copy(alpha = 0.3f)))
            }
        }

        is Block.Note -> {
            Spacer(Modifier.height(8.dp))
            Text(block.text, fontSize = 10.5.sp, color = colors.fg3, fontStyle = FontStyle.Italic)
        }
    }
}

/** How wide a table cell is. Fixed, because a measured column pass is a layout engine. */
private val TABLE_CELL = 124.dp

private fun headingSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 17.sp
    3 -> 15.sp
    4, 5, 6 -> 13.5.sp
    else -> 13.sp
}

/**
 * Text plus marks into an `AnnotatedString`.
 *
 * Every span is range-checked against the text, and an out-of-range one is dropped. Not
 * defensiveness for its own sake: these offsets come out of three different readers walking
 * three different trees, and `AnnotatedString` THROWS on a range past the end rather than
 * clamping - so one arithmetic slip in a reader would be a crash on opening a document rather
 * than a word in the wrong weight.
 */
fun annotate(text: String, spans: List<Span>): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            if (span.from < 0 || span.to > text.length || span.to <= span.from) continue
            addStyle(styleOf(span.style), span.from, span.to)
        }
    }
}

private fun styleOf(mark: Mark): SpanStyle = when (mark) {
    Mark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    Mark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    Mark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))
    Mark.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    // Underlined and in the accent, but NOT clickable. A tap that opens a browser from
    // inside a document reader is an outbound network request somebody did not ask for, and
    // the link's target is visible as text anyway.
    Mark.LINK -> SpanStyle(
        textDecoration = TextDecoration.Underline,
        color = Color(0xFF6AA9FF),
    )
}
