package dev.niccc2007.filet.locker

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgNote
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.settings.SmallButton
import dev.niccc2007.filet.settings.ToggleRow
import dev.niccc2007.filet.ui.theme.Filet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shortest password the Locker takes. Short enough to type, long enough not to be a PIN. */
const val LOCKER_MIN_PASSWORD = 6

/**
 * What a pane in the Locker draws while the Locker is not open: the set-up form the first time,
 * the unlock form after. In the pane itself rather than a dialogue over it, so a locked tab looks
 * locked - nothing of the folder behind it is drawn, not even its count.
 */
@Composable
fun LockerGate(vm: BrowserViewModel) {
    val colors = Filet.colors
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 440.dp).padding(horizontal = 20.dp, vertical = 28.dp)) {
            Icon(FiletIcons.Lock, null, tint = colors.accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            // Read again after an erase, which moves the revision.
            val rev by vm.locker.revision.collectAsState()
            val health = remember(rev) { vm.locker.health() }
            Text(
                when (health) {
                    LockerVault.Health.OK -> "The Locker is locked"
                    LockerVault.Health.NONE -> "Set up the Locker"
                    LockerVault.Health.KEY_MISSING -> "The Locker's key is missing"
                },
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (health) {
                    LockerVault.Health.OK -> "Its files are sealed with your password."
                    LockerVault.Health.NONE -> "Files moved here are sealed with your password, and leave only when you release them."
                    LockerVault.Health.KEY_MISSING -> "Its sealed files are still here, but the file that opens them is gone."
                },
                fontSize = 13.sp, color = colors.fg2,
            )
            Spacer(Modifier.height(16.dp))
            when (health) {
                LockerVault.Health.OK -> LockerUnlockForm(vm)
                LockerVault.Health.NONE -> LockerSetupForm(vm)
                LockerVault.Health.KEY_MISSING -> LockerKeyMissing(vm)
            }
        }
    }
}

/**
 * Sealed files with no key to open them. Said plainly, and nothing is done about it on its own:
 * a new Locker would orphan them for good, so the only way on is to erase them, by typing a word.
 */
@Composable
private fun LockerKeyMissing(vm: BrowserViewModel) {
    val colors = Filet.colors
    var typed by remember { mutableStateOf("") }
    val n = remember { vm.locker.sealedCount() }
    DlgWarn(
        "$n sealed file${if (n == 1) " is" else "s are"} here. Without the key file nothing can open " +
            "${if (n == 1) "it" else "them"}, and a new Locker set up over ${if (n == 1) "it" else "them"} " +
            "would lose ${if (n == 1) "it" else "them"} for good, so Filet will not make one on its own.",
        bad = true,
    )
    Text(
        "Type \"${LockerVault.ERASE_WORD}\" to erase them and start again.",
        fontSize = 12.sp, color = colors.fg2, modifier = Modifier.padding(bottom = 10.dp),
    )
    DlgField("Type ${LockerVault.ERASE_WORD}", typed, { typed = it })
    Row(Modifier.fillMaxWidth()) {
        DlgBtn("Erase the sealed files", kind = BtnKind.PRIMARY, enabled = LockerVault.eraseConfirmed(typed)) {
            typed = ""
            vm.eraseLocker()
        }
    }
}

/** The first visit: a password twice, and what a rooted phone and a lost password mean. */
@Composable
fun LockerSetupForm(vm: BrowserViewModel, onCancel: (() -> Unit)? = null) {
    val colors = Filet.colors
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rooted = remember { LockerVault.rooted() }
    // A standing fact about the phone, so a note; the one banner is the password that cannot be
    // got back, which is the thing between the reader and losing the files.
    if (rooted) {
        DlgNote(
            "This phone is rooted",
            "Anything with root can read Filet's own storage, so here the Locker is only as strong as its " +
                "password. Choose a long one.",
        )
    }
    DlgField("Password", pw, { pw = it; error = null }, password = true)
    DlgField("Password again", again, { again = it; error = null }, password = true)
    DlgWarn("Forget the password and the files in the Locker cannot be got back. Filet keeps no copy of it.", bad = true)
    val problem = when {
        pw.isNotEmpty() && pw.length < LOCKER_MIN_PASSWORD -> "At least $LOCKER_MIN_PASSWORD characters."
        again.isNotEmpty() && again != pw -> "The two do not match."
        else -> error
    }
    problem?.let { Text(it, fontSize = 12.sp, color = colors.bad, modifier = Modifier.padding(bottom = 10.dp)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onCancel != null) DlgBtn("Cancel", onClick = onCancel)
        DlgBtn(
            if (busy) "Sealing…" else "Set up",
            kind = BtnKind.PRIMARY,
            enabled = !busy && pw.length >= LOCKER_MIN_PASSWORD && pw == again,
        ) {
            busy = true
            vm.makeLocker(pw.toCharArray()) { e ->
                busy = false
                error = e
                if (e == null) { pw = ""; again = "" }
            }
        }
    }
}

