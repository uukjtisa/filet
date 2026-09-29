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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A group of extensions that people think of together.
 *
 * The presets exist because the alternative is an empty screen with an "Add extension" button,
 * which answers the question "what can I change?" with "you tell me". Someone who wants
 * `.mkv` to stop opening in the built-in player should find `.mkv` already on the list.
 */
/** Internal only so `MimeTableTest` can prove every offered extension resolves to a type. */
internal data class OpenerPreset(val title: String, val extensions: List<String>)

internal val OPENER_PRESETS = listOf(
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
    for (preset in OPENER_PRESETS) {
        item(key = "openers-" + preset.title) { PresetHeader(preset.title) }
        items(preset.extensions, key = { "opener-$it" }) { ext ->
            ExtensionRow(vm, ext, onPick)
        }
    }
    // Added by hand, and rendered like any other row: tapping one chooses its opener, which
    // is the second half of "add it to the list already and no more prompts".
    item(key = "openers-custom-header") { CustomExtensionsHeader(vm) }
    item(key = "openers-custom") { CustomExtensionRows(vm, onPick) }
    item { AddExtensionRow(vm) }
    item { Spacer(Modifier.height(8.dp)) }
}

@Composable
private fun OpenersIntro(vm: BrowserViewModel) {
    val colors = Filet.colors
    val overrides by vm.handlers.overrides.collectAsState()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
        Text(
            "What a single tap opens. Double tap always offers the system chooser.",
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
    val externals by vm.handlers.externals.collectAsState()
    val chosen = overrides[ext] ?: vm.handlers.builtInFor(ext)
    val custom = ext in overrides
    // "Another app" on its own is not an answer a user can check. Name it.
    val app = externals[ext]
    val label = if (chosen == HandlerId.EXTERNAL && app != null) app.label else chosen.label
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
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                color = if (custom) colors.accent else colors.fg2,
            )
            // Shown even when this type is NOT routed externally: it is the app a chooser
            // answered once, and knowing it is remembered is the whole point of the feature.
            if (app != null && chosen != HandlerId.EXTERNAL) {
                Text(
                    "hand-off goes to ${app.label}",
                    fontSize = 9.5.sp, color = colors.fg3,
                )
            }
        }
        if (custom) {
            Icon(
                FiletIcons.Close, "Reset to default", tint = colors.fg3,
                modifier = Modifier.size(18.dp).clickable { vm.handlers.clearDefault(ext) }.padding(3.dp),
            )
        }
    }
}

@Composable
private fun CustomExtensionsHeader(vm: BrowserViewModel) {
    val custom by vm.prefs.customExtensions.collectAsState()
    if (custom.isNotEmpty()) PresetHeader("Added by you")
}

@Composable
private fun CustomExtensionRows(vm: BrowserViewModel, onPick: (String) -> Unit) {
    val custom by vm.prefs.customExtensions.collectAsState()
    Column {
        for (ext in custom.sorted()) {
            ExtensionRow(vm, ext, onPick)
        }
    }
}

/**
 * Adding an extension the presets do not cover.
 *
 * Bug identified: typing one went straight into the opener chooser, and if nothing on the
 * device DECLARED that type the chooser refused - so the extension could not be added at all
 * and nothing was left behind to try again with. Adding and choosing are now two steps: this
 * stores the extension, and its row is then tapped like any other to pick an opener.
 */
@Composable
private fun AddExtensionRow(vm: BrowserViewModel) {
    val colors = Filet.colors
    var asking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var dots by remember { mutableStateOf<ExtensionEntry.Verdict?>(null) }

    fun commit(value: String) {
        vm.prefs.addCustomExtension(value)
        vm.toast("Added .$value — tap it to choose an opener")
    }

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
                "Added straight away. Tap it afterwards to choose.",
                fontSize = 10.sp, color = colors.fg3,
            )
        }
    }

    if (asking) {
        Dlg(onDismiss = { asking = false }, dismissOnScrim = false) {
            DlgHeader(FiletIcons.File, "Which extension?", onClose = { asking = false })
            DlgBody {
                // NOT normalised as it is typed. Silently deleting characters under somebody's
                // cursor is how a deliberate entry becomes an unexplained one; the dots are
                // raised after, where they can be answered.
                DlgField("Extension", typed, { typed = it }, hint = "png, tar.gz")
            }
            DlgFooter {
                DlgBtn("Cancel") { asking = false }
                DlgBtn(
                    "Add",
                    kind = BtnKind.PRIMARY,
                    enabled = ExtensionEntry.inspect(typed).valid,
                ) {
                    val v = ExtensionEntry.inspect(typed)
                    if (v.valid) {
                        asking = false
                        if (v.askAboutDots) dots = v else commit(v.cleaned)
                    } else {
                        asking = false
                    }
                }
            }
        }
    }

    // The dots are a question with three defensible answers, so all three are offered rather
    // than one being applied quietly.
    dots?.let { v ->
        // Three defensible answers, so all three are rows of equal weight rather than one
        // confirm and two afterthoughts crammed into the dismiss slot - which is what they were
        // when this was an AlertDialog with only two buttons to put them in.
        Dlg(onDismiss = { dots = null }) {
            DlgHeader(FiletIcons.Info, "That has extra dots", onClose = { dots = null })
            DlgBody {
                Text(
                    ExtensionEntry.dotQuestion(v),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Filet.colors.fg2,
                )
                Spacer(Modifier.height(10.dp))
                DlgAction(FiletIcons.Check, "Use ${v.cleaned}") {
                    dots = null
                    commit(ExtensionEntry.resolve(v, ExtensionEntry.Choice.DROP_DOTS))
                }
                DlgAction(FiletIcons.Check, "Use .${v.cleaned}") {
                    dots = null
                    commit(ExtensionEntry.resolve(v, ExtensionEntry.Choice.ONE_DOT))
                }
                DlgAction(FiletIcons.Check, "Keep ${v.typed}") {
                    dots = null
                    commit(ExtensionEntry.resolve(v, ExtensionEntry.Choice.KEEP))
                }
            }
            DlgFooter {
                DlgSpacer()
                DlgBtn("Cancel") { dots = null }
            }
        }
    }
}

/**
 * Settings' route into "Open with".
 *
 * Kept as a name rather than replaced at the call site, because this one is about a TYPE and the
 * other is about a FILE, and the two readings are worth keeping distinct where they are called
 * from. The dialogue they open is the same one - see [OpenWithDialog] for why it was two.
 */
@Composable
fun OpenerPicker(vm: BrowserViewModel, extension: String, onDismiss: () -> Unit) =
    OpenWithDialog(vm = vm, file = null, extension = extension, onDismiss = onDismiss)
