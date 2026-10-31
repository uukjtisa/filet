package dev.niccc2007.filet.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a code is taken to be, one format at a time. See [QrParse]. */
class QrParseTest {

    private inline fun <reified T : QrContent> parsed(s: String): T {
        val c = QrParse.parse(s)
        assertTrue("$s read as $c", c is T)
        return c as T
    }

    @Test
    fun `a wifi share reads its name, security and password, escapes and all`() {
        val w = parsed<QrContent.Wifi>("WIFI:T:WPA;S:Home\\;Net;P:pa\\:ss\\\\word;H:true;;")
        assertEquals("Home;Net", w.ssid)
        assertEquals("WPA", w.security)
        assertEquals("pa:ss\\word", w.password)
        assertTrue(w.hidden)
        // Order is free, and an open network has no password.
        val open = parsed<QrContent.Wifi>("WIFI:S:Cafe;T:nopass;;")
        assertEquals("", open.security)
        assertEquals("", open.password)
        assertFalse(open.hidden)
    }

    @Test
    fun `a link is a link, and a word with a colon is not`() {
        assertEquals("https://example.com/a?b=1", parsed<QrContent.Link>("https://example.com/a?b=1").url)
        assertEquals("https://www.example.com", parsed<QrContent.Link>("www.example.com").url)
        // A UPI address that is not a payment request is still a link to its app.
        assertEquals("upi://mandate?pa=x@y", parsed<QrContent.Link>("upi://mandate?pa=x@y").url)
        parsed<QrContent.Link>("market:details?id=dev.niccc2007.filet")
        parsed<QrContent.Text>("TODO:milk")
        parsed<QrContent.Text>("just some words: here")
    }

    @Test
    fun `a scheme that runs something is never offered as a link`() {
        parsed<QrContent.Text>("intent://scan/#Intent;scheme=zxing;package=com.evil;end")
        parsed<QrContent.Text>("javascript:alert(1)")
        parsed<QrContent.Text>("file:///sdcard/secret.txt")
    }

    @Test
    fun `contacts, from MECARD and from a vCard`() {
        val m = parsed<QrContent.Contact>("MECARD:N:Cruz,Ana;TEL:+639171234567;EMAIL:ana@example.com;ORG:Acme;;")
        assertEquals("Ana Cruz", m.name)
        assertEquals(listOf("+639171234567"), m.phones)
        assertEquals(listOf("ana@example.com"), m.emails)
        assertEquals("Acme", m.org)
        val v = parsed<QrContent.Contact>("BEGIN:VCARD\nVERSION:3.0\nFN:Ben Reyes\nTEL;TYPE=CELL:0917\nEMAIL:ben@example.com\nEND:VCARD")
        assertEquals("Ben Reyes", v.name)
        assertEquals(listOf("0917"), v.phones)
    }

    @Test
    fun `mail, messages, calls and places`() {
        val e = parsed<QrContent.Email>("MATMSG:TO:a@b.com;SUB:Hi;BODY:Hello there;;")
        assertEquals("a@b.com", e.to); assertEquals("Hi", e.subject); assertEquals("Hello there", e.body)
        val m = parsed<QrContent.Email>("mailto:a@b.com?subject=Hi%20you&body=x")
        assertEquals("Hi you", m.subject)
        val s = parsed<QrContent.Sms>("SMSTO:+63917:See you at 5")
        assertEquals("+63917", s.number); assertEquals("See you at 5", s.body)
        assertEquals("09171", parsed<QrContent.Sms>("sms:09171?body=hi").number)
        assertEquals("+632811", parsed<QrContent.Phone>("tel:+632811").number)
        val g = parsed<QrContent.Geo>("geo:14.5995,120.9842?q=Manila")
        assertEquals(14.5995, g.lat, 1e-9); assertEquals("Manila", g.query)
        parsed<QrContent.Text>("geo:999,0")
    }

    @Test
    fun `a calendar event`() {
        val ev = parsed<QrContent.Event>("BEGIN:VEVENT\nSUMMARY:Exam\nDTSTART:20261030T080000\nLOCATION:Room 4\nEND:VEVENT")
        assertEquals("Exam", ev.title); assertEquals("20261030T080000", ev.start); assertEquals("Room 4", ev.location)
    }

    /** An EMVCo payload with its checksum, the way a QR Ph code is built. */
    private fun emv(vararg fields: Pair<String, String>): String {
        val body = fields.joinToString("") { (tag, v) -> tag + "%02d".format(v.length) + v } + "6304"
        return body + QrParse.crc16(body)
    }

