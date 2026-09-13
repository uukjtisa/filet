package dev.niccc2007.filet.apk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.ApkAction
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.handlers.ViewerAction
import dev.niccc2007.filet.handlers.ViewerBar
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * The APK inspector.
 *
 * Reads first and offers work second, in that order: everything on this screen is a fact
 * about the file before any button is pressed. The buttons that rewrite the APK are grouped
 * and named for what they do to it.
 */
@Composable
fun ApkInspectorScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var info by remember(node.path) { mutableStateOf<ApkInfo?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    val work by vm.apkWork.collectAsState()

    LaunchedEffect(node.path) {
        runCatching { vm.inspectApk(node) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: "Could not read this APK." }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = info?.label ?: node.name,
            subtitle = info?.packageName ?: humanSize(node.size),
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Zip, "Browse inside") { vm.browseApk(node) }
            ViewerAction(FiletIcons.Share, "Share") { vm.shareOne(node) }
        }

        when {
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(error!!, fontSize = 13.sp, color = colors.fg2)
            }
            info == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            else -> {
                val i = info!!
                LazyColumn(Modifier.fillMaxSize()) {
                    if (i.warnings.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(10.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.warn.copy(alpha = 0.14f))
                                    .padding(11.dp),
                            ) {
                                i.warnings.forEach { Text(it, fontSize = 11.5.sp, color = colors.warn) }
                            }
                        }
                    }

                    item { SectionLabel("Identity") }
                    item { Fact("Package", i.packageName) }
                    item { Fact("Version", "${i.versionName}  (${i.versionCode})") }
                    item { Fact("Size", humanSize(i.sizeBytes)) }
                    if (i.debuggable) item { Fact("Debuggable", "yes — this build is not a release") }

                    item { SectionLabel("SDK") }
                    item { Fact("minSdk", i.minSdk.toString()) }
                    item { Fact("targetSdk", i.targetSdk.toString()) }
                    i.compileSdk?.let { item { Fact("compileSdk", it.toString()) } }

                    item { SectionLabel("Signature") }
                    if (i.signerSubject == null) {
                        item { Fact("Signed", "no — this APK will not install as-is") }
                    } else {
                        item { Fact("Subject", i.signerSubject) }
                        item { Fact("SHA-256", i.signerSha256 ?: "") }
                    }

                    item { SectionLabel("Contents") }
                    item { Fact("Classes", "${i.classCount} in ${i.dexEntries.size} dex file(s)") }
                    if (i.nativeAbis.isNotEmpty()) item { Fact("Native", i.nativeAbis.joinToString(", ")) }
                    items(i.dexEntries.size) { idx ->
                        val dex = i.dexEntries[idx]
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.browseDex(node, dex) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FiletIcons.Dex, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(dex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text("open as smali", fontSize = 10.sp, color = colors.fg3)
                        }
                    }

                    if (i.permissions.isNotEmpty()) {
                        item { SectionLabel("Permissions (${i.permissions.size})") }
                        item {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                i.permissions.forEach { p ->
                                    Text(
                                        p.removePrefix("android.permission."),
                                        fontSize = 9.5.sp,
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

                    item { SectionLabel("Work") }
                    item {
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            if (work != null) {
                                Text(work!!, fontSize = 11.5.sp, color = colors.accent, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(8.dp))
                            }
                            // Each chip asks the view model the same question the action
                            // itself asks, so a chip is never enabled into a toast that says
                            // it was never going to work.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionChip("Decompile to folder", vm.apkBlockReason(node, ApkAction.DECOMPILE), vm::toast) { vm.decompileApk(node) }
                                ActionChip("Rebuild & sign", vm.apkBlockReason(node, ApkAction.REBUILD), vm::toast) { vm.rebuildApk(node) }
                                ActionChip("Sign as-is", vm.apkBlockReason(node, ApkAction.SIGN), vm::toast) { vm.signApk(node) }
                                ActionChip("Edit manifest", vm.apkBlockReason(node, ApkAction.MANIFEST), vm::toast) { vm.editManifest(node) }
                                ActionChip("Install", vm.apkBlockReason(node, ApkAction.INSTALL), vm::toast) { vm.installApk(node) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Decompile writes a working folder beside the APK. Edit the smali " +
                                    "there, then Rebuild & sign produces a new, installable APK — the " +
                                    "original is never modified in place.",
                                fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = Filet.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(label, fontSize = 11.5.sp, color = colors.fg3, modifier = Modifier.width(92.dp))
        Text(
            value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
/**
 * @param blocked why this action cannot run, or null when it can.
 *
 * A blocked chip is greyed **and still tappable**, and tapping it says why. A phone has no
 * hover, so a plain disabled control is a dead end - the user is left to guess what makes it
 * come back, which is the complaint this whole gate exists for.
 */
private fun ActionChip(
    text: String,
    blocked: String?,
    onBlocked: (String) -> Unit,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val enabled = blocked == null
    Text(
        text,
        fontSize = 11.5.sp,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3.copy(alpha = 0.55f),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp))
            .background(if (enabled) colors.high else colors.sunken)
            .clickable { if (enabled) onClick() else onBlocked(blocked!!) }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
