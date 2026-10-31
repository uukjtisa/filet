package dev.niccc2007.filet.docs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.docs.CodeChips.codeChips
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath

/**
 * Where a document is being read from, for the parts of it that point elsewhere.
 *
 * @param file the document's own address, which relative pictures and links are read against.
 *   Null for a document with no address of its own, whose local links then go nowhere.
 * @param open a link to another file, opened in Filet.
 * @param say a short message, for a link that leads nowhere.
 * @param stamp when the file was last changed, which tells a saved reading spot whether the
 *   document it was saved in is still the one on screen. 0 when not known.
 */
class DocPlace(
    val file: VPath?,
    val open: (VPath) -> Unit,
    val say: (String) -> Unit,
    val stamp: Long = 0L,
)

/** Everything a block needs from the reader around it. */
internal class DocEnv(
    val place: DocPlace?,
    val reading: ReadingSettings,
    val dark: Boolean,
    val onLink: (String) -> Unit,
    /** The page's own density, before the zoom - what tables measure at. See [TableView]. */
    val base: Density,
) {
    /** Pixels measured at [base] as dp, which the zoom then scales with everything else. */
    fun dp(px: Int): Float = px / base.density
}

internal val LocalDocEnv = compositionLocalOf<DocEnv?> { null }

private val LINK = Color(0xFF6AA9FF)

/** How a run of document text is styled, apart from its marks. */
internal fun textAlignOf(align: Align): TextAlign = when (align) {
    Align.START -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.END -> TextAlign.End
    Align.JUSTIFY -> TextAlign.Justify
}

/**
 * A run of document text: its marks, its links, its code chips and its pictures.
 *
 * ## Pictures inside the text
 *
 * Each picture is an inline placeholder the size of the picture, so a row of badges flows and
 * wraps like words. Before a picture has loaded its placeholder is small - or the size the HTML
 * asked for - and when it arrives the placeholder grows to it over a short animation, so the
 * text around it settles instead of jumping. A picture is never wider than the text's own width.
 */
@Composable
internal fun RichText(
    text: String,
    spans: List<Span>,
    pictures: List<Picture>,
    size: TextUnit,
    line: TextUnit,
    weight: FontWeight,
    color: Color,
    align: Align,
    ground: Color,
    modifier: Modifier = Modifier,
    italic: Boolean = false,
) {
    val env = LocalDocEnv.current
    val colors = Filet.colors
    val codeGround = colors.sunken
    val built = remember(text, spans, pictures, ground, env?.onLink) { annotateRich(text, spans, pictures, ground, env?.onLink) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val chips = Modifier.codeChips(
        layout = { layout },
        ranges = built.code,
        color = codeGround,
        radius = with(density) { 4.dp.toPx() },
        padX = with(density) { 2.5.dp.toPx() },
        inset = with(density) { 1.dp.toPx() },
    )
    if (pictures.isEmpty() || env == null) {
        Text(
            built.text,
            fontSize = size,
            lineHeight = line,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else null,
            color = color,
            textAlign = textAlignOf(align),
            onTextLayout = { layout = it },
            modifier = modifier.then(chips),
        )
        return
    }
    val slots = remember(pictures) { pictures.map { PictureSlot() } }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val room = maxWidth
        val font = with(density) { size.toDp() }
        val inline = HashMap<String, InlineTextContent>()
        for ((k, p) in pictures.withIndex()) {
            val slot = slots[k]
            val target = placeholder(p, slot, room, font, env) { label ->
                with(density) { measurer.measure(label, androidx.compose.ui.text.TextStyle(fontSize = 10.5.sp)).size.width.toDp() }
            }
            key(k) {
                val w by animateDpAsState(target.width, tween(220), label = "pictureWidth")
                val h by animateDpAsState(target.height, tween(220), label = "pictureHeight")
                val tall = h > font * 2
                inline["p$k"] = InlineTextContent(
                    Placeholder(
                        with(density) { w.coerceAtLeast(1.dp).toSp() },
                        with(density) { h.coerceAtLeast(1.dp).toSp() },
                        if (tall) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextCenter,
                    ),
                ) { InlinePicture(p, slot, env) }
            }
        }
        Text(
            built.text,
            fontSize = size,
            lineHeight = line,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else null,
            color = color,
            textAlign = textAlignOf(align),
            inlineContent = inline,
            onTextLayout = { layout = it },
            // Bug identified here: the text sat in the box at its own width, so a line holding only
            // a picture was exactly as wide as the picture and a centred one had nothing to be
            // centred in - the logo of a README drew at the left edge. The run without pictures
            // above takes the caller's width; this one has to fill the box to do the same.
            modifier = (if (align == Align.START) Modifier else Modifier.fillMaxWidth()).then(chips),
        )
    }
}

