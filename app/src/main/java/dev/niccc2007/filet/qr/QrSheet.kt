package dev.niccc2007.filet.qr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.theme.Filet

/** The words a code's kind is called by, for the dialog title and the viewer's pill. */
fun QrContent.kindLabel(): String = when (this) {
    is QrContent.Link -> "Link"
    is QrContent.Wifi -> "Wi-Fi network"
    is QrContent.Phone -> "Phone number"
    is QrContent.Sms -> "Text message"
    is QrContent.Email -> "Email"
    is QrContent.Geo -> "Place"
    is QrContent.Contact -> "Contact"
    is QrContent.Event -> "Calendar event"
    is QrContent.Payment -> "Payment code"
    is QrContent.OneTimeCode -> "Two-factor setup"
    is QrContent.AuthTransfer -> "Authenticator export"
    is QrContent.Passkey -> "Passkey sign-in"
    is QrContent.WifiDevice -> "Wi-Fi device setup"
    is QrContent.Esim -> "eSIM"
    is QrContent.Crypto -> "Crypto payment"
    is QrContent.Text -> "Text"
}

private fun QrContent.icon(): ImageVector = when (this) {
    is QrContent.Link -> FiletIcons.Link
    is QrContent.Wifi -> FiletIcons.Wifi
    is QrContent.Phone, is QrContent.Sms -> FiletIcons.Device
    is QrContent.Email -> FiletIcons.Share
    is QrContent.Geo -> FiletIcons.Pin
    is QrContent.Contact -> FiletIcons.Star
    is QrContent.Event -> FiletIcons.Clock
    is QrContent.Payment -> FiletIcons.Lock
    is QrContent.OneTimeCode, is QrContent.AuthTransfer -> FiletIcons.Key
    is QrContent.Passkey -> FiletIcons.Shield
    is QrContent.WifiDevice -> FiletIcons.Wifi
    is QrContent.Esim -> FiletIcons.Device
    is QrContent.Crypto -> FiletIcons.Hex
    is QrContent.Text -> FiletIcons.Doc
}

/**
 * What a code turned out to be, and what can be done with it - never done before this is seen.
 * See [QrContent] for why.
 *
 * @param fromPicture true when the code came from a photo rather than the camera, which changes
 *   what a payment app can be told to do with it.
 */
