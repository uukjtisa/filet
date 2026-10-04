package dev.niccc2007.filet.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.niccc2007.filet.handlers.ViewerAction
import dev.niccc2007.filet.handlers.ViewerBar
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * Read and write a file's own metadata.
 *
 * ## What this is, and why it took a round to appear
 *
 * The engine - `MetadataStore`, `MetadataSupport` - has been built and tested for several rounds
 * with nothing reaching it, which `REDESIGN-SPEC.md` has listed as outstanding the whole time.
 * This is the surface, built to the mock rather than designed again: the support banner, the
 * standard fields, the arbitrary ones, the backup tick and the collapsible format matrix are all
 * the mock's, in its order.
 *
 * ## One thing in the mock that is deliberately not here
 *
 * The mock draws a **cover art** block with Replace, Extract and Remove. `MetadataStore` writes
 * string values and has no binary path at all, so those three buttons would be drawn, enabled,
 * and do nothing - which is exactly the dead control R1 forbids, and it is the fault this app has
 * already fixed twice on other screens. The block is left out until the engine can honour it, and
 * the formats that take an attached picture still say so in the matrix.
 */
@Composable
fun MetadataScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val format = remember(node.path) { MetadataSupport.writerFor(node.extension) }
    val readable = remember(node.path) { MetadataSupport.forExtension(node.extension).firstOrNull() }

    var loading by remember(node.path) { mutableStateOf(true) }
    var backup by remember(node.path) { mutableStateOf(true) }
    var matrixOpen by remember(node.path) { mutableStateOf(false) }
    var band by remember(node.path) { mutableStateOf(MetadataSupport.Tier.FULL) }
    var busy by remember(node.path) { mutableStateOf(false) }

    // Edits are held until Save, like every other writer in the app: writing metadata rewrites
    // the file, and a field that commits as you type would rewrite it per keystroke.
    val fields = remember(node.path) { mutableStateListOf<Pair<String, String>>() }
    var dirty by remember(node.path) { mutableStateOf(false) }

    LaunchedEffect(node.path) {
        fields.clear()
        fields.addAll(runCatching { vm.readMetadata(node) }.getOrDefault(emptyList()))
        // Every field the format declares, shown whether or not the file carries it yet - an
        // empty Title is a thing to fill in, and a writer that only lists what is already there
        // can never add anything.
        format?.fields?.forEach { key ->
            if (fields.none { it.first.equals(key, ignoreCase = true) }) fields.add(key to "")
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = (format ?: readable)?.name?.let { "$it container" } ?: "unknown format",
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Check, "Save", enabled = dirty && !busy && format != null) {
                busy = true
                vm.writeMetadata(node, fields.toList(), backup) {
                    busy = false
                    dirty = false
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SupportBanner(format, readable, node.extension) { matrixOpen = !matrixOpen } }

            if (format != null) {
                item { FieldGroup("Standard fields") }
                items(fields.size) { i ->
                    val (key, value) = fields[i]
                    val standard = format.fields.any { it.equals(key, ignoreCase = true) }
                    if (standard) {
                        MetaRow(key, value, onRemove = null) { fields[i] = key to it; dirty = true }
                    }
                }

                if (format.custom) {
                    item {
                        FieldGroup(
                            "Your own fields",
                            "Any name, any value. This container takes arbitrary keys natively.",
                        )
                    }
                    items(fields.size) { i ->
                        val (key, value) = fields[i]
                        val standard = format.fields.any { it.equals(key, ignoreCase = true) }
                        if (!standard) {
                            MetaRow(
                                key,
                                value,
                                onRemove = {
                                    // Emptied rather than dropped from the list: an empty value is
                                    // how the store removes a key, and a row that simply vanishes
                                    // would leave the old value in the file.
                                    fields[i] = key to ""
                                    dirty = true
                                },
                            ) { fields[i] = key to it; dirty = true }
                        }
                    }
                    item {
                        AddFieldRow { name ->
                            if (name.isNotBlank() && fields.none { it.first == name }) {
                                fields.add(name to "")
                                dirty = true
                            }
                        }
                    }
                }

                item {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        DlgTick(
                            on = backup,
                            label = "Back up the file before writing",
                            sub = "Copied beside it as ${node.name}.bak.",
                        ) { backup = !backup }
                    }
                }
            }

            item {
                FormatMatrix(
                    open = matrixOpen,
                    band = band,
                    onBand = { band = it },
                    onToggle = { matrixOpen = !matrixOpen },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * What this file's format can actually hold.
 *
 * First on the screen because it decides whether anything below it is worth reading, and because
 * the honest answer for some formats is "not much" - which somebody should be told before they
 * fill in four fields.
 */
@Composable
private fun SupportBanner(
    format: MetadataSupport.Format?,
    readable: MetadataSupport.Format?,
    extension: String,
    onMore: () -> Unit,
) {
    val colors = Filet.colors
    val tier = format?.tier ?: readable?.tier
    val tone = when (tier) {
        MetadataSupport.Tier.FULL -> colors.good
        MetadataSupport.Tier.PARTIAL -> colors.warn
        MetadataSupport.Tier.READ_ONLY -> colors.bad
        null -> colors.fg3
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(8.dp))
            Text(
                when (tier) {
                    MetadataSupport.Tier.FULL -> "Fully supported."
                    MetadataSupport.Tier.PARTIAL -> "About half of it survives."
                    MetadataSupport.Tier.READ_ONLY -> "Filet can read this, not write it."
                    null -> "Filet does not know this format."
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            format?.caveat ?: if (format != null) {
                "This container takes " +
                    (if (format.custom) "tags and arbitrary fields" else "a fixed set of fields") +
                    (if (format.binary) ", and a real attached picture." else ".")
            } else {
                MetadataSupport.refusalFor(extension)
            },
            fontSize = 10.5.sp,
            color = colors.fg2,
            lineHeight = 14.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "What about other formats?",
            fontSize = 10.5.sp,
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onMore),
        )
    }
}

@Composable
private fun FieldGroup(label: String, hint: String? = null) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 4.dp)) {
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
            color = colors.fg3,
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 10.sp, color = colors.fg3, lineHeight = 14.sp)
        }
    }
}

