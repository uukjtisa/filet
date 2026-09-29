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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    // What installing it would do, and how many of its permissions are runtime grants. Both are
    // read from PackageManager, which is the source the installer itself consults - so the
    // answer predicts what will happen rather than describing what the file contains.
    var outlook by remember(node.path) { mutableStateOf<InstallOutlook?>(null) }
    var dangerous by remember(node.path) { mutableStateOf<Int?>(null) }
    var icon by remember(node.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val work by vm.apkWork.collectAsState()

    LaunchedEffect(node.path) {
        runCatching { vm.inspectApk(node) }
            .onSuccess { i ->
                info = i
                val installed = vm.installedFacts(i.packageName)
                val installedCode = installed.first
                val installedSha = installed.second
                outlook = InstallOutlooks.of(
                    InstallFacts(
                        signed = i.signerSha256 != null,
                        apkVersionCode = i.versionCode,
                        apkSha256 = i.signerSha256,
                        installedVersionCode = installedCode,
                        installedSha256 = installedSha,
                    ),
                )
                dangerous = vm.dangerousPermissionCount(i.permissions)
                icon = vm.apkIcon(node)
            }
            .onFailure { error = it.message ?: "Could not read this APK." }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = info?.label ?: node.name,
            subtitle = info?.packageName ?: humanSize(node.size),
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Zip, "Browse inside") { vm.browseApk(node) }
            ViewerAction(FiletIcons.Share, "Share") { vm.share(node) }
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
                    // `.insp .ih` - the identity block. The screen used to open with six
                    // sections of key-values and no summary, so the question everybody actually
                    // has (what happens if I install this) was somewhere below the fold, spelled
                    // as a subject DN and a hex digest for the reader to compare themselves.
                    item { IdentityBlock(i, icon) }

                    // The answer, stated. Severe outcomes get the banner; the ordinary ones are
                    // a quiet line, because "this is an update" is not an obstacle.
                    outlook?.takeIf { it.worthShowing }?.let { o -> item { OutlookRow(o) } }

                    if (i.warnings.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.warn.copy(alpha = 0.14f))
                                    .padding(11.dp),
                            ) {
                                i.warnings.forEach { Text(it, fontSize = 11.5.sp, color = colors.warn) }
                            }
                        }
                    }

                    // `dl.kv` - four lines that each answer something, rather than fifteen that
                    // restate the file. The raw subject and digest stay, below, for the reader
                    // who wants them.
                    item { SectionLabel("Summary") }
                    item {
                        Fact(
                            "Permissions",
                            when {
                                i.permissions.isEmpty() -> "none"
                                dangerous == null -> "${i.permissions.size}"
                                dangerous == 0 -> "${i.permissions.size} · none of them dangerous"
                                else -> "${i.permissions.size} · $dangerous of them dangerous"
                            },
                        )
                    }
                    item {
                        Fact(
                            "Size",
                            humanSize(i.sizeBytes) + " · ${i.dexEntries.size} dex · " +
                                "${i.classCount} classes",
                        )
                    }
                    if (i.debuggable) item { Fact("Debuggable", "yes — this build is not a release") }

                    item { SectionLabel("Signature") }
                    if (i.signerSubject == null) {
                        item { Fact("Signed", "no — this APK will not install as-is") }
                    } else {
                        item { Fact("Subject", i.signerSubject) }
                        item { Fact("SHA-256", i.signerSha256 ?: "") }
                    }

                    item { SectionLabel("Contents") }
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
                                "Rebuild & sign makes a new APK. The original is untouched.",
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

/**
 * `.insp .ih` - a 44dp rounded icon tile, the label, and the package in a mono line under it,
 * with a wrapping chip row for the facts that are one word each.
 *
 * Chips rather than rows because minSdk, targetSdk and an ABI list are three short facts that
 * cost three full-width rows apiece in a key-value list and read as a wall.
 */
@Composable
private fun IdentityBlock(i: ApkInfo, icon: android.graphics.Bitmap?) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxWidth().padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    // The tinted tile is the ground the glyph needs and the wrong ground for a
                    // real icon, which brings its own. It is drawn only when there is no icon.
                    .background(if (icon == null) colors.accent.copy(alpha = 0.18f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(FiletIcons.Apk, null, tint = colors.accent, modifier = Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    i.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    i.packageName + " · " + i.versionName + " (" + i.versionCode + ")",
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Chip("minSdk ${i.minSdk}")
            Chip("targetSdk ${i.targetSdk}")
            i.compileSdk?.let { Chip("compileSdk $it") }
            i.nativeAbis.forEach { Chip(it) }
            if (i.debuggable) Chip("debuggable", tone = ChipTone.WARN)
        }
    }
}

private enum class ChipTone { PLAIN, OK, WARN }

/** `.chips span` */
@Composable
private fun Chip(text: String, tone: ChipTone = ChipTone.PLAIN) {
    val colors = Filet.colors
    val ink = when (tone) {
        ChipTone.PLAIN -> colors.fg3
        ChipTone.OK -> colors.good
        ChipTone.WARN -> colors.warn
    }
    Text(
        text,
        fontSize = 9.5.sp,
        color = ink,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.sunken)
            .border(
                1.dp,
                if (tone == ChipTone.PLAIN) colors.lineSoft else ink.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * What installing it would do.
 *
 * A severe outcome is a banner because it is the thing standing between the reader and a
 * working result - a signature clash means the install cannot happen without destroying the
 * installed app's data. An ordinary update is a quiet line: it is news, not an obstacle.
 */
@Composable
private fun OutlookRow(o: InstallOutlook) {
    val colors = Filet.colors
    val ink = if (o.severe) colors.warn else colors.fg2
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (o.severe) colors.warn.copy(alpha = 0.12f) else colors.sunken)
            .padding(11.dp),
    ) {
        Text(o.headline, fontSize = 12.5.sp, color = ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(o.detail, fontSize = 11.sp, lineHeight = 15.sp, color = colors.fg3)
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
