package dev.niccc2007.filet.apk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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

    // The names as well as the count: one puts a number on the summary line, the other puts the
    // dangerous permissions at the top of the list and marks them. Same single query.
    var dangerousNames by remember(node.path) { mutableStateOf<Set<String>?>(null) }
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
                val risky = vm.dangerousPermissions(i.permissions)
                dangerousNames = risky
                dangerous = risky.size
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
                    // `dl.kv` - one framed block of lines that each answer something, rather
                    // than three headed sections restating the file. Signature folded in: who
                    // signed it is a summary fact, and the digest nobody reads by eye is behind
                    // the fold with the rest of the detail.
                    item { SectionLabel("Summary") }
                    item {
                        FactBlock {
                            Fact(
                                "Permissions",
                                when {
                                    i.permissions.isEmpty() -> "none"
                                    dangerous == null -> "${i.permissions.size}"
                                    dangerous == 0 -> "${i.permissions.size} · none dangerous"
                                    else -> "${i.permissions.size} · $dangerous of them dangerous"
                                },
                            )
                            Fact(
                                "Size",
                                humanSize(i.sizeBytes) + " · ${i.dexEntries.size} dex · " +
                                    "${i.classCount} classes",
                            )
                            Fact(
                                "Signed by",
                                i.signerSubject?.let { subject ->
                                    // The common name alone. A full X.500 subject is six fields
                                    // of which one is read, and printing all six is how this line
                                    // became three wrapped rows.
                                    Regex("CN=([^,]+)").find(subject)?.groupValues?.get(1)?.trim()
                                        ?: subject
                                } ?: "nothing — this APK will not install as-is",
                            )
                            if (i.debuggable) Fact("Debuggable", "yes — not a release build")
                        }
                    }

                    // Everything below is a LIST, and a list is what made this screen unusable on
                    // a real app: sixty permission chips and a dex row each, all open, before the
                    // actions. Folded shut, with the count on the header so the fold still answers
                    // the question without being opened.
                    if (i.permissions.isNotEmpty()) {
                        item {
                            Fold(
                                title = "Permissions",
                                detail = when {
                                    dangerous == null -> "${i.permissions.size}"
                                    dangerous == 0 -> "${i.permissions.size} · none dangerous"
                                    else -> "${i.permissions.size} · $dangerous dangerous"
                                },
                                tone = if ((dangerous ?: 0) > 0) FoldTone.WARN else FoldTone.PLAIN,
                            ) {
                                // Dangerous first. In declaration order the reader is asked to
                                // know which of sixty names matter, which is the question they
                                // opened the inspector to have answered.
                                val risky = dangerousNames.orEmpty()
                                val ordered = i.permissions.sortedByDescending { it in risky }
                                ordered.forEach { p ->
                                    PermissionRow(p, dangerous = p in risky)
                                }
                            }
                        }
                    }

                    item {
                        Fold(
                            title = "Contents",
                            detail = buildList {
                                add("${i.dexEntries.size} dex")
                                if (i.nativeAbis.isNotEmpty()) add(i.nativeAbis.joinToString(", "))
                            }.joinToString(" · "),
                        ) {
                            if (i.nativeAbis.isNotEmpty()) {
                                Fact("Native", i.nativeAbis.joinToString(", "))
                            }
                            i.dexEntries.forEach { dex ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { vm.browseDex(node, dex) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        FiletIcons.Dex, null, tint = colors.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        dex,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("open as smali", fontSize = 10.sp, color = colors.fg3)
                                }
                            }
                        }
                    }

                    if (i.signerSubject != null) {
                        item {
                            Fold(title = "Signature", detail = "certificate") {
                                Fact("Subject", i.signerSubject)
                                Fact("SHA-256", i.signerSha256 ?: "")
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
                            // Rows, not a flow of chips.
                            //
                            // Five chips wrapping at whatever width they happened to reach gave no
                            // order and no explanation: "Sign as-is" and "Install" sat side by
                            // side looking equivalent, and one of them replaces an app on the
                            // phone. Each action now gets a line, a verb and one sentence saying
                            // what it does, grouped by what it produces - because "makes a file"
                            // and "changes this phone" are not the same kind of button.
                            //
                            // Each row asks the view model the same question the action itself
                            // asks, so a row is never enabled into a toast saying it was never
                            // going to work.
                            WorkGroup("Makes a new file") {
                                WorkRow(
                                    FiletIcons.Dex, "Decompile to folder",
                                    "Writes a smali tree you can read and edit.",
                                    vm.apkBlockReason(node, ApkAction.DECOMPILE), vm::toast,
                                ) { vm.decompileApk(node) }
                                WorkRow(
                                    FiletIcons.Zip, "Rebuild & sign",
                                    "Builds a new APK from the folder. The original is untouched.",
                                    vm.apkBlockReason(node, ApkAction.REBUILD), vm::toast,
                                ) { vm.rebuildApk(node) }
                                WorkRow(
                                    FiletIcons.Key, "Sign as-is",
                                    "Signs this APK without changing what is in it.",
                                    vm.apkBlockReason(node, ApkAction.SIGN), vm::toast,
                                ) { vm.signApk(node) }
                                WorkRow(
                                    FiletIcons.Rename, "Edit manifest",
                                    "Opens AndroidManifest.xml as text.",
                                    vm.apkBlockReason(node, ApkAction.MANIFEST), vm::toast,
                                ) { vm.editManifest(node) }
                            }
                            Spacer(Modifier.height(10.dp))
                            WorkGroup("Changes this phone") {
                                WorkRow(
                                    FiletIcons.Device, "Install",
                                    "Hands the APK to Android's installer.",
                                    vm.apkBlockReason(node, ApkAction.INSTALL), vm::toast,
                                ) { vm.installApk(node) }
                            }
                            Spacer(Modifier.height(20.dp))
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

/** How a fold's header reads: plain, or carrying something worth noticing. */
private enum class FoldTone { PLAIN, WARN }

/**
 * A section that is shut until it is asked for.
 *
 * The specific answer to this screen being unusable on a real app. An APK with sixty permissions
 * and four dexes rendered every one of them, open, above the actions - so a screen whose job is to
 * say what installing this file would do answered with four pages of inventory instead, and put
 * the buttons past the end of it.
 *
 * The count lives on the HEADER, so a shut fold still answers the question. Opening it is for the
 * reader who wants the names, which is the rarer case and is now the one that costs a tap rather
 * than the one that costs everybody else four pages.
 *
 * The screen's job is to say what installing this file would do. An inventory is not that answer,
 * and putting four pages of one above the actions buried the answer along with them.
 */
@Composable
private fun Fold(
    title: String,
    detail: String,
    tone: FoldTone = FoldTone.PLAIN,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Filet.colors
    var open by remember(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, if (tone == FoldTone.WARN) colors.warn.copy(alpha = 0.45f) else colors.lineSoft, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                detail,
                fontSize = 10.5.sp,
                color = if (tone == FoldTone.WARN) colors.warn else colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) FiletIcons.Up else FiletIcons.Sort,
                if (open) "Collapse" else "Expand",
                tint = colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), content = content)
        }
    }
}

