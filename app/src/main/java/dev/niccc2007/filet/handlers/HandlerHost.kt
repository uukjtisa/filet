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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
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
                    HandlerId.METADATA ->
                        dev.niccc2007.filet.metadata.MetadataScreen(vm, r.node)
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
            // The merged dialogue, in its open-a-file mode. There used to be a second
            // Open With in Settings with the same list and a different layout; see
            // OpenWith.kt for why one composable does both jobs.
            chooser?.let { node ->
                dev.niccc2007.filet.handlers.OpenWithDialog(
                    vm = vm,
                    file = node,
                    extension = node.extension,
                    onDismiss = vm::dismissChooser,
                )
            }
        }

        // Where a viewer's Share goes. Bug identified: it handed straight to Android's share
        // sheet, so Filet's own network share was the one destination its own button could
        // not reach. Only shown when there is a real choice - see shareTargets.
        val shareFor by vm.shareChoice.collectAsState()
        AnimatedVisibility(
            visible = shareFor != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            shareFor?.let { ask -> ShareChoiceSheet(vm, ask) }
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
 * Where to send a file.
 *
 * Two destinations, and the network one is Filet's own: a link any browser on this network can
 * open, with nothing installed at the other end. Handing the file to another app stays first
 * because it is still right most of the time.
 *
 * Was a hand-rolled scrim and sheet with its own paddings; now on the shared kit, so it agrees
 * with every other dialogue about what a row and a header are.
 */
@Composable
private fun ShareChoiceSheet(vm: BrowserViewModel, ask: BrowserViewModel.ShareAsk) {
    Dlg(onDismiss = vm::dismissShareChoice) {
        DlgHeader(
            icon = FiletIcons.Share,
            title = "Share ${ask.title}",
            sub = if (ask.items.size > 1) "${ask.items.size} files" else null,
            onClose = vm::dismissShareChoice,
        )
        DlgBody {
            for (target in ask.targets) {
                DlgPick(
                    icon = if (target == ShareTarget.NETWORK) FiletIcons.Wifi else FiletIcons.Share,
                    label = target.label,
                    detail = target.detail,
                    ours = target == ShareTarget.NETWORK,
                    tag = if (target == ShareTarget.NETWORK) "Filet" else null,
                ) { vm.shareVia(target) }
            }
        }
    }
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
            // A gradient, not a filled strip.
            //
            // The bar floats over the picture now rather than sitting above it, and a solid panel
            // laid on a photograph reads as a lid on it. A scrim that is dense behind the text and
            // gone by the bottom edge keeps the words legible over anything - a white sky, a dark
            // frame - without drawing a shape of its own. The same reason a video player's top
            // chrome is a gradient everywhere it is done well.
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    0.65f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                    1f to androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            // Below the status bar rather than under it, now that the viewer draws behind the
            // system bars. Without this the title sits in the clock.
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Back, "Close", tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(7.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                maxLines = 1,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 9.5.sp, color = colors.fg2, maxLines = 1)
            }
        }
        actions()
    }
}

@Composable
fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Filet.colors
    Icon(
        icon, label,
        // Brighter than the old fg2: these sit over a photograph now, not over a panel.
        tint = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3.copy(alpha = 0.4f),
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp),
    )
}
