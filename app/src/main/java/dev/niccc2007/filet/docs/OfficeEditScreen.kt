package dev.niccc2007.filet.docs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.handlers.ViewerAction
import dev.niccc2007.filet.handlers.ViewerBar
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text editing for a DOCX or PPTX: each paragraph as a field, saved back into the original file.
 *
 * Labelled for exactly what it is - the words change, nothing else does - because an editor that
 * looks like a word processor invites layout changes it cannot make. See [OfficeTextEdit] for
 * why it splices rather than regenerates, and [SafeReplace] for why a failed save cannot cost
 * the original.
 *
 * @param onClose called after a save or a discard, so the reader can re-read the file.
 */
@Composable
fun OfficeEditScreen(vm: BrowserViewModel, node: VNode, presentation: Boolean, onClose: () -> Unit) {
    val colors = Filet.colors
    val scope = rememberCoroutineScope()
    var session by remember(node.path) { mutableStateOf<OfficeEditSession?>(null) }
    var loadError by remember(node.path) { mutableStateOf<String?>(null) }
    var saveError by remember(node.path) { mutableStateOf<String?>(null) }
    var saving by remember(node.path) { mutableStateOf(false) }
    var askDiscard by remember(node.path) { mutableStateOf(false) }
    // Section entry + paragraph index to the new text. Only paragraphs that differ are held.
    val edits = remember(node.path) { mutableStateMapOf<Pair<String, Int>, String>() }
    val dirty = edits.isNotEmpty()

    LaunchedEffect(node.path) {
        runCatching {
            if (node.size > OfficeEditSession.MAX_BYTES) throw ZipPatch.Refused("This file is too large to edit here.")
            withContext(Dispatchers.IO) {
                val bytes = vm.openRead(node).use { it.readBytes() }
                OfficeEditSession.load(bytes, presentation)
            }
        }.onSuccess { session = it }
            .onFailure { loadError = it.message ?: "This file could not be opened for editing." }
    }

    fun save() {
        val current = session ?: return
        val grouped = edits.entries.groupBy({ it.key.first }, { it.key.second to it.value })
            .mapValues { (_, v) -> v.toMap() }
        saving = true
        saveError = null
        scope.launch {
            val result = runCatching {
                val (patched, changed) = withContext(Dispatchers.Default) { current.save(grouped) }
                if (changed.isNotEmpty()) {
                    // The copy on disk is checked the same way the one in memory was: a backend
                    // that stored something else is caught before the original is touched.
                    vm.replaceVerified(node, patched) { back -> ZipPatch.check(current.original, back, changed) }
                }
                withContext(Dispatchers.Default) { OfficeEditSession.load(patched, presentation) }
            }
            saving = false
            result.onSuccess {
                session = it
                edits.clear()
                vm.toast("Saved")
            }.onFailure {
                val why = it.message ?: it::class.java.simpleName
                // SafeReplace says where the original is in the one case it is not in place.
                saveError = if (why.contains("kept as")) why else "Not saved. The file is unchanged. $why"
            }
        }
    }

    BackHandler(enabled = dirty) { askDiscard = true }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = if (dirty) "Text editing  ·  unsaved" else "Text editing",
            onClose = { if (dirty) askDiscard = true else onClose() },
        ) {
            ViewerAction(FiletIcons.Check, "Save", enabled = dirty && !saving && session != null) { save() }
        }
        Text(
            "Changes words only. Formatting, pictures and layout stay exactly as they are.",
            fontSize = 11.sp,
            color = colors.fg2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.sunken)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        if (askDiscard) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Discard unsaved changes?", fontSize = 12.sp, color = colors.warn, modifier = Modifier.weight(1f))
                Chip("Discard") {
                    edits.clear()
                    askDiscard = false
                    onClose()
                }
                Spacer(Modifier.width(8.dp))
                Chip("Keep editing") { askDiscard = false }
            }
        }
        saveError?.let { msg ->
            Text(
                msg,
                fontSize = 11.sp,
                color = colors.warn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.warn.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }

        val current = session
        when {
            loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(loadError.orEmpty(), fontSize = 12.sp, color = colors.warn)
            }
            current == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            else -> LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
                for (section in current.sections) {
                    if (current.presentation) {
                        item(key = section.entry) {
                            Text(
                                section.label.uppercase(),
                                fontSize = 9.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                            )
                        }
                    }
                    if (section.paragraphs.isEmpty()) {
                        item(key = section.entry + "#empty") {
                            Text("No text on this one.", fontSize = 11.sp, color = colors.fg3)
                        }
                    }
                    for (p in section.paragraphs) {
                        val key = section.entry to p.index
                        item(key = section.entry + "#" + p.index) {
                            ParagraphField(
                                value = edits[key] ?: p.text,
                                enabled = p.editable && !saving,
                                changed = key in edits,
                            ) { typed -> if (typed == p.text) edits.remove(key) else edits[key] = typed }
                        }
                    }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

@Composable
private fun ParagraphField(value: String, enabled: Boolean, changed: Boolean, onChange: (String) -> Unit) {
    val colors = Filet.colors
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        textStyle = TextStyle(
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3,
        ),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (changed) colors.accent.copy(alpha = 0.10f) else colors.sunken)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 11.sp,
        color = colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