/** The size a picture's placeholder is drawn at right now. */
private fun placeholder(p: Picture, slot: PictureSlot, room: Dp, font: Dp, env: DocEnv, measure: (String) -> Dp): DpSize {
    val declared = when {
        p.widthPercent > 0 -> room * (p.widthPercent / 100f)
        p.width > 0 -> p.width.dp
        else -> null
    }
    val loaded = slot.size
    if (loaded != null && loaded.width.value > 0f && loaded.height.value > 0f) {
        val aspect = loaded.height / loaded.width
        val w = minOf(declared ?: loaded.width, room)
        val h = if (p.height > 0 && declared == null) p.height.dp else w * aspect
        return DpSize(w, h)
    }
    val ref = DocRefs.picture(p.src, env.place?.file)
    val waiting = ref is Ref.Web && !env.reading.webPictures && !slot.allowed
    if (slot.failed || waiting) {
        val label = (if (waiting) "⤓ " else "▨ ") + DocRefs.nameOf(p)
        return DpSize(minOf(measure(label) + 14.dp, room), maxOf(font * 1.5f, 18.dp))
    }
    if (declared != null) {
        val w = minOf(declared, room)
        val h = if (p.height > 0) p.height.dp * (w / declared) else w * 0.5f
        return DpSize(w, h)
    }
    return DpSize(font * 1.2f, font * 1.2f)
}

/** A built run: the string, and the ranges its code chips go behind. */
internal class Built(val text: AnnotatedString, val code: List<IntRange>)

/**
 * Text plus marks into an `AnnotatedString`.
 *
 * Every span is range-checked against the text, and an out-of-range one is dropped. Not
 * defensiveness for its own sake: these offsets come out of several readers walking different
 * trees, and `AnnotatedString` THROWS on a range past the end rather than clamping - so one
 * arithmetic slip in a reader would be a crash on opening a document rather than a word in the
 * wrong weight.
 */
fun annotate(text: String, spans: List<Span>): AnnotatedString = annotate(text, spans, null)

/**
 * The same, knowing what it is drawn on, so a document colour that would vanish on the theme is
 * dropped. See [DocLayout.readable].
 */
fun annotate(text: String, spans: List<Span>, ground: Color?): AnnotatedString =
    annotateRich(text, spans, emptyList(), ground, null).text

/**
 * The full build: links that answer [onLink], code runs left for [CodeChips] to draw, and each
 * picture's U+FFFC turned into the inline-content slot `p<index>`.
 */
internal fun annotateRich(text: String, spans: List<Span>, pictures: List<Picture>, ground: Color?, onLink: ((String) -> Unit)?): Built {
    if (spans.isEmpty() && pictures.isEmpty()) return Built(AnnotatedString(text), emptyList())
    val groundArgb = ground?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
    val code = ArrayList<IntRange>()
    val placed = pictures.withIndex().filter { (_, p) -> p.at in text.indices && text[p.at] == '￼' }.sortedBy { it.value.at }
    val string = buildAnnotatedString {
        var from = 0
        for ((k, p) in placed) {
            if (p.at < from) continue
            append(text, from, p.at)
            // The alternate text is the same single character, so every offset after it holds.
            appendInlineContent("p$k", "￼")
            from = p.at + 1
        }
        append(text, from, text.length)
        for (span in spans) {
            if (span.from < 0 || span.to > text.length || span.to <= span.from) continue
            if (span.style == Mark.LINK && onLink != null && span.href.isNotEmpty()) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = span.href,
                        styles = TextLinkStyles(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onLink(span.href) },
                    ),
                    span.from,
                    span.to,
                )
                continue
            }
            if (span.style == Mark.CODE && onLink != null) {
                // Drawn as a chip behind the text rather than as a span background.
                code += span.from until span.to
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.92.em), span.from, span.to)
                continue
            }
            val style = styleOf(span, spans, groundArgb) ?: continue
            addStyle(style, span.from, span.to)
        }
    }
    return Built(string, code)
}

private fun styleOf(span: Span, all: List<Span>, ground: Long?): SpanStyle? = when (span.style) {
    Mark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    Mark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    Mark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))
    // Underline and strike are both a text decoration, and two decoration spans over one range
    // REPLACE each other rather than adding up. Where both cover a run, each draws both.
    Mark.STRIKE, Mark.UNDERLINE -> SpanStyle(textDecoration = decorationFor(span, all))
    // Underlined and in the link colour. Clickable only where the screen says what a tap does -
    // see [annotateRich] - and a web address there asks before anything opens.
    Mark.LINK -> SpanStyle(
        textDecoration = TextDecoration.Underline,
        color = LINK,
    )
    Mark.COLOR -> if (span.argb == 0L || (ground != null && !DocLayout.readable(span.argb, ground))) {
        null
    } else {
        SpanStyle(color = Color(span.argb.toInt()))
    }
    // A highlighter's ink is chosen for the highlighter, not for the page: black on yellow
    // reads, the theme's pale text on yellow does not.
    Mark.HIGHLIGHT -> if (span.argb == 0L) {
        null
    } else {
        SpanStyle(
            background = Color(span.argb.toInt()).copy(alpha = 0.85f),
            color = Color(DocLayout.inkOn(span.argb).toInt()),
        )
    }
    Mark.SIZE -> if (span.scale <= 0f) null else SpanStyle(fontSize = span.scale.em)
    Mark.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
    Mark.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
}

private fun decorationFor(span: Span, all: List<Span>): TextDecoration {
    val other = if (span.style == Mark.UNDERLINE) Mark.STRIKE else Mark.UNDERLINE
    val own = if (span.style == Mark.UNDERLINE) TextDecoration.Underline else TextDecoration.LineThrough
    val overlaps = all.any { it.style == other && it.from < span.to && it.to > span.from }
    return if (overlaps) {
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    } else {
        own
    }
}