/** The password, and the fingerprint when it has been turned on. */
@Composable
fun LockerUnlockForm(vm: BrowserViewModel, onCancel: (() -> Unit)? = null) {
    val colors = Filet.colors
    val context = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val bio = remember { LockerBio.ready(context, vm.locker) }
    DlgField("Password", pw, { pw = it; wrong = false }, password = true)
    if (wrong) {
        Text(
            when (vm.locker.failure) {
                LockerVault.Failure.KEY_DAMAGED -> "The Locker's key file is damaged, and so is its copy, so no password can be checked."
                else -> "That is not the password."
            },
            fontSize = 12.sp, color = colors.bad, modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onCancel != null) DlgBtn("Cancel", onClick = onCancel)
        if (bio) {
            DlgBtn("Fingerprint") {
                LockerBio.unlock(context, vm.locker, onKey = vm::unlockLockerWith, onError = vm::toast)
            }
        }
        DlgBtn(if (busy) "Opening…" else "Unlock", kind = BtnKind.PRIMARY, enabled = !busy && pw.isNotEmpty()) {
            busy = true
            vm.unlockLocker(pw.toCharArray()) { ok ->
                busy = false
                wrong = !ok
                if (ok) pw = ""
            }
        }
    }
}

/**
 * Over the screen when something is to be moved into a locked Locker: the same forms, in a
 * dialogue, since the pane on screen is wherever the files are and not the Locker.
 */
@Composable
fun LockerAskDialog(vm: BrowserViewModel) {
    val asked by vm.lockerAsk.collectAsState()
    if (!asked) return
    val made = vm.locker.made
    Dlg(onDismiss = vm::dismissLockerAsk, dismissOnScrim = false) {
        DlgHeader(
            FiletIcons.Lock,
            if (made) "Open the Locker" else "Set up the Locker",
            sub = "To move the selection into it.",
            onClose = vm::dismissLockerAsk,
        )
        DlgBody {
            if (made) LockerUnlockForm(vm, onCancel = vm::dismissLockerAsk) else LockerSetupForm(vm, onCancel = vm::dismissLockerAsk)
        }
    }
}

/**
 * Along the top of a pane in the open Locker: what is in it, its history, and Lock.
 */
@Composable
fun LockerStrip(vm: BrowserViewModel) {
    val colors = Filet.colors
    val rev by vm.locker.revision.collectAsState()
    val (count, bytes) = remember(rev) {
        runCatching { vm.locker.read { i -> i.files().size to i.bytes() } }.getOrDefault(0 to 0L)
    }
    var history by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Lock, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Locker · $count file${if (count == 1) "" else "s"} · ${humanSize(bytes)}",
            fontSize = 12.sp, color = colors.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StripButton("History") { history = true }
        StripButton("Lock") { vm.lockLocker() }
    }
    if (history) LockerHistoryDialog(vm) { history = false }
}

@Composable
private fun StripButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = Filet.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** What went in and what came out, newest first. Kept inside the sealed index, never anywhere else. */
@Composable
fun LockerHistoryDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val events = remember { runCatching { vm.locker.read { it.history() } }.getOrDefault(emptyList()) }
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Lock, "Locker history", sub = "Added and released, newest first.", onClose = onDismiss)
        DlgBody {
            if (events.isEmpty()) {
                Text("Nothing yet.", fontSize = 13.sp, color = colors.fg3)
            }
            for (e in events) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        if (e.released) "Released" else "Added",
                        fontSize = 11.sp,
                        color = if (e.released) colors.good else colors.accent,
                        modifier = Modifier.width(66.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (e.released) "to " else "from ") + e.place + " · " + fmt.format(Date(e.at)),
                            fontSize = 11.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        DlgFooter { DlgBtn("Close", onClick = onDismiss) }
    }
}