    @Test
    fun `a QR Ph payment code reads its merchant, amount and currency, and checks itself`() {
        val acct = "00" + "%02d".format("com.p2pqrpay".length) + "com.p2pqrpay" + "0108GXCHPHM2"
        val code = emv("00" to "01", "01" to "12", "27" to acct, "52" to "5999", "53" to "608", "54" to "150.00", "58" to "PH", "59" to "Sari Store", "60" to "Marikina")
        val p = parsed<QrContent.Payment>(code)
        assertEquals("Sari Store", p.merchant)
        assertEquals("Marikina", p.city)
        assertEquals("150.00", p.amount)
        assertEquals("PHP", p.currency)
        assertEquals("QR Ph", p.network)
        assertEquals(true, p.oneTime)
        assertTrue(p.checksumOk)
        // One character changed and the code no longer matches its own checksum.
        assertFalse(parsed<QrContent.Payment>(code.replace("150.00", "950.00")).checksumOk)
    }

    @Test
    fun `the CRC is the one EMVCo specifies`() {
        // CRC-16/CCITT-FALSE's published check value.
        assertEquals("29B1", QrParse.crc16("123456789"))
    }

    @Test
    fun `text that only starts like a payment code is text`() {
        parsed<QrContent.Text>("000201 is my locker number")
    }

    // ── payments that are not EMV ──

    @Test
    fun `a UPI request names its payee and amount, and says nothing about being one-time`() {
        val p = parsed<QrContent.Payment>("upi://pay?pa=stall@okbank&pn=Chai%20Stall&am=20.00&cu=INR")
        assertEquals("UPI", p.network)
        assertEquals("stall@okbank", p.account)
        assertEquals("Chai Stall", p.merchant)
        assertEquals("20.00", p.amount)
        assertEquals("INR", p.currency)
        assertNull("the format does not say, so neither does this", p.oneTime)
        // Without a payee it is not a payment it could make; it stays a link to the app.
        parsed<QrContent.Link>("upi://pay?pn=Nobody")
    }

    @Test
    fun `a crypto request reads its coin, address, amount and note`() {
        val b = parsed<QrContent.Crypto>("bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.0015&label=Shop&message=Order%2042")
        assertEquals("Bitcoin", b.coin)
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", b.address)
        assertEquals("0.0015", b.amount)
        assertEquals("Shop", b.label)
        assertEquals("Order 42", b.message)
        // Written in capitals to fit a smaller code, which is allowed.
        assertEquals("Bitcoin", parsed<QrContent.Crypto>("BITCOIN:BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ").coin)
    }

    @Test
    fun `an ethereum value is in wei, and a lightning invoice carries its own amount`() {
        val e = parsed<QrContent.Crypto>("ethereum:0xfb6916095ca1df60bb79Ce92ce3ea74c37c5d359@1?value=2.014e18")
        assertEquals("0xfb6916095ca1df60bb79Ce92ce3ea74c37c5d359", e.address)
        assertEquals("2.014", e.amount)
        assertEquals("0.0025", parsed<QrContent.Crypto>("lightning:lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzq").amount)
        assertEquals("20", QrParse.invoiceAmount("lnbc20u1pvjluez")?.let { java.math.BigDecimal(it).movePointRight(6).toPlainString() })
        assertNull("an invoice that leaves the amount to the payer", QrParse.invoiceAmount("lnbc1pvjluezpp5qqqsyqcyq"))
    }

    @Test
    fun `a token contract call is not read as a payment with the wrong figures`() {
        parsed<QrContent.Text>("ethereum:0x89205a3a3b2a69de6dbf7f01ed13b2108b2c43e7/transfer?address=0x8e23ee67d1332ad560396262c48ffbb01f93d052&uint256=1")
    }

    // ── credentials and hand-offs ──

    @Test
    fun `a two-factor setup reads its service and account, and keeps the secret apart`() {
        val o = parsed<QrContent.OneTimeCode>("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&period=60&digits=8")
        assertEquals("Example", o.issuer)
        assertEquals("alice@example.com", o.account)
        assertEquals("JBSWY3DPEHPK3PXP", o.secret)
        assertTrue(o.timed)
        assertEquals(8, o.digits)
        assertEquals(60, o.periodS)
        // The issuer can come from the label alone, and the defaults are the ones every app uses.
        val bare = parsed<QrContent.OneTimeCode>("otpauth://hotp/ACME%20Co:john?secret=jbsw%20y3dp&counter=0")
        assertEquals("ACME Co", bare.issuer)
        assertEquals("JBSWY3DP", bare.secret)
        assertFalse(bare.timed)
        assertEquals(6, bare.digits)
    }