@Composable
fun QrDialog(content: QrContent, vm: BrowserViewModel, fromPicture: Boolean, onClose: () -> Unit) {
    var plain by remember { mutableStateOf(content is QrContent.Text) }
    val ctx = LocalContext.current
    val go: (Intent) -> Unit = { i -> onClose(); vm.onIntent?.invoke(i) }
    val copy: (String, String) -> Unit = { text, what -> vm.copyText(text, "$what copied") }
    // The code itself, handed to whichever app on the phone answers its scheme - or a sentence
    // saying which kind of app it needed, rather than the general "nothing can do that".
    val handOff: (String) -> Unit = { missing ->
        onClose()
        if (!ctx.startFirst(Intent(Intent.ACTION_VIEW, Uri.parse(content.raw)))) vm.toast(missing)
    }
    val warn = content is QrContent.Payment && !content.checksumOk ||
        content is QrContent.Link && QrParse.linkWarnings(content.url).isNotEmpty()

    Dlg(onDismiss = onClose) {
        DlgHeader(
            icon = content.icon(),
            title = content.kindLabel(),
            sub = summary(content),
            tone = if (warn) DlgTone.WARN else DlgTone.NORMAL,
            onClose = onClose,
        )
        DlgBody {
            Facts(content) { label, value -> copy(value, label) }
            if (plain) RawText(content.raw)

            when (content) {
                is QrContent.Link -> {
                    DlgPick(FiletIcons.Open, "Open link", detail = host(content.url)) { onClose(); vm.openUrl(content.url) }
                    DlgPick(FiletIcons.Copy, "Copy link") { copy(content.url, "Link") }
                }
                is QrContent.Wifi -> {
                    DlgPick(FiletIcons.Wifi, "Add this network", detail = "Android asks before saving it") {
                        val suggest = wifiIntent(content)
                        if (suggest != null) {
                            onClose()
                            vm.addWifiNetwork(suggest, content.ssid, content.password)
                        } else {
                            // Copied BEFORE leaving for Settings, not after: once the Settings
                            // screen is in front, a toast from here is drawn under it or not at
                            // all, and the password is the one thing that screen will ask for.
                            if (content.password.isNotEmpty()) vm.copySecret(content.password, "Password copied")
                            go(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    }
                    if (content.password.isNotEmpty()) DlgPick(FiletIcons.Key, "Copy password") { vm.copySecret(content.password, "Password copied") }
                }
                is QrContent.Phone -> {
                    DlgPick(FiletIcons.Device, "Call", detail = "Opens the dialler; nothing is dialled until you press call") {
                        go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(content.number))))
                    }
                    DlgPick(FiletIcons.Copy, "Copy number") { copy(content.number, "Number") }
                }
                is QrContent.Sms -> {
                    DlgPick(FiletIcons.Device, "Write the message", detail = "Nothing is sent until you send it") {
                        go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(content.number))).putExtra("sms_body", content.body))
                    }
                    DlgPick(FiletIcons.Copy, "Copy number") { copy(content.number, "Number") }
                }
                is QrContent.Email -> {
                    DlgPick(FiletIcons.Share, "Write the email") {
                        go(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + content.to))
                                .putExtra(Intent.EXTRA_SUBJECT, content.subject)
                                .putExtra(Intent.EXTRA_TEXT, content.body),
                        )
                    }
                    DlgPick(FiletIcons.Copy, "Copy address") { copy(content.to, "Address") }
                }
                is QrContent.Geo -> {
                    DlgPick(FiletIcons.Pin, "Open in maps") {
                        val q = if (content.query.isNotEmpty()) "?q=" + Uri.encode(content.query) else ""
                        go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${content.lat},${content.lon}$q")))
                    }
                    DlgPick(FiletIcons.Copy, "Copy coordinates") { copy("${content.lat}, ${content.lon}", "Coordinates") }
                }
                is QrContent.Contact -> {
                    DlgPick(FiletIcons.Plus, "Add to contacts") { go(contactIntent(content)) }
                }
                is QrContent.Event -> {
                    DlgPick(FiletIcons.Plus, "Add to calendar") { go(eventIntent(content)) }
                }
                is QrContent.Payment -> PaymentActions(content, vm, fromPicture, onClose)
                is QrContent.OneTimeCode -> {
                    DlgCaption(
                        "This sets up two-factor sign-in. Whoever has this code can make the account's sign-in " +
                            "numbers, so it belongs in one authenticator app and nowhere else.",
                    )
                    DlgPick(FiletIcons.Key, "Add to an authenticator app", detail = "Google Authenticator, Aegis, 2FAS and the like") {
                        handOff("No authenticator app on this phone takes these codes. Install one, or copy the secret into one.")
                    }
                    DlgPick(FiletIcons.Copy, "Copy the secret", detail = "For an app that asks for it typed in") {
                        vm.copySecret(content.secret, "Secret copied")
                    }
                }
                is QrContent.AuthTransfer -> {
                    DlgCaption(
                        "An authenticator app's export, carrying the secrets of " + accounts(content.accounts) +
                            ". Only scan one that came from your own phone.",
                    )
                    DlgPick(FiletIcons.Key, "Import into an authenticator app") {
                        handOff("No authenticator app on this phone takes these. Google Authenticator, which makes them, does.")
                    }
                }
                is QrContent.Passkey -> {
                    DlgCaption(
                        "A computer or tablet is asking to sign in with a passkey saved on this phone. Android " +
                            "finishes it over Bluetooth, so that device has to be close by. Only go on if you " +
                            "started the sign-in yourself, on a device in front of you.",
                    )
                    DlgPick(FiletIcons.Shield, "Sign in with this phone") {
                        onClose()
                        val uri = Uri.parse(content.raw)
                        // The scheme is written in capitals, and Android matches schemes exactly:
                        // offered as written first, then the way a filter would declare it.
                        if (!ctx.startFirst(Intent(Intent.ACTION_VIEW, uri), Intent(Intent.ACTION_VIEW, uri.normalizeScheme()))) {
                            vm.toast("Nothing on this phone took the sign-in. Try scanning it with the camera app.")
                        }
                    }
                }
                is QrContent.WifiDevice -> WifiDeviceActions(content, vm, onClose)
                is QrContent.Esim -> EsimActions(content, vm, onClose)
                is QrContent.Crypto -> {
                    DlgCaption(
                        "A crypto payment cannot be reversed. Check the address against the one you were given, " +
                            "at its start and its end, before paying.",
                    )
                    DlgPick(FiletIcons.Open, "Open in a wallet") {
                        handOff("No " + content.coin + " wallet on this phone takes these codes.")
                    }
                    DlgPick(FiletIcons.Copy, "Copy the address") { copy(content.address, "Address") }
                }
                is QrContent.Text -> {
                    DlgPick(FiletIcons.Copy, "Copy text") { copy(content.raw, "Text") }
                    DlgPick(FiletIcons.Share, "Share text") {
                        go(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content.raw), null))
                    }
                }
            }
            if (content !is QrContent.Text) {
                if (plain) {
                    DlgPick(FiletIcons.Copy, "Copy the plain text") { copy(content.raw, "Text") }
                } else {
                    DlgPick(FiletIcons.Doc, "See the plain text") { plain = true }
                }
            }
        }
    }
}

