package dev.niccc2007.filet.remotes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgNote
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * Adding or editing a network place.
 *
 * ## The shape, decided once
 *
 * **A place is an address plus a credential.** That is the whole model, and the form is those two
 * things, in that order, with a name after them.
 *
 * The address is **one field**. `http://192.168.1.9:8321/a/KTR9` is what the other end hands out,
 * and a host, a port and a path are not three separate questions - they are parts of one string.
 * Splitting them across boxes, and then filing the port under "more options", is how this form
 * became incoherent: a port is not optional, it is part of where the thing is.
 *
 * Everything under **Advanced** is genuinely optional - a start folder, a timeout, read-only, an
 * SSH key. If leaving it blank stops the place from working, it is not advanced and it belongs
 * above.
 *
 * ## What this replaced
 *
 * Three ways of saying the same thing, on screen at once: a paste box, a full set of host, port
 * and path fields, and an advanced section holding the port a second time - plus a state machine
 * deciding which of them to show, which latched open on the first keystroke and never closed.
 */
@Composable
fun ConnectionDialog(
    initial: NetConnection,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (NetConnection) -> Unit,
) {
    var kind by remember { mutableStateOf(RemoteKinds.kindFor(initial)) }
    // The chooser is for a place with nothing in it yet. A discovered host arrives with its
    // address already known, and asking "what are you connecting to" about something that has
    // just announced itself would be a step backwards.
    var chosen by remember { mutableStateOf(!isNew || initial.host.isNotBlank()) }

    if (!chosen) {
        KindChooser(onPick = { kind = it; chosen = true }, onDismiss = onDismiss)
        return
    }

    ConnectionFields(
        initial = initial,
        kind = kind,
        isNew = isNew,
        onBack = if (isNew && initial.host.isBlank()) ({ chosen = false }) else null,
        onDismiss = onDismiss,
        onDelete = onDelete,
        onSave = onSave,
    )
}

/** Step one: what is at the other end. */
@Composable
private fun KindChooser(onPick: (RemoteKind) -> Unit, onDismiss: () -> Unit) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Link,
            title = "Add a place",
            sub = "What are you connecting to?",
            onClose = onDismiss,
        )
        DlgBody {
            for (k in RemoteKind.entries) {
                DlgPick(
                    icon = iconForKind(k),
                    label = k.title,
                    detail = k.blurb,
                    onClick = { onPick(k) },
                )
            }
            Spacer(Modifier.height(6.dp))
            DlgNote(
                "Looking for another phone?",
                "Phones on the same Wi-Fi with hosting turned on appear by themselves at the " +
                    "top of the Remotes screen. Adding one by hand is only needed if it is on a " +
                    "different network.",
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
        }
    }
}

