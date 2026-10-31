package dev.niccc2007.filet.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which app pays a payment code, and what the code says about where the money goes. See [QrPay]. */
class QrPayTest {

    private fun tag(id: String, v: String) = id + "%02d".format(v.length) + v

    /** A QR Ph person-to-person code the way an InstaPay wallet writes one. Synthetic throughout. */
    private fun qrPh(bic: String, extra: String = ""): String {
        val acct = tag("00", "com.p2pqrpay") + tag("01", bic) + tag("02", "12345678") +
            tag("03", "100200300400500") + tag("04", "SYNTHETICREF0001")
        val body = tag("00", "01") + tag("01", "11") + tag("27", acct) + tag("52", "6016") + tag("53", "608") +
            tag("58", "PH") + tag("59", "AN** B.") + tag("60", "Sample City") + extra + "6304"
        return body + QrParse.crc16(body)
    }

    @Test
    fun `a QR Ph code names the institution the money goes into`() {
        val p = QrParse.parse(qrPh("GXCHPHM2XXX")) as QrContent.Payment
        assertEquals("QR Ph", p.network)
        assertEquals("GXCHPHM2XXX", p.bic)
        assertEquals("GCash", QrPay.institution(p.bic)?.name)
        assertTrue(p.checksumOk)
        assertEquals("Maya", QrPay.institution("PAPHPHM1XXX")?.name)
        assertNull("an unknown bank is not guessed at", QrPay.institution("ZZZZPHM1XXX"))
    }

    @Test
    fun `every other field the code carries is offered, labelled, in order`() {
        val extra = tag("62", tag("01", "INV-42") + tag("02", "09170000000") + tag("08", "Rent"))
        val p = QrParse.parse(qrPh("BNORPHMMXXX", extra)) as QrContent.Payment
        assertEquals(
            listOf(
                "Account in the code" to "100200300400500",
                "Reference" to "SYNTHETICREF0001",
                "Bill number" to "INV-42",
                "Mobile" to "09170000000",
                "Purpose" to "Rent",
            ),
            p.details,
        )
    }

    @Test
    fun `other national standards are named by their scheme`() {
        fun network(scheme: String): String {
            val body = tag("00", "01") + tag("26", tag("00", scheme)) + tag("53", "702") + tag("59", "Shop") + "6304"
            return (QrParse.parse(body + QrParse.crc16(body)) as QrContent.Payment).network
        }
        assertEquals("PayNow", network("SG.PAYNOW"))
        assertEquals("PromptPay", network("A000000677010111"))
        assertEquals("VietQR", network("A000000727"))
        assertEquals("QRIS", network("ID.CO.QRIS.WWW"))
        assertEquals("EMV", network("com.example.pay"))
    }

    // ── which installed apps are offered ──

    private val phone = listOf(
        "Maya" to "pkg.maya",
        "GCash" to "pkg.gcash",
        "BPI" to "pkg.bpi",
        "Mayan Calendar" to "pkg.calendar",
        "Calculator" to "pkg.calc",
        "BDO Online" to "pkg.bdo",
        "BDO Online" to "pkg.bdo",
    )

    @Test
    fun `the receiver's own app comes first and is said to be it, the rest follow by name`() {
        val w = QrPay.walletsFor("QR Ph", "GXCHPHM2XXX", phone)
        assertEquals(listOf("GCash", "BDO Online", "BPI", "Maya"), w.map { it.label })
        assertTrue(w.first().receiver)
        assertFalse(w.drop(1).any { it.receiver })
    }

    @Test
    fun `an unknown receiver still lists every app that can pay, with none called the receiver's`() {
        val w = QrPay.walletsFor("QR Ph", "ZZZZPHM1XXX", phone)
        assertEquals(4, w.size)
        assertFalse(w.any { it.receiver })
    }

    @Test
    fun `a name matches a whole word, not any prefix`() {
        assertTrue(QrPay.labelMatches("BPI", "BPI"))
        assertTrue(QrPay.labelMatches("BDO Online", "BDO"))
        assertTrue(QrPay.labelMatches("LANDBANK Mobile Banking", "Landbank"))
        assertFalse(QrPay.labelMatches("Mayan Calendar", "Maya"))
        assertFalse(QrPay.labelMatches("BPIX", "BPI"))
    }

    @Test
    fun `codes of other networks are not matched to Philippine wallets`() {
        assertEquals(emptyList<QrPay.Wallet>(), QrPay.walletsFor("PayNow", "", phone))
    }

    // ── payment links ──

    @Test
    fun `a payment link says whose it is and which service`() {
        assertEquals("\$coffee", QrParse.payHandle("https://cash.app/\$coffee"))
        assertEquals("\$coffee", QrParse.payHandle("https://cash.app/%24coffee"))
        assertEquals("coffeeshop", QrParse.payHandle("https://paypal.me/coffeeshop/5"))
        assertEquals("coffeeshop", QrParse.payHandle("https://www.paypal.com/paypalme/coffeeshop"))
        assertEquals("@coffee-shop", QrParse.payHandle("https://venmo.com/u/coffee-shop"))
        assertEquals("@coffee-shop", QrParse.payHandle("https://venmo.com/coffee-shop"))
        assertEquals("coffee", QrParse.payHandle("https://revolut.me/coffee"))
        assertNull(QrParse.payHandle("https://example.com/coffee"))
        assertEquals("A Cash App payment page", QrParse.linkKind("https://cash.app/\$coffee"))
        assertEquals("A PayPal payment page", QrParse.linkKind("https://paypal.me/coffeeshop"))
    }
}
