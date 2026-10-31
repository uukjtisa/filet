// Ported from mikepenz/multiplatform-markdown-renderer - MarkdownHighlightedCode.kt
// (https://github.com/mikepenz/multiplatform-markdown-renderer).
// Copyright Mike Penz and contributors.
// SPDX-License-Identifier: Apache-2.0
//
// Changed from the original: the highlighting itself is kept as it was - Highlights (dev.snipme,
// Apache-2.0) run off the main thread, each highlight a colour or a weight over its range, the
// plain text shown until it is ready - with a fresh builder per block, because the original shares
// one mutable builder between blocks highlighted in parallel. The block around it is this
// reader's: a language tag, Wrap, Copy and Hide chips on a pad of their own, and a height cap with
// its own scroll.
package dev.niccc2007.filet.docs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a fence's info string names, as Highlights' languages. */
internal object CodeLanguage {

    private val ALIASES = mapOf(
        "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN, "kotlin" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA,
        "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C,
        "cpp" to SyntaxLanguage.CPP, "c++" to SyntaxLanguage.CPP, "cc" to SyntaxLanguage.CPP, "cxx" to SyntaxLanguage.CPP,
        "hpp" to SyntaxLanguage.CPP, "ino" to SyntaxLanguage.CPP, "arduino" to SyntaxLanguage.CPP,
        "cs" to SyntaxLanguage.CSHARP, "c#" to SyntaxLanguage.CSHARP, "csharp" to SyntaxLanguage.CSHARP,
        "dart" to SyntaxLanguage.DART, "rust" to SyntaxLanguage.RUST, "rs" to SyntaxLanguage.RUST,
        "coffee" to SyntaxLanguage.COFFEESCRIPT, "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
        "js" to SyntaxLanguage.JAVASCRIPT, "javascript" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
        "mjs" to SyntaxLanguage.JAVASCRIPT, "cjs" to SyntaxLanguage.JAVASCRIPT, "json" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT, "typescript" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT,
        "perl" to SyntaxLanguage.PERL, "pl" to SyntaxLanguage.PERL,
        "py" to SyntaxLanguage.PYTHON, "python" to SyntaxLanguage.PYTHON, "python3" to SyntaxLanguage.PYTHON,
        "rb" to SyntaxLanguage.RUBY, "ruby" to SyntaxLanguage.RUBY,
        "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL,
        "shell" to SyntaxLanguage.SHELL, "console" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL,
        "swift" to SyntaxLanguage.SWIFT, "go" to SyntaxLanguage.GO, "golang" to SyntaxLanguage.GO,
        "php" to SyntaxLanguage.PHP,
    )

    /** The language a fence's info string names: its first word, `{.kotlin}` and all. Null for none. */
    fun of(info: String): SyntaxLanguage? = ALIASES[tag(info).lowercase()]

    /** The word shown on the block - the info string's first word, without decoration. */
    fun tag(info: String): String = info.trim().substringBefore(' ').trim('{', '}', '.').trim()

    fun highlight(code: String, language: SyntaxLanguage?, dark: Boolean): AnnotatedString {
        if (language == null || code.isEmpty()) return AnnotatedString(code)
        val found = runCatching {
            Highlights.Builder()
                .code(code)
                .language(language)
                .theme(SyntaxThemes.default(darkMode = dark))
                .build()
                .getHighlights()
        }.getOrNull() ?: return AnnotatedString(code)
        return buildAnnotatedString {
            append(code)
            for (h in found) {
                val start = h.location.start.coerceIn(0, code.length)
                val end = h.location.end.coerceIn(0, code.length)
                if (end <= start) continue
                when (h) {
                    is ColorHighlight -> addStyle(SpanStyle(color = Color(h.rgb).copy(alpha = 1f)), start, end)
                    is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
    }
}

/** How tall a code block may be before it scrolls inside itself. */
private val CODE_MAX = 460.dp

/** The pad at the foot of a code block that its chips sit on, so they never cover a line. */
private val CHIP_PAD = 34.dp

/**
 * A fenced or indented code block.
 *
 * Scrolled sideways by default, not wrapped - a wrapped line of code is harder to read than a
 * scrolled one - with Wrap one tap away for the block where it is the other way round. The chips
 * live on their own pad at the foot rather than over the code, so the last line is never under a
 * button.
 *
 * Bug identified here: the pad's chips sat in a filled pill, and a filled shape at the foot of a
 * block reads as something laid over the code, hiding a line - whether or not one is there. The
 * pad is transparent now and each chip is an outline, which says "button" without saying "cover".
 *
 * The block can be sized by hand from its Resize chip - see [BlockResize].
 */
@Composable
internal fun CodeView(block: Block.Code, dark: Boolean) {
    val colors = Filet.colors
    val context = LocalContext.current
    var wrap by remember(block) { mutableStateOf(false) }
    var hidden by remember(block) { mutableStateOf(false) }
    var copied by remember(block) { mutableStateOf(false) }
    val language = remember(block) { CodeLanguage.of(block.language) }
    val highlighted by produceState(AnnotatedString(block.text), block, dark) {
        value = withContext(Dispatchers.Default) { CodeLanguage.highlight(block.text, language, dark) }
    }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    val size = remember(block) { BlockSize() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    size.overflow = { scroll.maxValue / density }
    val style = @Composable { mod: Modifier ->
        Text(
            highlighted,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = wrap,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = mod,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val room = maxWidth.value
    Box(
        Modifier
            .then(size.width?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth())
            .onSizeChanged { size.shownW = it.width / density }
            .clip(RoundedCornerShape(8.dp))
            .background(colors.sunken),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (!hidden) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = size.height?.dp ?: CODE_MAX)
                        .onSizeChanged { size.shownH = it.height / density }
                        .verticalScroll(scroll),
                ) {
                    if (wrap) {
                        style(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp))
                    } else {
                        // Through `HScroll` rather than a bare `horizontalScroll`, so the edge says
                        // there is more - on a block with no visible right-hand edge, that is the
                        // difference between a long line and a truncated one.
                        dev.niccc2007.filet.ui.HScroll(ground = colors.sunken, contentPadding = 10.dp) {
                            style(Modifier)
                        }
                    }
                }
            }
            Spacer(Modifier.height(CHIP_PAD))
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(CHIP_PAD)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                CodeLanguage.tag(block.language).uppercase().ifEmpty { "TEXT" },
                fontSize = 9.5.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold,
                color = colors.fg3,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hidden) {
                    Chip(if (wrap) "↵ Wrap ✓" else "↵ Wrap", on = wrap) { wrap = !wrap }
                    Chip(if (copied) "Copied ✓" else "Copy", tint = if (copied) colors.good else null) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("code", block.text))
                        copied = true
                    }
                }
                Chip(if (hidden) "Show" else "Hide") { hidden = !hidden }
                if (!hidden) ResizeChip(size, room)
            }
        }
    }
    }
}

/**
 * One chip on a block's pad: a code block's or a table's. An outline on nothing - faint when off,
 * the accent when on - so the pad never reads as a cover over the block. See [CodeView].
 */
@Composable
internal fun Chip(label: String, on: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(50)
    val edge = when {
        tint != null -> tint.copy(alpha = 0.5f)
        on -> colors.accent.copy(alpha = 0.55f)
        else -> colors.fg3.copy(alpha = 0.28f)
    }
    Text(
        label,
        fontSize = 10.5.sp,
        fontWeight = if (on || tint != null) FontWeight.SemiBold else FontWeight.Medium,
        color = tint ?: if (on) colors.accent else colors.fg2,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
    Spacer(Modifier.width(4.dp))
}