@Composable
private fun ConnectionFields(
    initial: NetConnection,
    kind: RemoteKind,
    isNew: Boolean,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (NetConnection) -> Unit,
) {
    val prefilled = initial.host.isNotBlank()
    val spec = RemoteKinds.specFor(kind)

    // The address, as one string. Composed from whatever is already known, which is how a
    // discovered host arrives with its box filled in.
    var address by remember {
        mutableStateOf(
            if (prefilled) {
                RemoteAddress.compose(
                    initial.protocol,
                    initial.host,
                    if (initial.port > 0) initial.port else spec.defaultPort,
                    initial.share,
                    initial.useTls,
                    // Discovery knows the prefix but never the code, so the address arrives
                    // ending at `a/` with the cursor's work left to do. Without the trailing
                    // slash it reads as a finished path called "a".
                    trailingSlash = RemoteKinds.kindFor(initial) == RemoteKind.FILET_PHONE &&
                        RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, initial.share).isBlank(),
                )
            } else {
                ""
            },
        )
    }

    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var domain by remember { mutableStateOf(initial.domain) }
    var anonymous by remember {
        mutableStateOf(if (isNew && !prefilled) !spec.needsCredentials else initial.anonymous)
    }
    var label by remember { mutableStateOf(initial.label) }
    var others by remember {
        mutableStateOf(
            dev.niccc2007.filet.vfs.provider.net.Endpoints.format(initial.altHosts),
        )
    }

    var startPath by remember { mutableStateOf(initial.startPath) }
    var readOnly by remember { mutableStateOf(initial.readOnly) }
    var passive by remember { mutableStateOf(initial.passive) }
    var timeout by remember { mutableStateOf(initial.timeoutSeconds.toString()) }
    var privateKey by remember { mutableStateOf(initial.privateKey) }
    var keyPass by remember { mutableStateOf(initial.keyPassphrase) }
    var advanced by remember { mutableStateOf(false) }

    val parsed = RemoteAddress.parse(address)
    val protocol = parsed?.protocol
        ?: initial.protocol.takeIf { prefilled }
        ?: spec.protocol

    // The address IS the whole thing, including the code. There is no second box holding part
    // of it: `http://phone:8321/a/KTR9` is one value, and splitting the code out meant managing
    // the same thing in two places and keeping them in step by hand.
    val sharePath = parsed?.share.orEmpty()

    // Discovery can fill in everything except the code, so the prefilled address ends at `a/`.
    // That is not connectable, and saving it produces a place that opens an empty folder.
    val phoneNeedsCode = kind == RemoteKind.FILET_PHONE &&
        RemoteKinds.codeFromShare(kind, sharePath).isBlank()

    val canSave = parsed != null && parsed.usable && !phoneNeedsCode &&
        (protocol != NetProtocol.SMB || sharePath.isNotBlank())

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = iconForKind(kind),
            title = if (isNew) kind.title else initial.label.ifBlank { kind.title },
            sub = protocol.label,
            onClose = onDismiss,
        )
        DlgBody {
            if (spec.setupHint.isNotBlank() && isNew) {
                // Shut. Worth having once, and not about the state of this form, so it does
                // not earn a banner - see DlgWarn for the one-per-surface rule.
                DlgNote("First, on the other device", spec.setupHint)
            }

            DlgSection("Address")
            DlgField("", address, { address = it }, hint = addressHintFor(kind), tight = true)
            // ONE banner on this surface, and only for the thing standing between the reader
            // and a working place. There were four here, each short enough to pass the character
            // budget, and together they read as a wall of yellow boxes that all look like faults.
            if (phoneNeedsCode && address.isNotBlank()) {
                DlgWarn("Add the access code on the end.")
                Spacer(Modifier.height(8.dp))
                DlgNote(
                    "Where to find it",
                    "The four characters the other phone shows on its hosting card. It is never " +
                        "broadcast, which is why discovery cannot fill it in.",
                )
            } else if (address.isNotBlank()) {
                // Feedback on what was typed - not a warning and not news. A quiet line under
                // the field, where a hint would sit.
                DlgCaption(
                    if (parsed != null && parsed.usable) {
                        parsed.host +
                            (parsed.port?.let { ":" + it } ?: "") +
                            (if (parsed.share.isNotEmpty()) "  ·  " + parsed.share else "")
                    } else {
                        "Not an address yet"
                    },
                )
            }

            // No separate credential for a Filet phone: its code lives in the address above,
            // and a second box for part of one value is two things to keep in step.
            if (kind == RemoteKind.FILET_PHONE) {
                Unit
            } else if (spec.needsCredentials) {
                DlgSection("Signing in")
                DlgTick(
                    on = anonymous,
                    label = "No account needed",
                    sub = "Guest access, if the other device allows it.",
                ) { anonymous = !anonymous }
                if (!anonymous) {
                    Spacer(Modifier.height(10.dp))
                    DlgField("User", user, { user = it })
                    DlgField("Password", password, { password = it }, password = true)
                    if (protocol == NetProtocol.SMB) {
                        // A domain-joined NAS refuses a correct username without this, and the
                        // error it gives back is an ordinary access denied.
                        DlgField("Domain or workgroup", domain, { domain = it })
                    }
                }
            }

            DlgSection("Name")
            // Labelled, because it was not. The field was here all along with an empty label
            // and the hint "Optional", so the storage card kept showing the access code and the
            // name set here was never visible anywhere.
            DlgField(
                "What to call it",
                label,
                { label = it },
                hint = parsed?.host ?: "Sarah's tablet",
                tight = true,
            )
            DlgCaption("Shown on the storage card and in the remotes list.")

            DlgSection("Other addresses")
            DlgField(
                "",
                others,
                { others = it },
                hint = "192.168.43.1\ntablet.local",
                singleLine = false,
                tight = true,
            )
            DlgCaption(
                "One per line. The same device on another network - a hotspot, a second " +
                    "Wi-Fi. Filet uses whichever answers and remembers it.",
            )

            Spacer(Modifier.height(10.dp))
            DlgTick(
                on = advanced,
                label = "Advanced",
                // Everything in here is genuinely optional. Anything whose absence stops the
                // place from working lives above - which is why the port is not here.
                sub = "Start folder, timeout, read only" +
                    if (protocol == NetProtocol.SFTP) ", SSH key" else "",
            ) { advanced = !advanced }

            if (advanced) {
                Spacer(Modifier.height(10.dp))
                DlgField(
                    "Open at",
                    startPath,
                    { startPath = it },
                    hint = "A folder inside, or blank for the top",
                )
                DlgField(
                    "Timeout, seconds",
                    timeout,
                    { timeout = it.filter(Char::isDigit).take(3) },
                    numeric = true,
                )
                Spacer(Modifier.height(4.dp))
                DlgTick(
                    on = readOnly,
                    label = "Read only",
                    sub = "Filet refuses writes. The server still decides what is really allowed.",
                ) { readOnly = !readOnly }
                if (protocol == NetProtocol.FTP) {
                    Spacer(Modifier.height(8.dp))
                    DlgTick(
                        on = passive,
                        label = "Passive mode",
                        sub = "Leave on behind NAT. Active mode asks the server to connect back.",
                    ) { passive = !passive }
                }
                if (protocol == NetProtocol.SFTP) {
                    Spacer(Modifier.height(10.dp))
                    DlgField(
                        "Private key",
                        privateKey,
                        { privateKey = it },
                        hint = "Paste an OpenSSH or PEM key to use instead of a password",
                        singleLine = false,
                    )
                    if (privateKey.isNotBlank()) {
                        DlgField("Key passphrase", keyPass, { keyPass = it }, password = true)
                    }
                }
            }

            // The honest note. Shut: it is true of the PROTOCOL rather than of anything the
            // reader is in the middle of, and it is the same sentence every time.
            if (parsed != null && parsed.usable) {
                Spacer(Modifier.height(10.dp))
                val tls = parsed.tls
                DlgNote(
                    if (tls) "About this connection" else "Worth knowing",
                    when (protocol) {
                        NetProtocol.FTP ->
                            if (tls) "FTPS encrypts the connection."
                            else "Plain FTP sends your password in clear text over the network."
                        NetProtocol.SFTP ->
                            "Host key not pinned - a hostile network could impersonate this server."
                        NetProtocol.WEBDAV ->
                            if (tls) "HTTPS encrypts the connection."
                            else "Plain HTTP sends your password in clear text."
                        NetProtocol.SMB -> "SMB2 and SMB3 only. SMB1 is disabled."
                    },
                )
            }
        }
        DlgFooter {
            if (!isNew) DlgBtn("Delete", kind = BtnKind.DANGER, onClick = onDelete)
            if (onBack != null) DlgBtn("Back", onClick = onBack)
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                "Save",
                kind = BtnKind.PRIMARY,
                enabled = canSave,
                onClick = {
                    val p = parsed ?: return@DlgBtn
                    onSave(
                        initial.copy(
                            protocol = protocol,
                            label = RemoteKinds.nameFor(label, p.host, kind),
                            host = p.host,
                            // Typed alternates. Anything discovery has learned is already in
                            // here and round-trips through the same field, so editing the
                            // entry never silently throws a learned address away.
                            altHosts = dev.niccc2007.filet.vfs.provider.net.Endpoints
                                .parse(others)
                                .filterNot { it.equals(p.host, ignoreCase = true) },
                            port = p.port ?: RemoteKinds.defaultPortFor(protocol),
                            user = if (p.user.isNotBlank()) p.user else user.trim(),
                            password = password,
                            share = sharePath,
                            domain = domain.trim(),
                            anonymous = anonymous,
                            useTls = p.tls,
                            startPath = startPath.trim(),
                            readOnly = readOnly,
                            passive = passive,
                            timeoutSeconds = timeout.toIntOrNull()?.coerceIn(3, 120) ?: 15,
                            privateKey = privateKey.trim(),
                            keyPassphrase = keyPass,
                        ),
                    )
                },
            )
        }
    }
}

/** The shape this kind's address takes, shown in the box until something is typed. */
private fun addressHintFor(kind: RemoteKind): String = when (kind) {
    RemoteKind.WINDOWS_PC -> "\\\\DESKTOP-PC\\Shared"
    RemoteKind.NAS -> "\\\\192.168.1.20\\media"
    RemoteKind.FILET_PHONE -> "http://192.168.1.9:8321/a/"
    RemoteKind.FILET_DESKTOP -> "http://192.168.1.5:8322/pc"
    RemoteKind.MAC_OR_LINUX -> "sftp://192.168.1.7/"
    RemoteKind.OTHER -> "http://192.168.1.5/path"
}

private fun iconForKind(k: RemoteKind) = when (k) {
    RemoteKind.WINDOWS_PC -> FiletIcons.Device
    RemoteKind.FILET_DESKTOP -> FiletIcons.Device
    RemoteKind.FILET_PHONE -> FiletIcons.Wifi
    RemoteKind.NAS -> FiletIcons.Device
    RemoteKind.MAC_OR_LINUX -> FiletIcons.Terminal
    RemoteKind.OTHER -> FiletIcons.Link
}
