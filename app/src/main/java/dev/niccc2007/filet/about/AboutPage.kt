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
            AboutRow(FiletIcons.Download, "Trawl", "The companion downloader") {
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

    // The rule draws once on arrival - 1100 ms, 120 ms delay - and never again. Redrawing it
    // on every glare loop was a bug in Trawl worth not repeating.
    var ruleProgress by remember { mutableFloatStateOf(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val start = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - start) / 1100f
            ruleProgress = Motion.Out.transform(t.coerceIn(0f, 1f))
            if (t >= 1f) break
            kotlinx.coroutines.delay(16)
        }
    }

    // Glare: 2.6 s streak, 6.4 s rest, 9 s cycle, band at 42% of the name.
    val infinite = rememberInfiniteTransition(label = "glare")
    val glare by infinite.animateFloat(
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
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 28.dp, y = 24.dp)
                .size(158.dp)
                .rotate(-12f),
        )
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FiletIcons.Mark, null, tint = colors.accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "BUILT BY",
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp,
                    color = colors.fg2,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "niccc2007",
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (reduced) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val band = size.width * 0.42f
                    val x = -band + glare * (size.width + band * 2)
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.30f),
                            1f to Color.Transparent,
                            start = Offset(x, 0f),
                            end = Offset(x + band, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
            )
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier
                    .fillMaxWidth(ruleProgress.coerceAtLeast(0.001f))
                    .height(1.dp)
                    .background(colors.lineSoft)
            )
            Spacer(Modifier.height(11.dp))
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