/** The Locker's tile among the drive cards on Home: locked, open, or not set up. */
@Composable
fun LockerTile(
    vm: BrowserViewModel,
    editing: Boolean,
    dimmed: Boolean,
    bar: Boolean,
    onHide: () -> Unit,
) {
    val open by vm.locker.open.collectAsState()
    val pending by vm.locker.pending.collectAsState()
    val rev by vm.locker.revision.collectAsState()
    val count = remember(rev, open) { if (open) runCatching { vm.locker.read { it.files().size } }.getOrNull() else null }
    val health = remember(rev, open) { vm.locker.health() }
    dev.niccc2007.filet.ui.tabs.StorageTile(
        icon = FiletIcons.Lock,
        kind = "Locker",
        value = when {
            open -> "Open"
            health == LockerVault.Health.OK -> "Locked"
            health == LockerVault.Health.KEY_MISSING -> "Key missing"
            else -> "Set up"
        },
        caption = when {
            // Bug identified here: a lock waiting on a copy left the card reading Open, as though
            // the button had done nothing.
            open && pending -> "Locks when the copy finishes"
            open -> "${count ?: 0} file${if (count == 1) "" else "s"} · locks when you leave"
            health == LockerVault.Health.OK -> "Files sealed with your password"
            health == LockerVault.Health.KEY_MISSING -> "Its sealed files cannot be opened"
            else -> "Keep files sealed with a password"
        },
        onHide = onHide,
        editing = editing,
        dimmed = dimmed,
        bar = bar,
        onClick = vm::openLocker,
    )
}

/**
 * Settings › Storage › Locker: when it locks, the fingerprint, the password. In the Settings
 * page's own rows, so it reads as one more section rather than a dialogue pasted into the page.
 */
@Composable
fun LockerSettings(vm: BrowserViewModel) {
    val colors = Filet.colors
    val context = LocalContext.current
    val onLeave by vm.prefs.lockerLockOnLeave.collectAsState()
    val inBackground by vm.prefs.lockerLockInBackground.collectAsState()
    val open by vm.locker.open.collectAsState()
    var change by remember { mutableStateOf(false) }
    var bioOn by remember { mutableStateOf(vm.locker.bio() != null) }
    val rooted = remember { LockerVault.rooted() }
    Column(Modifier.fillMaxWidth()) {
        ToggleRow(
            "Lock when you leave the Locker",
            "Going to another tab or folder locks it, and coming back asks for the password again.",
            onLeave,
            key = Prefs.K_LOCKER_LOCK_ON_LEAVE,
        ) { vm.prefs.setLockerLockOnLeave(it) }
        ToggleRow(
            "Lock when Filet is put away",
            "Going Home, to another app, or the screen turning off locks it.",
            inBackground,
            key = Prefs.K_LOCKER_LOCK_IN_BACKGROUND,
        ) { vm.prefs.setLockerLockInBackground(it) }
        if (LockerBio.supported() && vm.locker.made) {
            ToggleRow(
                "Unlock with fingerprint",
                "The password still opens it, and is needed again if the phone's fingerprints change.",
                bioOn,
                status = if (!open && !bioOn) "Open the Locker to turn this on." else null,
            ) { want ->
                when {
                    !want -> { LockerBio.disable(vm.locker); bioOn = false }
                    open -> LockerBio.enable(context, vm.locker, onDone = { bioOn = true }, onError = vm::toast)
                    else -> vm.toast("Open the Locker first, then turn this on.")
                }
            }
        }
        if (vm.locker.made) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                SmallButton("Change password") { change = true }
            }
        }
        if (rooted) {
            Text(
                "Rooted phone: root can read Filet's storage, so the Locker is only as strong as its password.",
                fontSize = 12.sp, color = colors.warn,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
    if (change) ChangePasswordDialog(vm) { change = false }
}

@Composable
private fun ChangePasswordDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val colors = Filet.colors
    var old by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Key, "Change the Locker's password", sub = "The files stay as they are.", onClose = onDismiss)
        DlgBody {
            DlgField("Password now", old, { old = it; wrong = false }, password = true)
            DlgField("New password", pw, { pw = it }, password = true)
            DlgField("New password again", again, { again = it }, password = true)
            if (wrong) Text("The password now is not right.", fontSize = 12.sp, color = colors.bad)
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Change", kind = BtnKind.PRIMARY, enabled = old.isNotEmpty() && pw.length >= LOCKER_MIN_PASSWORD && pw == again) {
                if (vm.locker.changePassword(old.toCharArray(), pw.toCharArray())) {
                    vm.toast("Password changed.")
                    onDismiss()
                } else {
                    wrong = true
                }
            }
        }
    }
}
