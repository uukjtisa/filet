package dev.niccc2007.filet.handlers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Hosts whatever handler is open, over the browser.
 *
 * A viewer is not a destination in a nav graph: it is a layer over the pane you opened it
 * from, so closing it puts you back exactly where you were with your selection intact. That
 * is the behaviour a file manager needs and the one a nav graph makes awkward.
 */
@Composable
fun HandlerHost(vm: BrowserViewModel, content: @Composable () -> Unit) {
    val request by vm.openRequest.collectAsState()
    val chooser by vm.chooserFor.collectAsState()
    val appPick by vm.appPickerFor.collectAsState()
    val update by vm.update.collectAsState()

    Box(Modifier.fillMaxSize()) {
        content()

        val r = request
        if (r != null) {
            BackHandler(enabled = true) { vm.closeHandler() }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (r.handler) {
                    HandlerId.TEXT -> TextEditorScreen(vm, r.node)
                    HandlerId.IMAGE -> ImageViewerScreen(vm, r.node)
                    HandlerId.MEDIA -> MediaScreen(vm, r.node)
                    HandlerId.HEX -> HexViewerScreen(vm, r.node)
                    HandlerId.APK -> dev.niccc2007.filet.apk.ApkInspectorScreen(vm, r.node)
                    HandlerId.MANIFEST -> dev.niccc2007.filet.apk.ManifestEditorScreen(vm, r.node)
                    else -> Unit
                }
            }
        }

        AnimatedVisibility(
            visible = chooser != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            chooser?.let { node -> OpenWithSheet(vm, node) }
        }

        // The second sheet: not "what can Filet do with this" but "which installed app".
        // Two sheets rather than one list, because the questions are different and mixing
        // Filet's own viewers with thirty third-party apps makes both harder to scan.
        AnimatedVisibility(
            visible = appPick != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            appPick?.let { pick ->
                BackHandler(enabled = true) { vm.dismissAppPicker() }
                AppPickerSheet(vm, pick)
            }
        }

        AnimatedVisibility(
            visible = update != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            update?.let { u ->
                BackHandler(enabled = true) { vm.dismissUpdate() }
                dev.niccc2007.filet.update.UpdateSheet(vm, u)
            }
        }
    }
}

/**
 * "Open with", listing what Filet itself can do plus a hand-off to the system chooser.
 *
 * "Always" writes the handler registry (PLAN.md L2) and the next single tap skips this sheet.
 * That is a per-type routing default, not a per-file one.
 */
@Composable
private fun OpenWithSheet(vm: BrowserViewModel, node: dev.niccc2007.filet.vfs.VNode) {
    val colors = Filet.colors
    var chosen by remember(node.path) { mutableStateOf<HandlerId?>(null) }
    var showAll by remember(node.path) { mutableStateOf(false) }
    // Two tiers. Bug identified: this list was built from the extension, so a file the tables
    // did not recognise was offered the code editor, the hex viewer and "another app" and
    // nothing else - a `.mcaddon`, which is a zip, could not be opened with the archive viewer
    // at all. Every viewer is now reachable; the guess just goes first.
    val offer = vm.registry.offerFor(node)
    val candidates = if (showAll) offer.all else offer.likely

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { vm.dismissChooser() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Open with", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${node.name}  ·  ${humanSize(node.size)}",
                        fontSize = 10.5.sp, color = colors.fg3, fontFamily = FontFamily.Monospace,
                    )
                }
                Icon(
                    FiletIcons.Close, "Close", tint = colors.fg2,
                    modifier = Modifier.size(26.dp).clickable { vm.dismissChooser() }.padding(5.dp),
                )
            }
            candidates.forEach { h ->
                val on = h == chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) colors.sel else Color.Transparent)
                        .clickable { chosen = h }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(iconFor(h), null, tint = colors.fg2, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(h.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (on) Icon(FiletIcons.Check, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                }
            }
            if (!showAll && offer.rest.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAll = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(FiletIcons.More, null, tint = colors.fg3, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Every other viewer", fontSize = 13.sp, color = colors.fg2)
                        // Honest about what they are. None of them can damage anything - a
                        // viewer handed a file it cannot read says so and closes - and that is
                        // a better outcome than a file with no way to open it.
                        Text(
                            "These do not match this file's name. One may still open it.",
                            fontSize = 10.sp, color = colors.fg3,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                // "Always" is the one that writes a routing default, so it is not the one
                // wearing the primary colour. Same rule as the app picker's unticked box.
                SheetButton("Always", primary = false, enabled = chosen != null) {
                    chosen?.let { vm.openWith(node, it, remember = true) }
                }
                Spacer(Modifier.width(8.dp))
                SheetButton("Just once", primary = true, enabled = chosen != null) {
                    chosen?.let { vm.openWith(node, it, remember = false) }
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 12.5.sp,
        color = when {
            !enabled -> colors.fg3
            primary -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary && enabled) colors.accent else colors.high)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun iconFor(h: HandlerId) = when (h) {
    HandlerId.TEXT -> FiletIcons.Code
    HandlerId.IMAGE -> FiletIcons.Image
    HandlerId.MEDIA -> FiletIcons.Play
    HandlerId.HEX -> FiletIcons.Hex
    HandlerId.ARCHIVE -> FiletIcons.Zip
    HandlerId.APK -> FiletIcons.Apk
    HandlerId.MANIFEST -> FiletIcons.Code
    HandlerId.EXTERNAL -> FiletIcons.Share
}

/** The bar every viewer wears, so they all close and act the same way. */
@Composable
fun ViewerBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Back, "Close", tint = colors.fg2,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(7.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 9.5.sp, color = colors.fg3, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Filet.colors
    Icon(
        icon, label,
        tint = if (enabled) colors.fg2 else colors.fg3.copy(alpha = 0.4f),
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp),
    )
}