/** The facts of the code, before any button: what it would do is visible before it is asked to. */
/**
 * Every field a code carries, each with its own Copy.
 *
 * A wallet or an authenticator reads a code itself and is handed nothing by another app, so the
 * fallback that always works is a person typing the parts in - a name, a number, an amount.
 * Copying the whole raw code is no help for that; copying the one field the form asks for is.
 * Lines that describe the code rather than carry a part of it - its kind, a warning - have none.
 */
@Composable
private fun Facts(content: QrContent, onCopy: (label: String, value: String) -> Unit) {
    // One secret a code at most - a password, a two-factor secret, an activation code - so one
    // switch shows it.
    var reveal by remember { mutableStateOf(false) }
    @Composable
    fun line(
        label: String,
        value: String,
        mono: Boolean = false,
        copy: Boolean = true,
        copyAs: String = value,
        trailing: (@Composable () -> Unit)? = if (copy) {
            {
                Icon(
                    FiletIcons.Copy,
                    "Copy " + label.lowercase(),
                    tint = Filet.colors.fg2,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable { onCopy(label, copyAs) }.padding(7.dp),
                )
            }
        } else null,
    ) {
        if (value.isEmpty()) return
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = Filet.colors.fg3, modifier = Modifier.width(78.dp))
            Text(
                value,
                fontSize = 12.5.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
    @Composable
    fun hidden(label: String, value: String) = line(label, if (reveal) value else "•".repeat(value.length.coerceAtMost(16)), mono = reveal, copy = false) {
        Icon(
            if (reveal) FiletIcons.EyeOff else FiletIcons.Eye,
            if (reveal) "Hide " + label.lowercase() else "Show " + label.lowercase(),
            tint = Filet.colors.fg2,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable { reveal = !reveal }.padding(7.dp),
        )
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        when (content) {
            is QrContent.Link -> {
                line("Address", content.url)
                QrParse.payHandle(content.url)?.let { line("Pay to", it) }
                QrParse.linkKind(content.url)?.let { line("What", it, copy = false) }
                QrParse.linkWarnings(content.url).forEach { line("Careful", it, copy = false) }
            }
            is QrContent.Wifi -> {
                line("Network", content.ssid)
                line("Security", content.security.ifEmpty { "Open, no password" }, copy = false)
                hidden("Password", content.password)
                if (content.hidden) line("Hidden", "The network does not announce itself", copy = false)
            }
            is QrContent.Phone -> line("Number", content.number)
            is QrContent.Sms -> { line("To", content.number); line("Message", content.body) }
            is QrContent.Email -> { line("To", content.to); line("Subject", content.subject); line("Message", content.body) }
            is QrContent.Geo -> { line("Where", "${content.lat}, ${content.lon}"); line("Name", content.query) }
            is QrContent.Contact -> {
                line("Name", content.name)
                content.phones.forEach { line("Phone", it) }
                content.emails.forEach { line("Email", it) }
                line("Works at", content.org)
                line("Website", content.url)
                line("Address", content.address)
            }
            is QrContent.Event -> { line("What", content.title); line("Starts", content.start); line("Ends", content.end); line("Where", content.location) }
            is QrContent.Payment -> {
                line("Pay to", content.merchant)
                if (content.bic.isNotEmpty()) {
                    line("Receives in", QrPay.institution(content.bic)?.name ?: ("Bank code " + content.bic))
                }
                line("Account", content.account)
                line("City", content.city)
                if (content.amount.isEmpty()) line("Amount", "You enter it", copy = false)
                else line("Amount", (content.currency + " " + content.amount).trim(), copyAs = content.amount)
                line("Network", content.network, copy = false)
                content.details.forEach { (label, value) -> line(label, value, mono = value.any { it.isDigit() }) }
                content.oneTime?.let { line("Kind", if (it) "For one payment" else "The shop's own, reusable", copy = false) }
                if (!content.checksumOk) line("Check", "This code fails its own checksum. It is damaged or was edited.", copy = false)
            }
            is QrContent.OneTimeCode -> {
                line("Service", content.issuer)
                line("Account", content.account)
                line(
                    "Numbers",
                    if (content.timed) "" + content.digits + " digits, a new one every " + content.periodS + " seconds"
                    else "" + content.digits + " digits, a new one each time it is asked",
                    copy = false,
                )
                hidden("Secret", content.secret)
            }
            is QrContent.AuthTransfer -> {
                line("Accounts", content.accounts.toString(), copy = false)
                if (content.parts > 1) line("Part", "" + content.part + " of " + content.parts + ". Scan every part, in order.", copy = false)
            }
            is QrContent.Passkey -> line("From", "A computer or tablet near this phone", copy = false)
            is QrContent.WifiDevice -> {
                line("Device", content.info)
                line("Address", content.mac, mono = true)
            }
            is QrContent.Esim -> {
                line("Provider", content.server)
                hidden("Code", content.code)
                if (content.needsConfirmation) line("Also needs", "A confirmation code, which the carrier gives separately", copy = false)
            }
            is QrContent.Crypto -> {
                line("Coin", content.coin, copy = false)
                line("To", content.address, mono = true)
                line("Amount", content.amount.ifEmpty { "You enter it" }, copy = content.amount.isNotEmpty())
                line("For", content.label)
                line("Note", content.message)
            }
            is QrContent.Text -> Unit
        }
    }
}

@Composable
private fun RawText(raw: String) {
    SelectionContainer {
        Text(
            raw,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Filet.colors.sunken)
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

/**
 * Payment codes are read by the payment app, not handed to it: QR Ph has no intent a wallet
 * answers, so there is nothing to open the code WITH. What can be done is say so plainly, open
 * the wallet, and keep the code a copy away.
 */
@Composable
private fun PaymentActions(p: QrContent.Payment, vm: BrowserViewModel, fromPicture: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // UPI is the exception: every UPI app answers `upi://pay`, and shows the payee and the
    // amount again and asks for its PIN before anything moves.
    if (p.network == "UPI") {
        DlgCaption("The UPI app shows who is paid and how much again, and asks for your PIN before anything is paid.")
        DlgPick(FiletIcons.Open, "Pay with a UPI app") {
            onClose()
            if (!ctx.startFirst(Intent(Intent.ACTION_VIEW, Uri.parse(p.raw)))) vm.toast("No UPI app on this phone.")
        }
        DlgPick(FiletIcons.Copy, "Copy the UPI ID") { vm.copyText(p.account, "UPI ID copied") }
        return
    }
    val pm = ctx.packageManager
    // Off the main thread: a label is a resource load per app, and a phone has a couple of
    // hundred. Null until it is known, so the caption never says "none installed" while it is
    // still looking.
    val wallets = androidx.compose.runtime.produceState<List<QrPay.Wallet>?>(null, p.raw) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val launchable = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            }.getOrDefault(emptyList())
            QrPay.walletsFor(p.network, p.bic, launchable)
        }
    }.value
    val scan = if (fromPicture) "choose Scan, then upload this picture" else "scan the code with it"
    DlgCaption(
        when {
            p.network == "QR Ph" && wallets?.isEmpty() == true ->
                "QR Ph codes are paid from a wallet or bank app on InstaPay, and none is on this phone. " +
                    "The details above can be typed into one by hand."
            p.network == "QR Ph" ->
                "Any InstaPay app pays a QR Ph code, not only the receiver's. None of them takes a code handed " +
                    "over by another app: open one and " + scan + ". Or copy the details above one at a time."
            else -> "Payment apps read the code themselves: open one and " + scan + ". Or copy the details above one at a time."
        },
    )
    Spacer(Modifier.padding(top = 4.dp))
    for (w in wallets.orEmpty()) {
        DlgPick(
            FiletIcons.Open,
            "Open " + w.label,
            detail = if (w.receiver) "The receiver's own app" else "Pays it through InstaPay",
        ) {
            onClose()
            pm.getLaunchIntentForPackage(w.pkg)?.let { vm.onIntent?.invoke(it) } ?: vm.toast(w.label + " would not open.")
        }
    }
    DlgPick(FiletIcons.Copy, "Copy the whole code") { vm.copyText(p.raw, "Code copied") }
}


