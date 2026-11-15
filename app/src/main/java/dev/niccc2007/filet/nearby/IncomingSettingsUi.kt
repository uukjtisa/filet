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
 * Share › Incoming files: one row under Pop-ups saying how a send asks with Filet closed, and the
 * options together behind it - the mock's "Incoming files" group.
 *
 * Here and not in Settings because Settings has no sharing room: the sharing options all live on
 * the pages they govern (Pop-ups beside this, Portal's on Portal), and a group parked in an
 * unrelated Settings tab is one nobody would look for.
 */
@Composable
internal fun IncomingRow(store: IncomingStore) {
    val s by store.settings.collectAsState()
    val overlay = rememberOverlayAllowed()
    var open by remember { mutableStateOf(false) }
    NRow(
        title = "Incoming files",
        sub = IncomingWords.row(s, overlay),
        lead = NLead.Glyph(FiletIcons.Tune),
        onClick = { open = true },
    )
    if (open) IncomingDialog(store, overlay) { open = false }
}

@Composable
private fun IncomingDialog(store: IncomingStore, overlay: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val s by store.settings.collectAsState()
    Dlg(onClose) {
        DlgHeader(FiletIcons.Download, "Incoming files", sub = "How another Filet's send asks while Filet is closed", onClose = onClose)
        DlgBody {
            DlgTick(
                on = s.ready,
                label = "Ready while Filet is closed",
                sub = "Hears a send with Filet in the background. Its notification sits folded and swipes away.",
            ) { store.setReady(!s.ready) }
            Spacer(Modifier.height(10.dp))
            DlgSection("How a send asks")
            Choices(AskStyle.entries.map { it.label }, s.ask.ordinal) { store.setAsk(AskStyle.entries[it]) }
            Spacer(Modifier.height(10.dp))
            DlgSection("One file asks with")
            Choices(OneFileStyle.entries.map { it.label }, s.oneFile.ordinal) { store.setOneFile(OneFileStyle.entries[it]) }
            Spacer(Modifier.height(10.dp))
            DlgTick(
                on = overlay,
                label = "Show over other apps",
                sub = if (overlay) "Granted. Turn it off in Android's settings." else "Granted once in Android's settings. Without it, a send asks with a notification.",
            ) { openOverlaySettings(context) }
        }
        DlgFooter { DlgBtn("Done", BtnKind.PRIMARY, onClick = onClose) }
    }
}

/** `.seg`: the choices in a row, the one in force lit. */
@Composable
private fun Choices(labels: List<String>, on: Int, pick: (Int) -> Unit) {
    val colors = Filet.colors
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            Text(
                label,
                fontSize = 11.5.sp,
                color = if (i == on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == on) colors.accent else colors.high)
                    .clickable { pick(i) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

/** Whether Filet may draw over other apps, read again each time the screen comes back. */
@Composable
private fun rememberOverlayAllowed(): Boolean {
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
private fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** What the Share page's row says. Pure. */
internal object IncomingWords {
    fun row(s: IncomingSettings, overlay: Boolean): String {
        val how = if (s.ask == AskStyle.NOTICE || !overlay) "a notification" else "a side card"
        val ready = if (s.ready) "Ready while closed" else "Only while Filet is open"
        return "$ready · asks with $how"
    }
}