/** One key and its value. The key is fixed; only the value is typed into. */
@Composable
private fun MetaRow(
    key: String,
    value: String,
    onRemove: (() -> Unit)?,
    onChange: (String) -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(key, fontSize = 9.5.sp, color = colors.fg3, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(3.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.sunken)
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (value.isEmpty()) {
                            Text("not set", fontSize = 12.sp, color = colors.fg3)
                        }
                        inner()
                    }
                },
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                FiletIcons.Delete,
                "Clear this field",
                tint = colors.fg3,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRemove)
                    .padding(7.dp),
            )
        }
    }
}

@Composable
private fun AddFieldRow(onAdd: (String) -> Unit) {
    val colors = Filet.colors
    var name by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (name.isEmpty()) Text("field name", fontSize = 12.sp, color = colors.fg3)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "+ Add a field",
            fontSize = 11.sp,
            color = if (name.isBlank()) colors.fg3 else colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = name.isNotBlank()) { onAdd(name.trim()); name = "" }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}

/**
 * Which formats support what, shut by default.
 *
 * The one piece of this screen that is reference rather than action, so it is behind a fold -
 * the same rule the APK inspector's lists follow. The counts are read from the support table
 * rather than written here, so the fold cannot drift from what the engine actually does.
 */
@Composable
private fun FormatMatrix(
    open: Boolean,
    band: MetadataSupport.Tier,
    onBand: (MetadataSupport.Tier) -> Unit,
    onToggle: () -> Unit,
) {
    val colors = Filet.colors
    val counts = remember { MetadataSupport.countByTier() }
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Which formats support what",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "-" else "+", fontSize = 13.sp, color = colors.fg3)
        }
        if (!open) return@Column

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (t in MetadataSupport.Tier.entries) {
                val tone = when (t) {
                    MetadataSupport.Tier.FULL -> colors.good
                    MetadataSupport.Tier.PARTIAL -> colors.warn
                    MetadataSupport.Tier.READ_ONLY -> colors.bad
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (t == band) tone.copy(alpha = 0.18f) else colors.raised)
                        .border(
                            1.dp,
                            if (t == band) tone else colors.lineSoft,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable { onBand(t) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${counts[t] ?: 0}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when (t) {
                            MetadataSupport.Tier.FULL -> "Fully"
                            MetadataSupport.Tier.PARTIAL -> "About half"
                            MetadataSupport.Tier.READ_ONLY -> "Read only"
                        },
                        fontSize = 9.sp,
                        color = colors.fg3,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        for (f in MetadataSupport.FORMATS.filter { it.tier == band }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    f.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    f.caveat ?: buildString {
                        append(if (f.custom) "any field" else "${f.fields.size} fields")
                        if (f.binary) append(", attached picture")
                    },
                    fontSize = 10.sp,
                    color = colors.fg3,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
