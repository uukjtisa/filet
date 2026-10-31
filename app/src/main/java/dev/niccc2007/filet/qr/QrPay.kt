package dev.niccc2007.filet.qr

/**
 * Which app on the phone can pay a payment code, and which one the money is going into. Pure.
 *
 * ## The fault this exists for
 *
 * Bug identified here: a QR Ph code offered "Open GCash" and "Open Maya" side by side, always, in
 * that order, whatever the code said - so a code that pays into a GCash account read as a guess
 * between two wallets. The code does say. QR Ph keeps the receiving institution's bank code
 * (its BIC) in the merchant account template, and that names the receiver's own app.
 *
 * Both buttons were not wrong, though, and dropping the second would have been. QR Ph runs over
 * InstaPay, so any member's app - another wallet, a bank's app - pays the same code. So the
 * receiver's app comes first and is called that, and the rest stay, saying why they can.
 *
 * Apps are found by their name on the home screen, not by package. A bank publishes its app
 * under a package nobody outside it would guess, renames it, splits it in two; the name a person
 * sees is the one stable thing, and it is also exactly what the button says.
 */
object QrPay {

    /** Somewhere money can be received, and the names its apps go by on a home screen. */
    class Institution(val name: String, val apps: List<String>)

    /** By the first eight characters of the BIC: the branch suffix ("XXX") says nothing here. */
    private val BY_BIC = mapOf(
        "GXCHPHM2" to Institution("GCash", listOf("GCash")),
        "PAPHPHM1" to Institution("Maya", listOf("Maya")),
        "BNORPHMM" to Institution("BDO", listOf("BDO")),
        "BOPIPHMM" to Institution("BPI", listOf("BPI")),
        "UBPHPHMM" to Institution("UnionBank", listOf("UnionBank")),
        "MBTCPHMM" to Institution("Metrobank", listOf("Metrobank")),
        "TLBPPHMM" to Institution("Landbank", listOf("Landbank")),
        "RCBCPHMM" to Institution("RCBC", listOf("RCBC")),
        "SETCPHMM" to Institution("Security Bank", listOf("Security Bank")),
        "CHBKPHMM" to Institution("China Bank", listOf("China Bank", "Chinabank")),
        "PNBMPHMM" to Institution("PNB", listOf("PNB")),
        "EWBCPHMM" to Institution("EastWest", listOf("EastWest")),
        "DBPHPHMM" to Institution("DBP", listOf("DBP")),
    )

    fun institution(bic: String): Institution? = BY_BIC[bic.trim().take(8).uppercase()]

    /**
     * The apps that pay a QR Ph code, by home-screen name: every institution above, and the
     * wallets and digital banks that are InstaPay members without being a receiver this table
     * names.
     */
    val QR_PH_APPS: List<String> =
        BY_BIC.values.flatMap { it.apps } + listOf("GoTyme", "SeaBank", "Tonik", "Coins.ph", "CIMB")

    /**
     * Whether an app called [label] is the one [name] means: the whole label, or the label
     * starting with it and then a word break. "BPI" is "BPI Mobile"; "Maya" is not "Mayan
     * Calendar", which a plain prefix would say it was.
     */
    fun labelMatches(label: String, name: String): Boolean {
        val l = label.trim().lowercase()
        val n = name.trim().lowercase()
        if (n.isEmpty() || !l.startsWith(n)) return false
        return l.length == n.length || !l[n.length].isLetterOrDigit()
    }

    /** An app that can pay the code. [receiver] when it is the receiving institution's own. */
    data class Wallet(val label: String, val pkg: String, val receiver: Boolean)

    /**
     * The installed apps that pay a code on [network], the receiver's first, then by name. An
     * app is listed once however many of its launcher entries match.
     *
     * @param installed every launchable app, as (label, package).
     */
    fun walletsFor(network: String, bic: String, installed: List<Pair<String, String>>): List<Wallet> {
        if (network != "QR Ph") return emptyList()
        val own = institution(bic)?.apps.orEmpty()
        return installed
            .filter { (label, _) -> QR_PH_APPS.any { labelMatches(label, it) } }
            .distinctBy { it.second }
            .map { (label, pkg) -> Wallet(label, pkg, receiver = own.any { labelMatches(label, it) }) }
            .sortedWith(compareByDescending<Wallet> { it.receiver }.thenBy { it.label.lowercase() })
    }
}
