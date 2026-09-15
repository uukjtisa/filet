package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.ArchiveCapability
import dev.niccc2007.filet.vfs.provider.EncryptionMethod

/**
 * The per-format half of the compress dialog: strength, password, split.
 *
 the tailoring is not cosmetic - the
 * three formats that take a password are not the three that take a strength, and none of them
 * share a scale. So **every control here is drawn from [ArchiveCapability] and nothing is drawn
 * from a `when` over format ids.** That is rule R1: the alternative is two answers to "can a 7z
 * take a password", the dialog's and the writer's, and the one that loses is the writer, which
 * finds out after somebody has typed a password and pressed Compress.
 *
 * `tools/check-archiveui.mjs` fails the build if a control in this file is rendered outside a
 * capability check.
 *
 * A refusal is SHOWN rather than the control being hidden. "7z supports AES-256, but this build
 * cannot write it" tells somebody to use zip; an absent field tells them nothing and reads as
 * an app that forgot.
 */
@Composable
fun ArchiveOptionsSection(
    capability: ArchiveCapability,
    strength: Int?,
    onStrength: (Int) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    encryption: EncryptionMethod?,
    onEncryption: (EncryptionMethod) -> Unit,
    splitBytes: Long?,
    onSplit: (Long?) -> Unit,
    splitProblem: String?,
) {
    val colors = Filet.colors

    // ── strength ──
    val scale = capability.strength
    if (scale != null) {
        Spacer(Modifier.height(12.dp))
        val value = strength ?: scale.default
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            // The unit, not just the number. Deflate levels, LZMA2 presets and bzip2 block
            // sizes are three different things that happen to be small integers.
            Text("$value  ${scale.unit}", fontSize = 10.sp, color = colors.fg3)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onStrength(scale.coerce(it.toInt())) },
            valueRange = scale.min.toFloat()..scale.max.toFloat(),
            steps = (scale.max - scale.min - 1).coerceAtLeast(0),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(scale.minLabel, fontSize = 9.5.sp, color = colors.fg3)
            Spacer(Modifier.weight(1f))
            Text(scale.maxLabel, fontSize = 9.5.sp, color = colors.fg3)
        }
    }

    // ── password ──
    Spacer(Modifier.height(12.dp))
    if (capability.password.supported) {
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            singleLine = true,
            label = { Text("Password (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (password.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (m in capability.password.methods) {
                    val on = m == (encryption ?: capability.password.default)
                    Chip(m.label, on) { onEncryption(m) }
                    Spacer(Modifier.width(6.dp))
                }
            }
            val chosen = encryption ?: capability.password.default
            if (chosen?.weak == true) {
                Text(
                    "Broken since 1994. Only worth choosing when something cannot read AES.",
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!capability.password.canEncryptNames) {
                // Said plainly, because somebody who sets a password reasonably assumes the
                // whole archive is hidden. A zip's central directory is never encrypted.
                Text(
                    "The file names stay readable - a zip never hides those.",
                    fontSize = 9.5.sp,
                    color = colors.fg3,
                )
            }
        }
    } else {
        Text(
            capability.password.refusal.orEmpty(),
            fontSize = 10.sp,
            color = colors.fg3,
        )
    }

    // ── split ──
    Spacer(Modifier.height(12.dp))
    if (capability.canSplit) {
        Text("Split into parts", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(5.dp))
        // FlowRow, not Row. Five chips do not fit across a 1080px phone, and a plain Row does
        // not overflow - it squeezes, so the last chip renders "4 GB" broken across three lines,
        // one character wide. Caught by looking at a screenshot of it.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Chip("One file", splitBytes == null) { onSplit(null) }
            for ((label, size) in SPLIT_SIZES) {
                Chip(label, splitBytes == size) { onSplit(size) }
            }
        }
        if (splitProblem != null) {
            Text(splitProblem, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(capability.splitRefusal.orEmpty(), fontSize = 10.sp, color = colors.fg3)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 11.sp,
        fontFamily = if (label.first().isDigit()) FontFamily.Monospace else FontFamily.Default,
        color = if (on) colors.accent else colors.fg2,
        // A chip is a label, so it never breaks: a squeezed one that wraps reads as a rendering
        // fault rather than as a size.
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) colors.sel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/**
 * The part sizes worth offering.
 *
 * Chosen for what people actually send things over rather than for round numbers: 25 MB is a
 * common mail limit, 100 MB a common chat one, 700 MB a CD, 4 GB the largest file a FAT32 card
 * will hold - which is the one that catches people out with a phone's SD card.
 */
private val SPLIT_SIZES = listOf(
    "25 MB" to 25L * 1024 * 1024,
    "100 MB" to 100L * 1024 * 1024,
    "700 MB" to 700L * 1024 * 1024,
    "4 GB" to 4L * 1024 * 1024 * 1024 - 1,
)