/**
 * Wi-Fi Easy Connect: this phone hands a network to the device in the code. Android asks which
 * one and does the rest; Filet never sees a password. Only some phones can - the code is still
 * shown on one that cannot, with the reason, since the device usually has a way in by hand too.
 */
@Composable
private fun WifiDeviceActions(d: QrContent.WifiDevice, vm: BrowserViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val supported = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { ctx.getSystemService(android.net.wifi.WifiManager::class.java)?.isEasyConnectSupported == true }.getOrDefault(false)
    }
    if (!supported) {
        DlgCaption("This phone cannot hand a network to a device this way. Most devices have another way to join: a button, or their own app.")
        return
    }
    DlgCaption("Android asks which of this phone's networks to give the device, then puts it on.")
    DlgPick(FiletIcons.Wifi, "Put the device on Wi-Fi") {
        onClose()
        // Started for a result for the same reason the save-a-network sheet is: Settings screens
        // that act for a caller ask who the caller is. See `WifiAdd`.
        vm.startForResult(Intent(Settings.ACTION_PROCESS_WIFI_EASY_CONNECT_URI, Uri.parse(d.raw))) { _, _ -> }
    }
}

/**
 * An eSIM is added from Android's own settings - an app cannot install one without being the
 * carrier's - so this copies the code and opens the screen it is typed into.
 */
