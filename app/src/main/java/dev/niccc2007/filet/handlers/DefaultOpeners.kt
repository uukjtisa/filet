package dev.niccc2007.filet.handlers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A group of extensions that people think of together.
 *
 * The presets exist because the alternative is an empty screen with an "Add extension" button,
 * which answers the question "what can I change?" with "you tell me". Someone who wants
 * `.mkv` to stop opening in the built-in player should find `.mkv` already on the list.
 */
private data class OpenerPreset(val title: String, val extensions: List<String>)

private val PRESETS = listOf(
    OpenerPreset("Text and code", listOf("txt", "md", "log", "json", "xml", "kt", "java", "py", "lua", "c", "h", "sh")),
    OpenerPreset("Images", listOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic")),
    OpenerPreset("Video and audio", listOf("mp4", "mkv", "webm", "avi", "mov", "mp3", "m4a", "flac", "opus", "wav")),
    OpenerPreset("Archives and packages", listOf("zip", "apk", "jar", "tar", "gz", "7z", "rar", "dex")),
    OpenerPreset("Documents", listOf("pdf", "docx", "xlsx", "pptx", "epub", "csv")),
)

/**
 * Settings ▸ Default openers.
 *
 * Written as a `LazyListScope` extension rather than its own screen so it lives in the same
 * scroll as everything else in Settings — a list this long inside a nested scroller is a
 * gesture fight, and a separate screen for one table is a place users do not find.
 */
fun LazyListScope.defaultOpenersSection(vm: BrowserViewModel, onPick: (String) -> Unit) {
    item { SectionLabel("Default openers") }
    item { OpenersIntro(vm) }
    for (preset in PRESETS) {
        item(key = "openers-" + preset.title) { PresetHeader(preset.title) }
        items(preset.extensions, key = { "opener-$it" }) { ext ->
            ExtensionRow(vm, ext, onPick)
        }
    }
    item { AddExtensionRow(vm, onPick) }
    item { Spacer(Modifier.height(8.dp)) }
}

@Composable
private fun OpenersIntro(vm: BrowserViewModel) {
    val colors = Filet.colors
    val overrides by vm.handlers.overrides.collectAsState()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
        Text(
            "What a single tap opens. Double tap always offers the system chooser instead, so " +
                "nothing here can lock you out of another app.",
            fontSize = 11.sp, color = colors.fg3, lineHeight = 15.sp,
        )
        if (overrides.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${overrides.size} changed from the default",
                    fontSize = 11.sp, color = colors.accent, modifier = Modifier.weight(1f),
                )
                Text(
                    "Reset all",
                    fontSize = 11.sp, color = colors.bad,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.handlers.clearAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetHeader(title: String) {
    Text(
        title,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun ExtensionRow(vm: BrowserViewModel, ext: String, onPick: (String) -> Unit) {
    val colors = Filet.colors
    // Subscribed, so a change made in the chooser sheet shows here without a revisit.
    val overrides by vm.handlers.overrides.collectAsState()
    val chosen = overrides[ext] ?: vm.handlers.builtInFor(ext)
    val custom = ext in overrides
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(ext) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ".$ext",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(64.dp),
        )
        Text(
            chosen.label,
            fontSize = 12.sp,
            color = if (custom) colors.accent else colors.fg2,
            modifier = Modifier.weight(1f),
        )
        if (custom) {
            Icon(
                FiletIcons.Close, "Reset to default", tint = colors.fg3,
                modifier = Modifier.size(18.dp).clickable { vm.handlers.clearDefault(ext) }.padding(3.dp),
            )
        }
    }
}

@Composable
private fun AddExtensionRow(vm: BrowserViewModel, onPick: (String) -> Unit) {
    val colors = Filet.colors
    var asking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .clickable { typed = ""; asking = true }
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Plus, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Another extension", fontSize = 12.5.sp)
            Text(
                "Anything not listed above — type it without the dot.",
                fontSize = 10.sp, color = colors.fg3,
            )
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Which extension?", fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = typed,
                    // Normalised here, not on save: an extension with a dot, a space or a
                    // capital in it silently never matches anything and looks like a bug in
                    // the routing rather than a typo in the box.
                    onValueChange = { typed = it.trim().trimStart('.').lowercase().take(12) },
                    singleLine = true,
                    label = { Text("Extension") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { asking = false; onPick(typed) },
                    enabled = typed.isNotBlank(),
                ) { Text("Choose opener") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The picker itself.
 *
 * Shown from Settings for one extension. It lists exactly what [HandlerRegistry.candidatesFor]
 * offers at a tap — no more, because an option here that the chooser does not have is a
 * setting that does not take effect (PLAN.md R1).
 */
@Composable
fun OpenerPicker(vm: BrowserViewModel, extension: String, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val candidates = remember(extension) { vm.handlers.candidatesForExtension(extension) }
    val builtIn = remember(extension) { vm.handlers.builtInFor(extension) }
    val overrides by vm.handlers.overrides.collectAsState()
    val current = overrides[extension] ?: builtIn

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open .$extension with", fontSize = 15.sp) },
        text = {
            Column {
                for (id in candidates) {
                    val on = id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // Choosing Filet's own pick CLEARS the override rather than
                                // pinning it. Otherwise the row silently stops following a
                                // future default, which is not what "same as before" means.
                                if (id == builtIn) vm.handlers.clearDefault(extension)
                                else vm.handlers.setDefault(extension, id)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                id.label,
                                fontSize = 13.sp,
                                color = if (on) colors.accent else MaterialTheme.colorScheme.onSurface,
                            )
                            if (id == builtIn) {
                                Text("Filet's default for this type", fontSize = 10.sp, color = colors.fg3)
                            }
                            if (id == HandlerId.EXTERNAL) {
                                Text("Hands the file to another app", fontSize = 10.sp, color = colors.fg3)
                            }
                        }
                        if (on) {
                            Icon(
                                FiletIcons.Check, null, tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
