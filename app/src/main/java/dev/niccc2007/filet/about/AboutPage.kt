package dev.niccc2007.filet.about

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.BuildConfig
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.reduceMotion
import dev.niccc2007.filet.ui.theme.Filet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.em
import dev.niccc2007.filet.ui.theme.FrauncesFamily
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.luminance

private data class Credit(val name: String, val what: String, val licence: String)

private val CREDITS = listOf(
    Credit("ARSCLib", "resources.arsc + AXML, no aapt2", "Apache-2.0"),
    Credit("smali / dexlib2", "dex to smali and back", "Apache-2.0"),
    Credit("apksig", "v1-v4 signing", "Apache-2.0"),
    Credit("sora-editor", "code editor, TextMate grammars", "LGPL-2.1"),
    Credit("LuaJ", "script runtime", "BSD"),
)

/**
 * Trawl's AboutPage, restyled for Filet.
 *
 * The Signature Banner keeps Trawl's contract exactly: radius 22, diagonal gradient, 1px
 * glass border, and the mark as a large watermark rotated -12 degrees at 7% opacity bleeding
 * off the bottom-right, so the panel reads as printed rather than assembled. The name carries
 * the glare sweep; the rule under it draws ONCE on arrival, not on every glare loop.
 *
 * The name shown is the handle. Public surfaces carry niccc2007, never the legal name.
 */