/**
 * The summary, as one framed block.
 *
 * Loose rows under a heading read as the start of a list that keeps going - which on this screen
 * it used to. A frame says where the summary ends.
 */
@Composable
private fun FactBlock(content: @Composable ColumnScope.() -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, shape)
            .padding(vertical = 5.dp),
        content = content,
    )
}

/**
 * One permission, with the part that matters said rather than implied.
 *
 * The prefix is stripped because `android.permission.` is on every line and carries nothing, and
 * the dangerous ones are marked because a reader cannot be expected to know which of sixty names
 * Android treats as dangerous - that is what the inspector is for.
 */
@Composable
private fun PermissionRow(permission: String, dangerous: Boolean) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (dangerous) colors.warn else colors.lineSoft),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            permission.removePrefix("android.permission."),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (dangerous) MaterialTheme.colorScheme.onSurface else colors.fg2,
            modifier = Modifier.weight(1f),
        )
        if (dangerous) Text("dangerous", fontSize = 9.5.sp, color = colors.warn)
    }
}

/**
 * A labelled group of actions.
 *
 * The grouping carries the one distinction a row of equal chips threw away: whether an action
 * produces a file to look at, or reaches out and changes the device.
 */
@Composable
private fun WorkGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(11.dp)
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
            color = colors.fg3,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.sunken)
                .border(1.dp, colors.lineSoft, shape),
            content = content,
        )
    }
}

/**
 * One action: what it is called, and what it does.
 *
 * @param blocked why this cannot run, or null when it can. A blocked row stays visible and says
 *   why in place of its description, rather than disappearing - an action that vanishes leaves
 *   the reader wondering whether the app can do it at all, which is the question this screen
 *   exists to answer.
 */
@Composable
private fun WorkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    blocked: String?,
    onBlocked: (String) -> Unit,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val enabled = blocked == null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (enabled) onClick() else onBlocked(blocked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) colors.accent else colors.fg3.copy(alpha = 0.45f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.5.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else colors.fg3.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                blocked ?: detail,
                fontSize = 10.sp,
                color = if (enabled) colors.fg3 else colors.warn,
                lineHeight = 13.5.sp,
            )
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
