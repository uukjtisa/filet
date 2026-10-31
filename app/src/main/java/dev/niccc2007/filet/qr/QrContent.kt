package dev.niccc2007.filet.qr

/**
 * What a QR code says, read before anything is done with it. Pure.
 *
 * ## Why a dialog first, never an action
 *
 * A code is text somebody else chose. Opening a link the moment it is decoded, joining a network
 * the moment one is read, is acting on a stranger's instructions without showing them - the
 * pattern every phishing sticker on a parking meter relies on. So a code is always read into one
 * of these, shown for what it is, and only then offered the actions that kind of content has.
 *
 * ## The formats
 *
 * The de facto ones every scanner reads: `WIFI:` (the Android and iOS share sheet's format),
 * `MECARD:` and vCard for a contact, `MATMSG:` and `mailto:` for mail, `SMSTO:` and `sms:`,
 * `tel:`, `geo:`, iCalendar's `VEVENT`, and EMVCo merchant-presented payment codes - the format
 * behind QR Ph, which GCash and Maya codes are - plus UPI's `upi://pay`.
 *
 * Then the codes a phone meets that are not contacts or links but credentials and hand-offs:
 * `otpauth://` (setting up two-factor sign-in) and `otpauth-migration://` (an authenticator's
 * export), `FIDO:/` (a computer asking to sign in with a passkey on this phone), `DPP:` (Wi-Fi
 * Easy Connect, on printers and smart plugs), `LPA:` (an eSIM's activation code), and the crypto
 * payment schemes. Each of those was read as a plain link before, which put the button "Open
 * link" in front of a secret: a two-factor code shown that way reads as a web address to visit,
 * not as a password for an account.
 *
 * Anything else with a scheme is a link; anything without one is text.
 */
sealed interface QrContent {
    val raw: String

    data class Link(override val raw: String, val url: String) : QrContent
    data class Wifi(
        override val raw: String,
        val ssid: String,
        /** WPA, WEP, SAE, or empty for an open network. */
        val security: String,
        val password: String,
        val hidden: Boolean,
    ) : QrContent
    data class Phone(override val raw: String, val number: String) : QrContent
    data class Sms(override val raw: String, val number: String, val body: String) : QrContent
    data class Email(override val raw: String, val to: String, val subject: String, val body: String) : QrContent
    data class Geo(override val raw: String, val lat: Double, val lon: Double, val query: String) : QrContent
    data class Contact(
        override val raw: String,
        val name: String,
        val phones: List<String>,
        val emails: List<String>,
        val org: String,
        val url: String,
        val address: String,
    ) : QrContent
    data class Event(override val raw: String, val title: String, val start: String, val end: String, val location: String) : QrContent
    data class Payment(
        override val raw: String,
        val merchant: String,
        val city: String,
        /** As written in the code, or empty for a code that leaves the amount to the payer. */
        val amount: String,
        /** ISO 4217 alpha code when it is one this knows, otherwise the numeric code. */
        val currency: String,
        /**
         * "QR Ph", "UPI", "PayNow", "PromptPay", "VietQR", "QRIS" or "FPS" for the national
         * standards this knows, otherwise "EMV".
         */
        val network: String,
        /** False when the code's own checksum does not match - damaged, or edited. True for a format that carries none. */
        val checksumOk: Boolean,
        /** True for a code made for one payment, false for a shop's reusable code, null where the format does not say. */
        val oneTime: Boolean?,
        /** The account paid into, where the code names one: a UPI ID. */
        val account: String = "",
        /** The receiving institution's BIC, for QR Ph: which bank or wallet the money goes into. See [QrPay]. */
        val bic: String = "",
        /** Every other field the code carries, labelled, in the code's order - each one copyable. */
        val details: List<Pair<String, String>> = emptyList(),
    ) : QrContent

    /**
     * A two-factor account for an authenticator app. [secret] is a password: whoever holds it can
     * make the account's sign-in numbers, so it is hidden until asked for and copied as sensitive.
     */
    data class OneTimeCode(
        override val raw: String,
        val issuer: String,
        val account: String,
        val secret: String,
        /** True for numbers that change with the clock, false for ones that count presses. */
        val timed: Boolean,
        val digits: Int,
        val periodS: Int,
    ) : QrContent

