package dev.niccc2007.filet.docs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Putting a password on a PDF, changing it and taking it off, from wherever the file is: the
 * reader's More menu, and the file list's menu for one PDF.
 *
 * Both run the same rewrite, [PdfDoc.rewrite] into `replaceVerified`, so a lock made from the
 * list is the same lock the reader makes, and a failed save leaves the original as it was
 * either way.
 */
internal enum class PasswordChange(val title: String, val action: String, val done: String) {
    LOCK("Lock with a password", "Lock", "Locked with a password"),
    CHANGE("Change password", "Change", "Password changed"),
    REMOVE("Remove password", "Remove", "Password removed"),
}

/**
 * A new password twice, or a removal confirmed, for a file whose current password is already
 * known: the reader's case, where it was typed to open the file. The file is saved over while
 * this is open, so it cannot be dismissed until that is done, and a failure is shown in it
 * rather than lost.
 */
@Composable
internal fun PasswordChangeDialog(
    what: PasswordChange,
    name: String,
    working: Boolean,
    failed: String?,
    onGo: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val differs = again.isNotEmpty() && again != first
    val ready = what == PasswordChange.REMOVE || (first.isNotEmpty() && again == first)
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(if (what == PasswordChange.CHANGE) FiletIcons.Key else FiletIcons.Lock, what.title, sub = name, onClose = onDismiss)
        DlgBody {
            // One banner: what went wrong when something did, else what this change costs.
            DlgWarn(
                when {
                    failed != null -> failed
                    what == PasswordChange.REMOVE -> REMOVE_COST
                    else -> LOCK_COST
                },
                bad = failed != null,
            )
            if (what != PasswordChange.REMOVE) {
                DlgField("New password", first, { first = it }, password = true, enabled = !working)
                DlgField("Type it again", again, { again = it }, password = true, enabled = !working)
                if (differs) DlgCaption("The two do not match.")
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", enabled = !working, onClick = onDismiss)
            DlgBtn(
                if (working) "Saving" else what.action,
                kind = if (what == PasswordChange.REMOVE) BtnKind.DANGER else BtnKind.PRIMARY,
                enabled = ready && !working,
            ) { onGo(if (what == PasswordChange.REMOVE) null else first) }
        }
    }
}

/**
 * The password a PDF already has, asked for before anything about it can change: the file
 * list's first step on a locked PDF, as typing it to open the file is the reader's. Checked as
 * soon as it is typed, so a wrong one is said here rather than after a new one was chosen.
 */
@Composable
private fun CurrentPasswordDialog(
    name: String,
    checking: Boolean,
    failed: String?,
    onNext: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    // Typed input lives in this dialogue, so a stray tap on the scrim must not throw it away.
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Lock, "PDF password", sub = name, onClose = onDismiss)
        DlgBody {
            DlgWarn(failed ?: "This PDF has a password. Type it first to change it or take it off.", bad = failed != null)
            DlgField("Current password", entry, { entry = it }, password = true, enabled = !checking)
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", enabled = !checking, onClick = onDismiss)
            DlgBtn(if (checking) "Checking" else "Next", kind = BtnKind.PRIMARY, enabled = entry.isNotEmpty() && !checking) {
                onNext(entry)
            }
        }
    }
}

/** Once the password has opened it: change it, or take it off. */
@Composable
private fun ChangeOrRemoveDialog(name: String, onPick: (PasswordChange) -> Unit, onDismiss: () -> Unit) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Key, "PDF password", sub = name, onClose = onDismiss)
        DlgBody {
            DlgAction(FiletIcons.Key, PasswordChange.CHANGE.title, sub = "A new password in place of this one.") {
                onPick(PasswordChange.CHANGE)
            }
            DlgAction(FiletIcons.Lock, PasswordChange.REMOVE.title, sub = "The file opens without one.", danger = true) {
                onPick(PasswordChange.REMOVE)
            }
        }
    }
}

/**
 * The whole job for one PDF picked in the file list, in the reader's order.
 *
 * No password: Lock, a new one typed twice. A password: that one first, checked; then Change or
 * Remove; then the same dialogue the reader shows. Drawn by whoever holds the file list's menu,
 * and gone again through [onDone] once the file is saved or a step is cancelled.
 *
 * Whether it has a password is read when the row is pressed, never when the menu is built,
 * because the menu decides every row from what it already knows - see `selectionActions`.
 */
