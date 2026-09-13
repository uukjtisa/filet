package dev.niccc2007.filet.apk

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.handlers.ViewerBar
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * A structured AndroidManifest editor, on ARSCLib.
 *
 * Structured rather than a text box, deliberately. Binary XML is not text: round-tripping it
 * through a string means re-encoding resource references and losing the ones that cannot be
 * expressed, and a manifest that *looks* edited but no longer installs is worse than no
 * editor at all. Every field here maps to an ARSCLib setter that writes back exactly.
 *
 * Changes land in the decompiled working copy; [ApkTools.rebuild] picks them up from there.
 */
@Composable
fun ManifestEditorScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val draft by vm.manifestDraft.collectAsState()
    var dirty by remember(node.path) { mutableStateOf(false) }

    LaunchedEffect(node.path) { vm.loadManifest(node) }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = "AndroidManifest.xml",
            subtitle = node.name + if (dirty) "  ·  unsaved" else "",
            onClose = { vm.closeHandler() },
        ) {
            dev.niccc2007.filet.handlers.ViewerAction(FiletIcons.Check, "Apply", enabled = dirty) {
                vm.applyManifest(node)
                dirty = false
            }
        }

        val d = draft
        if (d == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "Editing writes into the decompiled working copy. Rebuild & sign turns it " +
                        "into an installable APK; the original file is never touched.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            item { SectionLabel("Identity") }
            item { Fixed("Package", d.packageName) }
            item {
                Field("Version name", d.versionName) { v ->
                    vm.editManifestField { m -> m.versionName = v }
                    dirty = true
                }
            }
            item {
                Field("Version code", d.versionCode.toString()) { v ->
                    v.toIntOrNull()?.let { code -> vm.editManifestField { m -> m.versionCode = code } }
                    dirty = true
                }
            }

            item { SectionLabel("SDK") }
            item {
                Field("minSdk", d.minSdk.toString()) { v ->
                    v.toIntOrNull()?.let { n -> vm.editManifestField { m -> m.minSdkVersion = n } }
                    dirty = true
                }
            }
            item {
                Field("targetSdk", d.targetSdk.toString()) { v ->
                    v.toIntOrNull()?.let { n -> vm.editManifestField { m -> m.targetSdkVersion = n } }
                    dirty = true
                }
            }

            item { SectionLabel("Flags") }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Debuggable", fontSize = 13.sp)
                        Text(
                            "Lets a debugger attach. Also lets any app with root read this " +
                                "app's private data, so never ship it on.",
                            fontSize = 10.sp, color = colors.fg3, lineHeight = 13.sp,
                        )
                    }
                    Switch(
                        checked = d.debuggable,
                        onCheckedChange = { on ->
                            vm.editManifestField { m -> m.setDebuggable(on) }
                            dirty = true
                        },
                    )
                }
            }

            item { SectionLabel("Permissions (${d.permissions.size})") }
            items(d.permissions.size) { idx ->
                val p = d.permissions[idx]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.removePrefix("android.permission."),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = colors.fg2, modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                var adding by remember { mutableStateOf("") }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = adding,
                        onValueChange = { adding = it },
                        singleLine = true,
                        label = { Text("Add uses-permission", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Add",
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp))
                            .background(colors.high)
                            .clickable(enabled = adding.isNotBlank()) {
                                val name = if (adding.contains('.')) adding else "android.permission.$adding"
                                vm.editManifestField { m -> m.addUsesPermission(name) }
                                adding = ""
                                dirty = true
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Snapshot of the manifest fields the editor exposes. */
data class ManifestDraft(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val permissions: List<String>,
)

@Composable
private fun Fixed(label: String, value: String) {
    val colors = Filet.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(label, fontSize = 11.5.sp, color = colors.fg3, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Field(label: String, initial: String, onChange: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        singleLine = true,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