    /** An authenticator app's export of several accounts at once, as one code of [parts]. */
    data class AuthTransfer(override val raw: String, val accounts: Int, val part: Int, val parts: Int) : QrContent

    /** A computer or tablet nearby asking to sign in with a passkey kept on this phone. */
    data class Passkey(override val raw: String) : QrContent

    /** Wi-Fi Easy Connect: a device - a printer, a camera, a plug - asking to be given a network. */
    data class WifiDevice(override val raw: String, val info: String, val mac: String) : QrContent

    /** An eSIM's activation code. Usually good for one phone, once. */
    data class Esim(override val raw: String, val server: String, val code: String, val needsConfirmation: Boolean) : QrContent

    /** A request for a crypto payment. Empty fields are ones the code leaves to the wallet. */
    data class Crypto(
        override val raw: String,
        val coin: String,
        val address: String,
        val amount: String,
        val label: String,
        val message: String,
    ) : QrContent

    data class Text(override val raw: String) : QrContent
}

object QrParse {

    fun parse(text: String): QrContent {
        val t = text.trim()
        val upper = t.uppercase()
        return when {
            upper.startsWith("WIFI:") -> wifi(t) ?: QrContent.Text(t)
            upper.startsWith("MECARD:") -> mecard(t)
            upper.startsWith("BEGIN:VCARD") -> vcard(t)
            upper.startsWith("MATMSG:") -> matmsg(t)
            upper.startsWith("MAILTO:") -> mailto(t)
            upper.startsWith("SMSTO:") || upper.startsWith("MMSTO:") -> smsto(t)
            upper.startsWith("SMS:") -> smsUri(t)
            upper.startsWith("TEL:") -> QrContent.Phone(t, t.substring(4).trim())
            upper.startsWith("GEO:") -> geo(t) ?: QrContent.Text(t)
            upper.contains("BEGIN:VEVENT") -> event(t)
            t.startsWith("000201") -> payment(t) ?: QrContent.Text(t)
            upper.startsWith("UPI://PAY") -> upi(t) ?: link(t, t)
            upper.startsWith("OTPAUTH://") -> oneTime(t) ?: QrContent.Text(t)
            upper.startsWith("OTPAUTH-MIGRATION://") -> transfer(t) ?: QrContent.Text(t)
            PASSKEY.matches(t) -> QrContent.Passkey(t)
            upper.startsWith("DPP:") -> wifiDevice(t) ?: QrContent.Text(t)
            upper.startsWith("LPA:") -> esim(t) ?: QrContent.Text(t)
            schemeOf(t) in COINS -> crypto(t, schemeOf(t)!!) ?: QrContent.Text(t)
            upper.startsWith("URLTO:") -> link(t.substring(6), t)
            else -> link(t, t)
        }
    }