@Composable
private fun EsimActions(e: QrContent.Esim, vm: BrowserViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hasEsim = remember {
        runCatching { ctx.getSystemService(android.telephony.euicc.EuiccManager::class.java)?.isEnabled == true }.getOrDefault(false)
    }
    DlgCaption(
        if (hasEsim) "Android adds an eSIM in its own settings: add an eSIM, choose to enter the code yourself, and paste it. " +
            "Activation codes usually work once, on one phone, so this one is not for sharing."
        else "This phone has no eSIM, so the code is for a different phone. Activation codes usually work once, so it is not for sharing.",
    )
    if (hasEsim) {
        DlgPick(FiletIcons.Device, "Copy the code and open SIM settings") {
            onClose()
            vm.copySecret(e.raw, "Activation code copied")
            ctx.startFirst(
                Intent(android.telephony.euicc.EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS),
                Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
        }
    }
    DlgPick(FiletIcons.Copy, "Copy the code") { vm.copySecret(e.raw, "Activation code copied") }
}

/**
 * The network in a code is one this phone has saved already.
 *
 * Bug identified here: that case was a toast, and Android's own sheet - the thing anybody
 * watches for after "Add this network" - never appears for a network it already has, because it
 * has nothing to ask. A toast where a sheet was expected reads as the button doing nothing. So
 * it is a dialogue, and it carries the one thing worth doing next: a saved network that will not
 * join usually has a password that changed after it was saved, and Android only takes the new one
 * once the old one is forgotten.
 */
@Composable
fun WifiSavedDialog(ssid: String, password: String, vm: BrowserViewModel, onClose: () -> Unit) {
    Dlg(onDismiss = onClose) {
        DlgHeader(icon = FiletIcons.Wifi, title = "Already saved", sub = ssid, onClose = onClose)
        DlgBody {
            DlgCaption("This phone has " + ssid + " saved already, so Android had nothing to ask. It joins it by itself when it is in range.")
            DlgCaption("If it will not join, the password has probably changed since it was saved. Forget the network in Wi-Fi settings, then scan the code again.")
            Spacer(Modifier.padding(top = 4.dp))
            DlgPick(FiletIcons.Wifi, "Open Wi-Fi settings", detail = if (password.isNotEmpty()) "The password is copied first" else null) {
                onClose()
                if (password.isNotEmpty()) vm.copySecret(password, "Password copied")
                vm.onIntent?.invoke(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            if (password.isNotEmpty()) DlgPick(FiletIcons.Key, "Copy password") { vm.copySecret(password, "Password copied") }
        }
    }
}

/** Start the first of [intents] something on the phone answers. False when nothing does. */
private fun android.content.Context.startFirst(vararg intents: Intent): Boolean =
    intents.any { runCatching { startActivity(it) }.isSuccess }

private fun accounts(n: Int) = if (n == 1) "one account" else "$n accounts"

private fun summary(c: QrContent): String = when (c) {
    is QrContent.Link -> host(c.url)
    is QrContent.Wifi -> c.ssid
    is QrContent.Phone -> c.number
    is QrContent.Sms -> c.number
    is QrContent.Email -> c.to
    is QrContent.Geo -> c.query.ifEmpty { "${c.lat}, ${c.lon}" }
    is QrContent.Contact -> c.name
    is QrContent.Event -> c.title
    is QrContent.Payment -> c.merchant.ifEmpty { c.account.ifEmpty { c.network } }
    is QrContent.OneTimeCode -> c.issuer.ifEmpty { c.account }
    is QrContent.AuthTransfer -> accounts(c.accounts)
    is QrContent.Passkey -> "A nearby device wants to sign in"
    is QrContent.WifiDevice -> c.info.ifEmpty { c.mac.ifEmpty { "A device" } }
    is QrContent.Esim -> c.server
    is QrContent.Crypto -> c.coin
    is QrContent.Text -> c.raw.take(60)
}

private fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.ifEmpty { null } ?: url.take(60)

/**
 * Android's own "save this network?" sheet, on 11 and later: the person approves it there, and
 * Filet never holds a Wi-Fi permission. Null where it cannot be used - older Android, or WEP,
 * which a suggestion cannot carry - and the caller opens Wi-Fi settings with the password copied.
 */
private fun wifiIntent(w: QrContent.Wifi): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val b = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(w.ssid)
    when (w.security.uppercase()) {
        "", "NOPASS" -> Unit
        "WPA", "WPA2" -> b.setWpa2Passphrase(w.password)
        "SAE", "WPA3" -> b.setWpa3Passphrase(w.password)
        else -> return null
    }
    if (w.hidden) b.setIsHiddenSsid(true)
    val list = arrayListOf(runCatching { b.build() }.getOrNull() ?: return null)
    return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list)
}

private fun contactIntent(c: QrContent.Contact): Intent {
    val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
    if (c.name.isNotEmpty()) i.putExtra(ContactsContract.Intents.Insert.NAME, c.name)
    c.phones.getOrNull(0)?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
    c.phones.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
    c.phones.getOrNull(2)?.let { i.putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it) }
    c.emails.getOrNull(0)?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
    c.emails.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
    if (c.org.isNotEmpty()) i.putExtra(ContactsContract.Intents.Insert.COMPANY, c.org)
    if (c.address.isNotEmpty()) i.putExtra(ContactsContract.Intents.Insert.POSTAL, c.address)
    return i
}

private fun eventIntent(e: QrContent.Event): Intent {
    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
    if (e.title.isNotEmpty()) i.putExtra(CalendarContract.Events.TITLE, e.title)
    if (e.location.isNotEmpty()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
    QrParse.icalTime(e.start)?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
    QrParse.icalTime(e.end)?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
    return i
}