@Composable
fun AboutPage(vm: BrowserViewModel) {
    val colors = Filet.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item { SignatureBanner() }

        item { SectionLabel("Project") }
        // FIRST in the section, above Source. It is the one row here that asks for something
        // rather than telling you something, and under three links it has nothing to do with
        // it reads as a footnote. Draws nothing until an address is configured.
        item { SupportCard(onOpen = { url -> vm.openUrl(url) }) }
        item {
            AboutRow(FiletIcons.Code, "Source", "github.com/uukjtisa/filet") {
                vm.openUrl("https://github.com/uukjtisa/filet")
            }
        }
        item {
            AboutRow(FiletIcons.File, "Licence", "GNU General Public License v3.0") {
                vm.openUrl("https://www.gnu.org/licenses/gpl-3.0.html")
            }
        }
        item {
            AboutRow(FiletIcons.Download, "Trawl", "A recommended multi-platform media downloader") {
                vm.openTrawl()
            }
        }

        item { SectionLabel("Built on") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
                    .background(colors.raised)
                    .padding(vertical = 4.dp),
            ) {
                for (c in CREDITS) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(c.what, fontSize = 10.sp, color = colors.fg3)
                        }
                        Text(
                            c.licence,
                            fontSize = 9.sp,
                            color = colors.fg2,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, colors.lineSoft, RoundedCornerShape(20.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        item { SectionLabel("Version") }
        item {
            AboutRow(
                FiletIcons.Cog,
                "v${BuildConfig.VERSION_NAME} · niccc2007",
                if (BuildConfig.UPDATER_ENABLED) "Check for updates" else "Updates handled by your app store",
            ) {
                if (BuildConfig.UPDATER_ENABLED) vm.checkForUpdates()
            }
        }
    }
}

@Composable
private fun SignatureBanner() {
    val colors = Filet.colors
    val reduced = reduceMotion()

    // The flourish draws ONCE on arrival - 1100 ms, 120 ms delay, OutCubic - and never again.
    // Tying an entrance to a forever-loop was a bug in the companion app worth not repeating.
    var reveal by remember { mutableFloatStateOf(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val start = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - start) / 1100f
            reveal = Motion.Out.transform(t.coerceIn(0f, 1f))
            if (t >= 1f) break
            kotlinx.coroutines.delay(16)
        }
    }

    // Glare: 2.6 s streak, 6.4 s rest, 9 s cycle.
    val infinite = rememberInfiniteTransition(label = "glare")
    val ambient by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 9000
                0f at 0 using LinearEasing
                1f at 2600 using LinearEasing
                1f at 9000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "glareSweep",
    )

    // Touching the name overrides the clock and runs one sweep by hand.
    //
    // It runs to completion rather than handing back the moment the finger lifts: taking
    // control back mid-streak cuts the highlight off in the middle of the glyphs, which looks
    // like a dropped frame rather than like a decision.
    var touchAt by remember { mutableLongStateOf(0L) }
    var touchPhase by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(touchAt) {
        if (touchAt == 0L || reduced) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - start) / 1100f
            touchPhase = t.coerceIn(0f, 1f)
            if (t >= 1f) break
            kotlinx.coroutines.delay(16)
        }
    }
    val touching = touchAt != 0L && touchPhase < 1f
    val phase = if (touching) touchPhase else ambient

    /**
     * The name's own width, so the streak is sized to the WORD rather than to the card.
     *
     * The card is full-bleed and the name is about half of it, so a band measured against the
     * card swept a highlight across empty gradient for most of its travel. Measured in
     * `onTextLayout`, which is the only place the laid-out width is known.
     */
    var nameWidth by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    0f to colors.high,
                    0.55f to MaterialTheme.colorScheme.primaryContainer,
                    1f to MaterialTheme.colorScheme.surface,
                )
            )
            .border(1.dp, colors.lineSoft, RoundedCornerShape(22.dp)),
    ) {
        // The watermark bleeds off the bottom-right corner on purpose.
        //
        // With `offset`, not negative padding: Compose throws IllegalArgumentException on a
        // negative padding value, so the whole About page crashed the moment it was drawn.
        // Offset is the modifier that actually means "draw outside my box" anyway - the
        // rounded clip above trims what hangs over the edge.
        Icon(
            FiletIcons.Mark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 28.dp, y = 24.dp)
                .size(158.dp)
                .rotate(-12f),
        )

        /**
         * The struck edge and the light pool, which are what make it a plate rather than a panel.
         *
         * Both are deliberately almost invisible: a hairline of light along the top, a hairline
         * of shade along the bottom, and a soft pool behind the name. Any stronger and the card
         * reads as glassy, which is a different and much more dated register than engraving.
         */
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.055f), Color.Transparent),
                    center = Offset(size.width * 0.22f, size.height * 0.30f),
                    radius = size.width * 0.62f,
                ),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.07f),
                start = Offset(0f, 0.5f),
                end = Offset(size.width, 0.5f),
                strokeWidth = 1f,
            )
            drawLine(
                color = Color.Black.copy(alpha = 0.18f),
                start = Offset(0f, size.height - 0.5f),
                end = Offset(size.width, size.height - 0.5f),
                strokeWidth = 1f,
            )
        }

        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FiletIcons.Mark, null, tint = colors.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "BUILT BY",
                    fontSize = 9.5.sp,
                    letterSpacing = 0.22.em,
                    color = colors.fg2,
                    fontWeight = FontWeight.W700,
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                text = "niccc2007",
                style = TextStyle(
                    fontFamily = FrauncesFamily,
                    fontSize = 37.sp,
                    lineHeight = 37.sp,
                    letterSpacing = 0.005.em,
                    fontWeight = FontWeight.W700,
                    // The streak is the TEXT'S OWN PAINT, not something drawn over it.
                    //
                    // This was a `drawRect` with `BlendMode.SrcAtop` inside `drawWithContent`,
                    // on the reasoning that SrcAtop composites only where the destination is
                    // opaque - so it would land on the glyphs alone. On the device it drew a
                    // bright rectangle across the first third of the word, with a hard
                    // vertical edge.
                    //
                    // The reasoning was wrong about what the destination IS. Inside
                    // `drawWithContent` the canvas is the one the card is already painted
                    // into, not an isolated layer holding only the glyphs, so "where the
                    // destination is opaque" includes the whole gradient panel behind them.
                    // An offscreen compositing strategy would also fix it, at the cost of a
                    // layer per frame.
                    //
                    // Painting the glyphs with the gradient cannot produce a rectangle at all,
                    // because there is no second shape to produce one.
                    brush = nameGlare(
                        base = MaterialTheme.colorScheme.onSurface,
                        highlight = colors.accent,
                        phase = if (reduced) -1f else phase,
                        width = nameWidth,
                        // Read off the surface rather than from `isSystemInDarkTheme`: the
                        // palette can be overridden in settings, so the system's idea of the
                        // theme is not reliably the one on screen.
                        light = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
                    ),
                ),
                onTextLayout = { nameWidth = it.size.width.toFloat() },
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { touchAt = System.currentTimeMillis() },
            )
            SignatureFlourish(
                reveal = reveal,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            )
            Text(
                "A file manager for people who want to do real work on their phone without a PC. " +
                    "Open source, GPL-3.0, no ads, no accounts, no telemetry.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.fg2,
            )
        }
    }
}

