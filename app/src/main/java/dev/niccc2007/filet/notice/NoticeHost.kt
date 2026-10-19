package dev.niccc2007.filet.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.niccc2007.filet.BuildConfig
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader

/**
 * Draws whatever [Notices] says is at the front of the queue. Hosted once, above the browser.
 *
 * The update offer keeps its own sheet - it has a download and a reminder row that do not belong
 * in a second copy - so for that kind this only hands over to it and waits for it to close.
 */
@Composable
fun NoticeHost(vm: BrowserViewModel) {
    val head by Notices.head.collectAsState()
    val update by vm.update.collectAsState()
    val ctx = LocalContext.current

    key(head) {
        when (head) {
            NoticeKind.WELCOME -> WelcomeDialog(
                updater = BuildConfig.UPDATER_ENABLED,
                onAbout = {
                    FirstRun.markShown(ctx)
                    Notices.done(NoticeKind.WELCOME)
                    vm.focusedPane()?.openSpecial(PaneKind.ABOUT, "About")
                },
                onDismiss = {
                    FirstRun.markShown(ctx)
                    Notices.done(NoticeKind.WELCOME)
                },
            )

            NoticeKind.UPDATE -> {
                var opened by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    // False when the sheet is already busy with something the user opened, or the
                    // release is gone; the notice is then spent rather than left blocking the queue.
                    if (vm.showQueuedUpdate()) opened = true else Notices.done(NoticeKind.UPDATE)
                }
                LaunchedEffect(opened, update) {
                    if (opened && update == null) Notices.done(NoticeKind.UPDATE)
                }
            }

            null -> Unit
        }
    }
}

/**
 * The first-run welcome: one card, three pointers, dismissed in one tap.
 *
 * Not a tour and not a wall. It appears over a file manager somebody installed to look at files,
 * so it says where the About page is and what lives there, and gets out of the way. The scrim and
 * the back gesture both dismiss it, and either way it never returns.
 */
@Composable
private fun WelcomeDialog(updater: Boolean, onAbout: () -> Unit, onDismiss: () -> Unit) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Info, "Welcome to Filet", sub = "Everything below lives on the About page.", onClose = onDismiss)
        DlgBody {
            DlgAction(
                FiletIcons.Download, "Updates",
                sub = if (updater) "Filet checks for new versions itself and tells you." else "Updates come from where you installed Filet.",
                onClick = onAbout,
            )
            DlgAction(FiletIcons.Warn, "Report a bug", sub = "The source and its issue tracker are linked there.", onClick = onAbout)
            DlgAction(FiletIcons.Heart, "Support Filet", sub = "Free and open source, kept going by people who chip in.", onClick = onAbout)
            DlgCaption("About is in the menu, at the end of the list.")
        }
        DlgFooter {
            DlgBtn("Not now", onClick = onDismiss)
            DlgBtn("Open About", kind = BtnKind.PRIMARY, onClick = onAbout)
        }
    }
}