    @Test
    fun `a two-factor code with no usable secret is shown as text, never as a link`() {
        parsed<QrContent.Text>("otpauth://totp/Example:alice?issuer=Example")
        parsed<QrContent.Text>("otpauth://totp/Example:alice?secret=not-base32!")
        parsed<QrContent.Text>("otpauth://sometimes/Example:alice?secret=JBSWY3DPEHPK3PXP")
    }

    /** A protobuf the shape of an authenticator export: [n] accounts, batch [index] of [size]. */
    private fun export(n: Int, size: Int, index: Int): String {
        val out = java.io.ByteArrayOutputStream()
        repeat(n) { out.write(byteArrayOf(0x0A, 3, 1, 2, 3)) }
        out.write(byteArrayOf(0x18, size.toByte(), 0x20, index.toByte()))
        val b64 = java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        return "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(b64, "UTF-8")
    }

    @Test
    fun `an authenticator export counts its accounts and its parts without unpacking the secrets`() {
        val t = parsed<QrContent.AuthTransfer>(export(n = 3, size = 2, index = 1))
        assertEquals(3, t.accounts)
        assertEquals(2, t.part)
        assertEquals(2, t.parts)
        // An unencoded + in the query is still the base64 +, not a space.
        val one = parsed<QrContent.AuthTransfer>(export(1, 1, 0).replace("%2B", "+"))
        assertEquals(1, one.parts)
        parsed<QrContent.Text>("otpauth-migration://offline?data=%%%")
        parsed<QrContent.Text>("otpauth-migration://offline?data=CgM")
    }

    @Test
    fun `a passkey request is its own thing, and only when it is the digits the standard writes`() {
        parsed<QrContent.Passkey>("FIDO:/" + "1234567890".repeat(8))
        parsed<QrContent.Text>("FIDO:/hello")
    }

    @Test
    fun `a Wi-Fi Easy Connect code names its device, and needs its key`() {
        val d = parsed<QrContent.WifiDevice>("DPP:C:81/1;M:5254005828e5;I:SN=4774LH2b4044;K:MDkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDIgADURzxmttZoIRIPWGoQMV00XHWCAQIhXruVWOz0NjlkIA=;;")
        assertEquals("52:54:00:58:28:e5", d.mac)
        assertEquals("SN=4774LH2b4044", d.info)
        parsed<QrContent.Text>("DPP:C:81/1;M:5254005828e5;;")
    }

    @Test
    fun `an eSIM code names its provider, and says when the carrier sends a second code`() {
        val e = parsed<QrContent.Esim>("LPA:1\$rsp.example.com\$QRF-SPEEDTEST")
        assertEquals("rsp.example.com", e.server)
        assertEquals("QRF-SPEEDTEST", e.code)
        assertFalse(e.needsConfirmation)
        assertTrue(parsed<QrContent.Esim>("LPA:1\$smdp.example.com\$ABC\$1.3.6.1.4.1\$1").needsConfirmation)
        parsed<QrContent.Text>("LPA:2\$smdp.example.com\$ABC")
    }

    // ── what a link is, before it is opened ──

    @Test
    fun `a link says what it is for when its address shows it`() {
        assertEquals("A WhatsApp chat", QrParse.linkKind("https://wa.me/639171234567"))
        assertEquals("An app on Google Play", QrParse.linkKind("https://play.google.com/store/apps/details?id=x"))
        assertEquals("A place on a map", QrParse.linkKind("https://maps.app.goo.gl/abc"))
        assertEquals("A Facebook page", QrParse.linkKind("https://m.facebook.com/somepage"))
        assertEquals("A shortened link", QrParse.linkKind("https://bit.ly/3xyz"))
        assertNull(QrParse.linkKind("https://example.com/menu"))
    }

    @Test
    fun `the shapes a misleading link uses are each named`() {
        assertTrue(QrParse.linkWarnings("http://example.com").single().startsWith("Not encrypted"))
        val at = QrParse.linkWarnings("https://bank.example@other.site/login").single()
        assertTrue(at, at.contains("other.site"))
        assertTrue(QrParse.linkWarnings("https://xn--pple-43d.com").single().contains("another alphabet"))
        assertTrue(QrParse.linkWarnings("https://192.168.1.10/pay").single().contains("bare number"))
        assertEquals(emptyList<String>(), QrParse.linkWarnings("https://example.com/menu"))
        assertEquals("other.site", QrParse.hostOf("https://user:pw@other.site:8080/x"))
    }
}