/**
 * The name's paint: its own colour, with a narrow brighter band travelling across it.
 *
 * @param phase 0 at the left of the travel, 1 at the right. Negative for no streak at all,
 *   which is what reduced motion asks for - and the answer there is a plain solid colour
 *   rather than a frozen highlight sitting on two letters for ever.
 * @param width the laid-out width of the text. Zero before the first layout pass, and the
 *   honest answer for that one frame is plain text rather than a gradient sized to nothing.
 *
 * The band is narrow deliberately. A wide one reads as the whole word changing brightness
 * rather than as something crossing it.
 */
private fun nameGlare(
    base: Color,
    highlight: Color,
    phase: Float,
    width: Float,
    light: Boolean,
): Brush {
    if (width <= 1f || phase < 0f) return SolidColor(base)
    // A highlight has to move AWAY from the background, not toward white.
    //
    // Toward white is right on the dark theme and wrong on the light one, which the device
    // showed plainly: against near-black text on a pale card, lightening the glyphs makes
    // them fade into the page, so the streak read as a smudge wiping the name out rather than
    // as light crossing it. The direction is the whole of the rule - on a dark ground lift
    // toward white, on a light ground deepen into the accent.
    val lit = if (light) lerp(highlight, Color.Black, 0.25f) else lerp(highlight, Color.White, 0.55f)
    val band = width * 0.42f
    val start = -band + phase * (width + band * 2f)
    return Brush.linearGradient(
        0.00f to base,
        0.34f to base,
        0.46f to lit,
        0.54f to lit,
        0.66f to base,
        1.00f to base,
        start = Offset(start, 0f),
        end = Offset(start + band, 0f),
        tileMode = TileMode.Clamp,
    )
}

/**
 * The rule under the name, as an engraved flourish rather than a 1dp bar.
 *
 * A tapering line with a cap, a lozenge and diminishing dots - the shape a struck line takes on
 * a printed plate, which is the register the rest of this card is already in: the watermark, the
 * display face, the sheen. A plain bar in that company reads as a divider somebody forgot to
 * design.
 *
 * 210dp rather than the full width. Run it across the whole card and the ornament bunches into
 * the first sixth of a long bare line, which reads as a tape measure; an engraved rule has to be
 * short enough that the cluster IS the rule.
 *
 * Drawn rather than composed out of Boxes because the taper is a gradient along the rule's own
 * length, and a border width cannot be animated at all.
 */
@Composable
private fun SignatureFlourish(reveal: Float, modifier: Modifier = Modifier) {
    val ink = Filet.colors.accent
    Canvas(modifier.width(210.dp).height(14.dp)) {
        val w = size.width
        val midY = size.height / 2f
        val cut = w * reveal.coerceIn(0f, 1f)
        if (cut <= 0f) return@Canvas
        clipRect(right = cut) {
            // Full strength at the name's left edge, gone by the right margin, so it reads as
            // an underline that trails off rather than a bar that stops.
            drawLine(
                brush = Brush.horizontalGradient(
                    0.00f to ink,
                    0.34f to ink.copy(alpha = 0.62f),
                    0.72f to ink.copy(alpha = 0.16f),
                    1.00f to Color.Transparent,
                ),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // The left cap. Without it the rule looks cut off by the padding rather than
            // started at the name.
            drawLine(
                color = ink,
                start = Offset(0.8.dp.toPx(), midY - 4.dp.toPx()),
                end = Offset(0.8.dp.toPx(), midY + 4.dp.toPx()),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // The lozenge, a diamond rather than a box.
            val d = 4.4.dp.toPx()
            val cx = w * 0.24f
            drawPath(
                path = Path().apply {
                    moveTo(cx, midY - d)
                    lineTo(cx + d, midY)
                    lineTo(cx, midY + d)
                    lineTo(cx - d, midY)
                    close()
                },
                color = ink,
            )
            // Dots either side, then three diminishing away to the right - the thing that makes
            // it read as ornament rather than as punctuation.
            listOf(
                0.14f to 0.85f,
                0.34f to 0.85f,
                0.50f to 0.50f,
                0.63f to 0.32f,
                0.74f to 0.18f,
            ).forEach { (f, a) ->
                val x = w * f
                if (x < w) drawCircle(ink.copy(alpha = a), 1.6.dp.toPx(), Offset(x, midY))
            }
        }
    }
}

@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.5.sp, color = colors.fg3)
        }
        Text("↗", fontSize = 13.sp, color = colors.fg3)
    }
}
