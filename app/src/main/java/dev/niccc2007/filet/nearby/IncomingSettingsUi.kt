package dev.niccc2007.filet.nearby

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.tabs.NLead
import dev.niccc2007.filet.ui.tabs.NRow
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Share > Pop-ups and incoming files: one row saying how a send asks, which opens Settings >
 * Activity > Nearby on Pop-ups. The options themselves live there, beside the other things that
 * pop up; see the decision at that group.
 */
@Composable
internal fun IncomingRow(vm: dev.niccc2007.filet.browser.BrowserViewModel) {
    val s by vm.nearby.incoming.settings.collectAsState()
    val popups by vm.nearby.inbox.popups.collectAsState()
    val overlay = rememberOverlayAllowed()
    NRow(
        title = "Pop-ups and incoming files",
        sub = IncomingWords.row(s, overlay, popups),
        lead = NLead.Glyph(FiletIcons.Tune),
        onClick = {
            dev.niccc2007.filet.settings.SettingsJump.request(PushInbox.KEY_POPUPS)
            vm.focusedPane()?.openSpecial(dev.niccc2007.filet.browser.PaneKind.SETTINGS, "Settings")
        },
    )
}

/** Whether Filet may draw over other apps, read again each time the screen comes back. */
@Composable
internal fun rememberOverlayAllowed(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(canOverlay(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = canOverlay(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

private fun canOverlay(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

/** Android's own page for Filet's "display over other apps"; there is no asking for it in place. */
internal fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** What the Share page's row says. Pure. */
internal object IncomingWords {
    fun row(s: IncomingSettings, overlay: Boolean, popups: Popups = Popups.ON): String {
        if (popups != Popups.ON) return "Pop-ups off · sends wait here"
        val how = when {
            s.ask == AskStyle.NOTICE || !overlay -> "a notification"
            s.ask == AskStyle.DIALOG -> "the full dialog"
            else -> "a side card"
        }
        val ready = if (s.ready) "Ready while closed" else "Only while Filet is open"
        return "$ready · asks with $how"
    }
}