    private fun schemeOf(s: String): String? = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""").find(s)?.groupValues?.get(1)?.lowercase()

    /** CTAP 2.2's hybrid sign-in: "FIDO:/" and the request written out in decimal digits. */
    private val PASSKEY = Regex("""^FIDO:/\d{20,}$""", RegexOption.IGNORE_CASE)

    // ── links ──

    /**
     * Schemes that run something rather than name something. `intent:` can address any component
     * on the phone with any extras, and `javascript:` and `file:` are the classic abuses; a code
     * carrying one is shown as text, never offered as a link.
     */
    private val REFUSED_SCHEMES = setOf("intent", "javascript", "file", "content", "data", "vbscript")

    /**
     * Schemes that are links without a `//`: an app store page, a payment request, a torrent.
     * Without this list "TODO:milk" would be offered as a link, since it has the shape of one.
     */
    private val OPAQUE_SCHEMES = setOf("market", "upi", "magnet", "spotify", "whatsapp", "viber", "tg", "skype", "zoommtg")

    private fun link(candidate: String, raw: String): QrContent {
        val s = candidate.trim()
        if (s.contains(' ') || s.contains('\n')) return QrContent.Text(raw)
        val scheme = schemeOf(s)
        return when {
            scheme != null && scheme in REFUSED_SCHEMES -> QrContent.Text(raw)
            scheme != null && s.startsWith("$scheme://", ignoreCase = true) && s.length > scheme.length + 3 -> QrContent.Link(raw, s)
            scheme != null && scheme in OPAQUE_SCHEMES && s.length > scheme.length + 1 -> QrContent.Link(raw, s)
            Regex("""^www\.[^\s.]+\.[^\s]+$""", RegexOption.IGNORE_CASE).matches(s) -> QrContent.Link(raw, "https://$s")
            else -> QrContent.Text(raw)
        }
    }

    /** The host of a link, lower-cased, without a port, a login or a leading "www.". Empty for an opaque scheme. */
    fun hostOf(url: String): String = url.lowercase().substringAfter("://", "")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').removePrefix("www.")

    private fun on(host: String, vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }

    /** Hosts whose whole job is to hide where a link goes until it is opened. */
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "ow.ly", "is.gd", "rb.gy", "cutt.ly", "s.id", "shorturl.at",
        "tiny.cc", "buff.ly", "rebrand.ly", "lnkd.in", "t.ly", "v.gd", "shorte.st", "adf.ly",
    )

    /**
     * What a link is for, in a few words, from its address alone - or null for an ordinary website.
     * Shown above the button, so "Open link" is not a blind tap.
     */
    fun linkKind(url: String): String? {
        val u = url.lowercase()
        val scheme = schemeOf(u)
        val host = hostOf(u)
        val path = u.substringAfter("://", "").substringAfter('/', "")
        return when {
            scheme == "market" || host == "play.google.com" && path.startsWith("store/apps") -> "An app on Google Play"
            host == "apps.apple.com" -> "An app on Apple's App Store"
            scheme == "whatsapp" || on(host, "wa.me", "whatsapp.com") -> "A WhatsApp chat"
            scheme == "tg" || on(host, "t.me", "telegram.me") -> "A Telegram chat"
            scheme == "viber" || on(host, "viber.com") -> "A Viber chat"
            on(host, "cash.app") -> "A Cash App payment page"
            host == "paypal.me" || on(host, "paypal.com") && (path.startsWith("paypalme") || path.startsWith("qrcodes")) -> "A PayPal payment page"
            on(host, "venmo.com") -> "A Venmo payment page"
            host == "revolut.me" -> "A Revolut payment page"
            on(host, "wise.com") && path.startsWith("pay") -> "A Wise payment page"
            on(host, "m.me", "messenger.com") -> "A Messenger chat"
            on(host, "facebook.com", "fb.com", "fb.me") -> "A Facebook page"
            on(host, "instagram.com") -> "An Instagram page"
            on(host, "tiktok.com") -> "A TikTok page"
            on(host, "youtube.com", "youtu.be") -> "A YouTube video"
            scheme == "spotify" || on(host, "spotify.com") -> "Something on Spotify"
            on(host, "maps.app.goo.gl", "waze.com") || host.startsWith("maps.google.") ||
                host == "google.com" && path.startsWith("maps") || host == "goo.gl" && path.startsWith("maps") -> "A place on a map"
            scheme == "zoommtg" || on(host, "zoom.us") -> "A Zoom meeting"
            host == "meet.google.com" -> "A Google Meet call"
            on(host, "teams.microsoft.com", "teams.live.com") -> "A Microsoft Teams meeting"
            host == "forms.gle" || host == "docs.google.com" && path.startsWith("forms") -> "A Google Form"
            scheme == "magnet" -> "A torrent"
            host in SHORTENERS -> "A shortened link"
            else -> null
        }
    }

    /**
     * The account a payment link pays, for the services whose links say it plainly - so it can
     * be copied into the app by hand when the link will not open it.
     */
    fun payHandle(url: String): String? {
        val host = hostOf(url)
        val path = decode(url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/'))
        val first = path.substringBefore('/')
        return when {
            on(host, "cash.app") && first.startsWith("$") && first.length > 1 -> first
            host == "paypal.me" && first.isNotEmpty() -> first
            on(host, "paypal.com") && first.equals("paypalme", ignoreCase = true) -> path.substringAfter('/', "").substringBefore('/').ifEmpty { null }
            on(host, "venmo.com") -> (if (first == "u") path.substringAfter('/', "").substringBefore('/') else first)
                .takeIf { it.isNotEmpty() && it != "code" }?.let { "@" + it }
            host == "revolut.me" && first.isNotEmpty() -> first
            else -> null
        }
    }

    /**
     * The things about an address worth a second look before it is opened, each in a sentence.
     * Not a verdict on the site - Filet cannot know that - only the shapes a misleading code uses.
     */
    fun linkWarnings(url: String): List<String> {
        val u = url.lowercase()
        val host = hostOf(u)
        val authority = u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        return buildList {
            if (u.startsWith("http://")) add("Not encrypted: what is typed into it can be read on the way.")
            // "https://bank.example@other.site" reads as the bank and goes to the other site.
            if ('@' in authority) add("Has an @ in its address: everything before the @ is decoration, and the site is " + host + ".")
            if (host.split('.').any { it.startsWith("xn--") }) add("Uses letters from another alphabet, which can imitate a familiar name.")
            if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[")) add("Goes to a bare number rather than a named site.")
            if (host in SHORTENERS) add("Shortened: where it really goes is only shown once it is opened.")
        }
    }

    // ── credentials and hand-offs ──

    private fun oneTime(t: String): QrContent? {
        val rest = t.substringAfter("://")
        val type = rest.substringBefore('/').lowercase()
        if (type != "totp" && type != "hotp") return null
        val label = decode(rest.substringAfter('/', "").substringBefore('?'))
        val q = query(rest.substringAfter('?', ""))
        val secret = q["secret"]?.replace(" ", "")?.uppercase()?.ifEmpty { null } ?: return null
        // Base32: a secret written in anything else is not one an authenticator can use.
        if (!secret.trimEnd('=').all { it in 'A'..'Z' || it in '2'..'7' }) return null
        return QrContent.OneTimeCode(
            t,
            issuer = q["issuer"]?.trim()?.ifEmpty { null } ?: label.substringBefore(':', "").trim(),
            account = label.substringAfter(':').trim(),
            secret = secret,
            timed = type == "totp",
            digits = q["digits"]?.toIntOrNull()?.takeIf { it in 6..10 } ?: 6,
            periodS = q["period"]?.toIntOrNull()?.takeIf { it in 1..600 } ?: 30,
        )
    }

    /**
     * Google Authenticator's export: base64 of a protobuf with field 1 once per account and fields
     * 3 and 4 the batch's size and index. Only those are read, for the counts; the secrets inside
     * are left packed, since nothing here needs them.
     */
    private fun transfer(t: String): QrContent? {
        // A bare + in a query decodes to a space; base64 meant the +.
        val data = query(t.substringAfter('?', ""))["data"]?.replace(' ', '+') ?: return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(data.replace('-', '+').replace('_', '/')) }.getOrNull() ?: return null
        var i = 0
        fun varint(): Long? {
            var r = 0L
            var shift = 0
            while (i < bytes.size && shift < 64) {
                val c = bytes[i++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        var accounts = 0
        var size = 1
        var index = 0
        while (i < bytes.size) {
            val key = varint() ?: return null
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> {
                    val v = varint() ?: return null
                    if (field == 3) size = v.toInt()
                    if (field == 4) index = v.toInt()
                }
                2 -> {
                    val len = varint() ?: return null
                    if (len < 0 || len > bytes.size - i) return null
                    i += len.toInt()
                    if (field == 1) accounts++
                }
                1 -> i += 8
                5 -> i += 4
                else -> return null
            }
        }
        if (i != bytes.size || accounts == 0) return null
        return QrContent.AuthTransfer(t, accounts, part = index + 1, parts = size.coerceAtLeast(index + 1))
    }

    /** `DPP:C:81/1;M:5254005828e5;I:SN=4774LH2b4044;K:MDkw...;;` - K, the device's key, is the one it cannot do without. */
    private fun wifiDevice(t: String): QrContent? {
        val f = fields(t.substring(4)).toMap()
        if (f["K"].isNullOrEmpty()) return null
        val mac = f["M"].orEmpty().filter { it.isLetterOrDigit() }.lowercase()
            .takeIf { it.length == 12 }?.chunked(2)?.joinToString(":").orEmpty()
        return QrContent.WifiDevice(t, info = f["I"].orEmpty().trim(), mac = mac)
    }

    /** GSMA SGP.22: `LPA:1$server$matching id$oid$confirmation flag`, the last three optional. */
    private fun esim(t: String): QrContent? {
        val parts = t.substring(4).split('$')
        if (parts.size < 2 || parts[0] != "1") return null
        val server = parts[1].trim()
        if (server.isEmpty() || server.any { it.isWhitespace() }) return null
        return QrContent.Esim(t, server, code = parts.getOrNull(2)?.trim().orEmpty(), needsConfirmation = parts.getOrNull(4) == "1")
    }

    private fun upi(t: String): QrContent? {
        val q = query(t.substringAfter('?', ""))
        val payee = q["pa"]?.trim()?.ifEmpty { null } ?: return null
        return QrContent.Payment(
            t,
            merchant = q["pn"].orEmpty().trim(),
            city = "",
            amount = q["am"].orEmpty().trim(),
            currency = q["cu"]?.trim()?.uppercase()?.ifEmpty { null } ?: "INR",
            network = "UPI",
            checksumOk = true,
            oneTime = null,
            account = payee,
        )
    }

    /** Crypto payment schemes (BIP 21 and its relatives), and what each coin is called. */
    private val COINS = mapOf(
        "bitcoin" to "Bitcoin", "lightning" to "Bitcoin Lightning", "bitcoincash" to "Bitcoin Cash",
        "litecoin" to "Litecoin", "dogecoin" to "Dogecoin", "ethereum" to "Ethereum",
        "monero" to "Monero", "solana" to "Solana",
    )

    private fun crypto(t: String, scheme: String): QrContent? {
        val rest = t.substring(scheme.length + 1).removePrefix("//")
        val target = rest.substringBefore('?')
        // EIP-681's `ethereum:token/transfer?address=...` names a contract call, and the amount is
        // in the token's own units, which nothing in the code says. Read as text, not as a
        // payment whose figures would be wrong.
        if ('/' in target) return null
        val address = target.substringBefore('@').trim()
        if (address.isEmpty() || address.any { it.isWhitespace() }) return null
        val q = query(rest.substringAfter('?', ""))
        val amount = q["amount"] ?: q["tx_amount"] ?: q["value"]?.let(::fromWei)
            ?: (if (scheme == "lightning") invoiceAmount(address) else null)
        return QrContent.Crypto(
            t,
            coin = COINS.getValue(scheme),
            address = address,
            amount = amount.orEmpty(),
            label = q["label"] ?: q["recipient_name"].orEmpty(),
            message = q["message"] ?: q["tx_description"] ?: q["memo"].orEmpty(),
        )
    }

    /** EIP-681's value is in wei, sometimes in scientific notation. */
    private fun fromWei(v: String): String? = runCatching {
        java.math.BigDecimal(v.trim()).movePointLeft(18).stripTrailingZeros().toPlainString()
    }.getOrNull()

    /**
     * A Lightning invoice carries its amount in its prefix: `lnbc2500u1...` is 2500 micro-bitcoin.
     * Null for an invoice that leaves the amount to the payer.
     */
    fun invoiceAmount(invoice: String): String? {
        val m = Regex("""^ln(?:bc|tb|bcrt)(\d+)([munp]?)1""", RegexOption.IGNORE_CASE).find(invoice) ?: return null
        val shift = when (m.groupValues[2].lowercase()) { "m" -> 3; "u" -> 6; "n" -> 9; "p" -> 12; else -> 0 }
        return java.math.BigDecimal(m.groupValues[1]).movePointLeft(shift).stripTrailingZeros().toPlainString()
    }

    // ── Wi-Fi and the MECARD family: KEY:value; pairs with backslash escapes ──

    /** Split `A:x;B:y;;` into pairs, honouring `\;` `\:` `\,` `\\` `\"`. Keys upper-cased. */
    fun fields(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var key = StringBuilder()
        var value = StringBuilder()
        var inValue = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { (if (inValue) value else key).append(body[i + 1]); i++ }
                c == ':' && !inValue -> inValue = true
                c == ';' -> {
                    if (key.isNotEmpty()) out += key.toString().trim().uppercase() to value.toString()
                    key = StringBuilder(); value = StringBuilder(); inValue = false
                }
                else -> (if (inValue) value else key).append(c)
            }
            i++
        }
        if (key.isNotEmpty()) out += key.toString().trim().uppercase() to value.toString()
        return out
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s

    private fun wifi(t: String): QrContent? {
        val f = fields(t.substring(5)).toMap()
        val ssid = unquote(f["S"] ?: return null)
        val type = f["T"].orEmpty().uppercase().let { if (it == "NOPASS") "" else it }
        return QrContent.Wifi(t, ssid, type, unquote(f["P"].orEmpty()), f["H"].equals("true", ignoreCase = true))
    }

    private fun mecard(t: String): QrContent {
        val f = fields(t.substring(7))
        val name = f.firstOrNull { it.first == "N" }?.second.orEmpty()
            // "Doe,John" is surname first.
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.reversed().joinToString(" ")
        return QrContent.Contact(
            t, name,
            phones = f.filter { it.first == "TEL" }.map { it.second },
            emails = f.filter { it.first == "EMAIL" }.map { it.second },
            org = f.firstOrNull { it.first == "ORG" }?.second.orEmpty(),
            url = f.firstOrNull { it.first == "URL" }?.second.orEmpty(),
            address = f.firstOrNull { it.first == "ADR" }?.second.orEmpty(),
        )
    }

    /** vCard: one property a line, parameters after a semicolon in the name, folded lines joined. */
    private fun vcard(t: String): QrContent {
        val lines = t.replace("\r\n ", "").replace("\n ", "").lines()
        fun all(prop: String) = lines.filter {
            val name = it.substringBefore(':').substringBefore(';').uppercase()
            name == prop && it.contains(':')
        }.map { it.substringAfter(':').trim() }
        val fn = all("FN").firstOrNull()
            ?: all("N").firstOrNull()?.split(';')?.filter { it.isNotBlank() }?.reversed()?.joinToString(" ").orEmpty()
        return QrContent.Contact(
            t, fn,
            phones = all("TEL"),
            emails = all("EMAIL"),
            org = all("ORG").firstOrNull()?.replace(';', ' ')?.trim().orEmpty(),
            url = all("URL").firstOrNull().orEmpty(),
            address = all("ADR").firstOrNull()?.split(';')?.filter { it.isNotBlank() }?.joinToString(", ").orEmpty(),
        )
    }

    private fun matmsg(t: String): QrContent {
        val f = fields(t.substring(7)).toMap()
        return QrContent.Email(t, f["TO"].orEmpty(), f["SUB"].orEmpty(), f["BODY"].orEmpty())
    }

    private fun query(q: String): Map<String, String> = q.split('&').mapNotNull {
        val k = it.substringBefore('=').lowercase()
        if (k.isEmpty()) null else k to decode(it.substringAfter('=', ""))
    }.toMap()

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun mailto(t: String): QrContent {
        val rest = t.substring(7)
        val q = query(rest.substringAfter('?', ""))
        return QrContent.Email(t, decode(rest.substringBefore('?')), q["subject"].orEmpty(), q["body"].orEmpty())
    }

    private fun smsto(t: String): QrContent {
        val rest = t.substring(6)
        return QrContent.Sms(t, rest.substringBefore(':').trim(), rest.substringAfter(':', ""))
    }

    private fun smsUri(t: String): QrContent {
        val rest = t.substring(4)
        return QrContent.Sms(t, rest.substringBefore('?').trim(), query(rest.substringAfter('?', ""))["body"].orEmpty())
    }

    private fun geo(t: String): QrContent? {
        val rest = t.substring(4)
        val coords = rest.substringBefore('?').split(',')
        val lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lon = coords.getOrNull(1)?.trim()?.substringBefore(';')?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return QrContent.Geo(t, lat, lon, query(rest.substringAfter('?', ""))["q"].orEmpty())
    }

    private fun event(t: String): QrContent {
        val lines = t.replace("\r\n ", "").replace("\n ", "").lines()
        fun one(prop: String) = lines.firstOrNull {
            it.substringBefore(':').substringBefore(';').uppercase() == prop && it.contains(':')
        }?.substringAfter(':')?.trim().orEmpty()
        return QrContent.Event(t, one("SUMMARY"), one("DTSTART"), one("DTEND"), one("LOCATION"))
    }

    /**
     * An iCalendar time - `20261030T080000`, `20261030T000000Z`, or a date alone - in epoch ms.
     * Local time when it carries no Z, as the format says. Null when it is none of those.
     */
    fun icalTime(s: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? = runCatching {
        val t = s.trim()
        when {
            Regex("""^\d{8}T\d{6}Z$""").matches(t) ->
                java.time.LocalDateTime.parse(t.dropLast(1), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            Regex("""^\d{8}T\d{6}$""").matches(t) ->
                java.time.LocalDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .atZone(zone).toInstant().toEpochMilli()
            Regex("""^\d{8}$""").matches(t) ->
                java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            else -> null
        }
    }.getOrNull()

    // ── EMVCo merchant-presented payment codes ──

    /** ISO 4217 numeric to alpha, for the currencies a code here is likely to carry. */
    private val CURRENCIES = mapOf(
        "608" to "PHP", "840" to "USD", "702" to "SGD", "458" to "MYR", "764" to "THB",
        "360" to "IDR", "704" to "VND", "392" to "JPY", "410" to "KRW", "978" to "EUR",
        "356" to "INR", "344" to "HKD", "036" to "AUD",
    )

    /** Top-level tag to value. Null when the layout does not hold together. */
    fun tlv(s: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            if (i + 4 > s.length) return null
            val tag = s.substring(i, i + 2)
            val len = s.substring(i + 2, i + 4).toIntOrNull() ?: return null
            if (i + 4 + len > s.length) return null
            out[tag] = s.substring(i + 4, i + 4 + len)
            i += 4 + len
        }
        return out
    }

    /** CRC-16/CCITT-FALSE over everything up to and including "6304", as EMVCo specifies. */
    fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return "%04X".format(crc)
    }

    private fun payment(t: String): QrContent? {
        val fields = tlv(t) ?: return null
        if (fields["00"] != "01") return null
        val crc = fields["63"]
        val checksumOk = crc != null && t.length >= 4 && crc.equals(crc16(t.substring(0, t.length - 4)), ignoreCase = true)
        // Merchant account information lives in tags 26 to 51; its first sub-tag names the scheme.
        val accounts = (26..51).mapNotNull { fields["%02d".format(it)]?.let(::tlv) }
        val ph = accounts.firstOrNull { a -> a["00"]?.lowercase()?.let { "p2pqrpay" in it || "ppmi" in it || it.startsWith("ph.") } == true }
        val schemes = accounts.mapNotNull { it["00"]?.lowercase() }
        val network = when {
            ph != null -> "QR Ph"
            schemes.any { it == "sg.paynow" } -> "PayNow"
            schemes.any { it.startsWith("a000000677") } -> "PromptPay"
            schemes.any { it.startsWith("a000000727") } -> "VietQR"
            schemes.any { it == "id.co.qris.www" } -> "QRIS"
            schemes.any { it == "hk.com.hkicl" } -> "FPS"
            else -> "EMV"
        }
        val currency = fields["53"].orEmpty().let { CURRENCIES[it] ?: it }
        // The rest of what the code says, for copying one field at a time. QR Ph's account
        // template carries the receiver's account and a reference after its bank code; tag 62 is
        // EMVCo's additional data, the same in every network.
        val details = buildList {
            ph?.get("03")?.let { add("Account in the code" to it) }
            ph?.get("04")?.let { add("Reference" to it) }
            fields["62"]?.let(::tlv)?.let { extra ->
                extra["01"]?.let { add("Bill number" to it) }
                extra["02"]?.let { add("Mobile" to it) }
                extra["03"]?.let { add("Store" to it) }
                extra["05"]?.let { add("Reference" to it) }
                extra["07"]?.let { add("Terminal" to it) }
                extra["08"]?.let { add("Purpose" to it) }
            }
        }.filter { it.second.isNotBlank() }
        return QrContent.Payment(
            t,
            merchant = fields["59"].orEmpty(),
            city = fields["60"].orEmpty(),
            amount = fields["54"].orEmpty(),
            currency = currency,
            network = network,
            checksumOk = checksumOk,
            oneTime = fields["01"] == "12",
            bic = ph?.get("01").orEmpty().trim(),
            details = details,
        )
    }
}