@Composable
fun PdfPasswordFlow(vm: BrowserViewModel, node: VNode, onDone: () -> Unit) {
    // null while it is being read.
    var locked by remember(node.path) { mutableStateOf<Boolean?>(null) }
    // The password it has, once typed and checked. Null until then on a locked file.
    var current by remember(node.path) { mutableStateOf<String?>(null) }
    var change by remember(node.path) { mutableStateOf<PasswordChange?>(null) }
    var working by remember(node.path) { mutableStateOf(false) }
    var failed by remember(node.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(node.path) {
        when (val has = PdfDoc.isLocked(vm.localCopy, node)) {
            null -> {
                vm.toast("This PDF could not be read.")
                onDone()
            }
            else -> {
                locked = has
                if (!has) change = PasswordChange.LOCK
            }
        }
    }

    val dismiss = {
        if (!working) onDone()
    }

    fun checkCurrent(entry: String) {
        working = true
        failed = null
        scope.launch {
            val r = PdfDoc.checkPassword(vm.localCopy, node, entry)
            working = false
            when (r) {
                PdfUnlock.Result.Unlocked -> current = entry
                PdfUnlock.Result.WrongPassword -> failed = "That password did not open it."
                // Somebody took the lock off since the row was pressed: offer what fits now.
                PdfUnlock.Result.NotLocked -> {
                    locked = false
                    change = PasswordChange.LOCK
                }
                is PdfUnlock.Result.Unsupported -> failed = r.why
            }
        }
    }

    fun save(what: PasswordChange, next: String?) {
        working = true
        failed = null
        scope.launch {
            val r = PdfDoc.rewrite(vm.localCopy, node, current, next) { bytes, verify ->
                vm.replaceVerified(node, bytes, verify)
            }
            working = false
            when (r) {
                is PdfDoc.Rewrote.Done -> {
                    vm.toast(what.done)
                    onDone()
                }
                PdfDoc.Rewrote.WrongPassword -> failed = "The password typed a moment ago no longer opens it."
                is PdfDoc.Rewrote.Failed -> failed = r.why
            }
        }
    }

    val what = change
    when {
        locked == null -> {}
        what != null -> PasswordChangeDialog(
            what = what,
            name = node.name,
            working = working,
            failed = failed,
            onGo = { next -> save(what, next) },
            onDismiss = dismiss,
        )
        current == null -> CurrentPasswordDialog(
            name = node.name,
            checking = working,
            failed = failed,
            onNext = { checkCurrent(it) },
            onDismiss = dismiss,
        )
        else -> ChangeOrRemoveDialog(name = node.name, onPick = { change = it }, onDismiss = dismiss)
    }
}

/**
 * Which PDF the file list asked about, so the selection bar and the long-press menu both open
 * the one flow drawn by [PdfPasswordHost], as Change icon does.
 */
object PdfPasswordAsk {
    private val _asked = MutableStateFlow<VNode?>(null)
    val asked: StateFlow<VNode?> = _asked.asStateFlow()

    /** Open the flow for the one PDF in [nodes]; anything but exactly one PDF file is ignored. */
    fun open(nodes: List<VNode>) {
        val one = nodes.singleOrNull() ?: return
        if (!one.isDir && one.extension.equals("pdf", ignoreCase = true)) _asked.value = one
    }

    internal fun close() {
        _asked.value = null
    }
}

/** Draws [PdfPasswordFlow] for whatever [PdfPasswordAsk] holds, unless a pick is on screen. */
@Composable
fun PdfPasswordHost(vm: BrowserViewModel, hidden: Boolean) {
    val node = PdfPasswordAsk.asked.collectAsState().value ?: return
    if (hidden) return
    PdfPasswordFlow(vm, node, onDone = PdfPasswordAsk::close)
}

private const val REMOVE_COST = "Anyone who has the file will be able to open it."
private const val LOCK_COST = "Without it the file will not open, and there is no way to get it back if it is forgotten."
