package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.jobs.JobKind
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** A file another Filet offers. */
data class PushFile(val name: String, val size: Long, val dir: Boolean)

/** What a fast share holds, read before fetching it: the sharing phone's name and its files. */
data class FastPeek(val device: String, val files: List<PushFile>)

/**
 * Keys with work running, so the same work is never started twice at once. Thread-safe: a fetch
 * ends on the IO pool and the next one is asked for on the main thread.
 */
internal class InFlight<K> {
    private val running = HashSet<K>()

    /** True, and [key] is now running; false when it already was, and nothing should start. */
    @Synchronized fun begin(key: K): Boolean = running.add(key)

    @Synchronized fun end(key: K) {
        running.remove(key)
    }
}

/**
 * Another Filet on this network offering files: a fast share of its own, which this phone fetches
 * with [key] from [base] once it is accepted.
 *
 * @param from the sender's name on the network, the one its Devices row is known by.
 * @param paired the sender proved it holds the pairing this phone made with it, so Always accept
 *   may be offered: a phone cannot borrow a trusted phone's name.
 */
data class PushOffer(
    val from: String,
    val name: String,
    val base: String,
    val key: String,
    val files: List<PushFile>,
    val at: Long,
    val paired: Boolean,
) {
    val id: String get() = "$from|$key"
    val title: String get() = files.singleOrNull()?.name ?: "${files.size} files"
}

/** Another Filet asking to pair: both phones show [code], and each person checks it matches. */
data class PairAsk(val from: String, val name: String, val token: String, val code: String)

/** Whether another Filet's push pops up. */
enum class Popups { ON, OFF_HOUR, OFF }

/** What happens to a push as it arrives. */
enum class PushAnswer { BLOCKED, ACCEPTED, ASK, HELD }

/** The push rules, apart from the network so they can be tested. */
object PushRules {
    /** An unanswered push leaves the list after this at the latest, as AirDrop's Everyone mode does. */
    const val STALE_MS = 10 * 60 * 1000L

    /** How often a waiting push asks its sender whether it is still offered. */
    const val CHECK_MS = 15_000L

    /** Missed checks in a row that mean the sender cancelled, left the Wi-Fi or closed Filet. */
    const val MISSES = 2

    fun popups(stored: String?, now: Long): Popups = when {
        stored == null || stored == "on" -> Popups.ON
        stored == "off" -> Popups.OFF
        stored.startsWith("until:") -> if (now < (stored.removePrefix("until:").toLongOrNull() ?: 0)) Popups.OFF_HOUR else Popups.ON
        else -> Popups.ON
    }

    fun answer(blocked: Boolean, always: Boolean, paired: Boolean, popupsOn: Boolean): PushAnswer = when {
        blocked -> PushAnswer.BLOCKED
        always && paired -> PushAnswer.ACCEPTED
        popupsOn -> PushAnswer.ASK
        else -> PushAnswer.HELD
    }

    fun stale(at: Long, now: Long, misses: Int): Boolean = now - at > STALE_MS || misses >= MISSES

    /** What a paired sender sends to prove it holds the pairing, without sending the pairing itself. */
    fun proof(token: String, key: String): String =
        MessageDigest.getInstance("SHA-256").digest("$token:$key".toByteArray()).joinToString("") { "%02x".format(it) }

    /** [name] made safe to save, and kept clear of what is already there. */
    fun landName(name: String, taken: (String) -> Boolean): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }.take(120)
        if (!taken(safe)) return safe
        val stem = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        for (i in 2..999) {
            val next = if (ext.isEmpty() || stem == safe) "$stem ($i)" else "$stem ($i).$ext"
            if (!taken(next)) return next
        }
        return "$stem (${System.currentTimeMillis()})" + (if (ext.isEmpty()) "" else ".$ext")
    }
}

/**
 * Pushes from other Filets: asked about in a pop-up, taken without asking from a paired phone set
 * to Always accept, turned away from a blocked one, and held in the Devices list while pop-ups are
 * off. What is accepted is fetched into Filet/Received as an Activity job, whose finished card
 * says where it landed and opens it.
 */
class PushInbox(
    private val context: Context,
    private val vfs: Vfs,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val received: VPath,
    private val ledger: JobLedger?,
    /** This phone's name, sent with every fetch so the sender's Activity says who downloaded. */
    private val selfName: () -> String,
) {
    private val _offers = MutableStateFlow<List<PushOffer>>(emptyList())

    /** Every push waiting for an answer, oldest first. */
    val offers: StateFlow<List<PushOffer>> = _offers.asStateFlow()

    private val _asking = MutableStateFlow<PushOffer?>(null)

    /** The push the pop-up is asking about now. */
    val asking: StateFlow<PushOffer?> = _asking.asStateFlow()

    private val _pairing = MutableStateFlow<PairAsk?>(null)

    /** Another Filet asking to pair, for its own pop-up. */
    val pairing: StateFlow<PairAsk?> = _pairing.asStateFlow()

    private val _popups = MutableStateFlow(PushRules.popups(prefs.getString(KEY_POPUPS), System.currentTimeMillis()))
    val popups: StateFlow<Popups> = _popups.asStateFlow()

    private val _blocked = MutableStateFlow(readSet(KEY_BLOCKED))
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private val _always = MutableStateFlow(readSet(KEY_ALWAYS))
    val always: StateFlow<Set<String>> = _always.asStateFlow()

    private val misses = HashMap<String, Int>()
    private var checking: Job? = null

    // ── settings ──

    /** On, off for an hour (back on by itself), or off. Covers every phone, paired ones too. */
    fun setPopups(p: Popups) {
        val stored = when (p) {
            Popups.ON -> null
            Popups.OFF -> "off"
            Popups.OFF_HOUR -> "until:${System.currentTimeMillis() + HOUR_MS}"
        }
        prefs.putString(KEY_POPUPS, stored)
        _popups.value = p
        if (p == Popups.ON) _asking.value = _asking.value ?: _offers.value.firstOrNull()
    }

    /** Pop-ups are on now: an hour's Off that has run out reads as On. */
    fun popupsOn(): Boolean {
        val now = PushRules.popups(prefs.getString(KEY_POPUPS), System.currentTimeMillis())
        if (now != _popups.value) _popups.value = now
        return now == Popups.ON
    }

    /** When an hour's Off turns itself back on, for the Devices line. */
    fun popupsBackAt(): Long = prefs.getString(KEY_POPUPS)?.removePrefix("until:")?.toLongOrNull() ?: 0

    fun block(from: String, on: Boolean) {
        _blocked.value = if (on) _blocked.value + from else _blocked.value - from
        writeSet(KEY_BLOCKED, _blocked.value)
        if (on) _offers.value.filter { it.from == from }.forEach { drop(it) }
    }

    fun setAlways(from: String, on: Boolean) {
        _always.value = if (on) _always.value + from else _always.value - from
        writeSet(KEY_ALWAYS, _always.value)
    }

    // ── from the network ──

    /**
     * A request to the inbox, from either server: `/api/push`, `/api/push/cancel` or `/api/pair`.
     * Answers the JSON to send back, or null for a path that is not the inbox's.
     */
    fun handle(path: String, body: JSONObject, address: String): JSONObject? = when (path) {
        "/api/push" -> JSONObject().put("answer", offer(body, address).name.lowercase())
        "/api/push/cancel" -> { cancel(body.optString("from"), body.optString("key")); JSONObject().put("ok", true) }
        "/api/pair" -> { askPair(body); JSONObject().put("ok", true) }
        else -> null
    }

    private fun offer(o: JSONObject, address: String): PushAnswer {
        val from = o.optString("from").take(80)
        val key = o.optString("key").take(40)
        val port = o.optInt("port")
        if (from.isEmpty() || key.isEmpty() || port !in 1..65535) return PushAnswer.BLOCKED
        val files = o.optJSONArray("files") ?: JSONArray()
        val list = (0 until minOf(files.length(), 500)).map { i ->
            val f = files.getJSONObject(i)
            PushFile(f.optString("name").take(200), f.optLong("size"), f.optBoolean("dir"))
        }
        if (list.isEmpty()) return PushAnswer.BLOCKED
        // The address is the one the request came from, never one the request names, so a push
        // can only send this phone to fetch from the phone that sent it.
        val token = PairedPeers.tokenFor(context, from)
        val paired = token != null && o.optString("proof") == PushRules.proof(token, key)
        val offer = PushOffer(from, o.optString("name").ifEmpty { from.removePrefix("Filet-") }.take(40), "http://" + NetHost.authority(address, port), key, list, System.currentTimeMillis(), paired)
        val answer = PushRules.answer(from in _blocked.value, from in _always.value, paired, popupsOn())
        when (answer) {
            PushAnswer.BLOCKED -> log("Turned away a push from ${offer.name}", "blocked on the Share page")
            PushAnswer.ACCEPTED -> fetch(offer.base, offer.key, offer.name)
            PushAnswer.ASK, PushAnswer.HELD -> {
                _offers.value = _offers.value.filterNot { it.id == offer.id } + offer
                if (answer == PushAnswer.ASK && _asking.value == null) _asking.value = offer
                keepChecking()
            }
        }
        return answer
    }

    private fun cancel(from: String, key: String) {
        _offers.value.firstOrNull { it.from == from && it.key == key }?.let { drop(it) }
    }

    private fun askPair(o: JSONObject) {
        val from = o.optString("from").take(80)
        val token = o.optString("token").take(100)
        if (from.isEmpty() || token.length < 16 || from in _blocked.value) return
        val self = PairedPeers.serviceName(context)
        _pairing.value = PairAsk(from, o.optString("name").ifEmpty { from.removePrefix("Filet-") }.take(40), token, PairedPeers.shortCode(self, from, token))
    }

    // ── answers ──

    /** The pop-up's Accept. Always accept is kept only for a paired phone. */
    fun accept(offer: PushOffer, always: Boolean) {
        if (always && offer.paired) setAlways(offer.from, true)
        drop(offer)
        fetch(offer.base, offer.key, offer.name)
    }

    fun decline(offer: PushOffer) {
        drop(offer)
        log("Declined ${offer.title} from ${offer.name}")
    }

    /** The pop-up closed without an answer: the push stays in the Devices list. */
    fun later() { _asking.value = null }

    /** A held push's row on the Share page, tapped: the pop-up for it. */
    fun show(offer: PushOffer) { _asking.value = offer }

    fun answerPair(ask: PairAsk, ok: Boolean) {
        if (ok) PairedPeers.remember(context, ask.from, ask.name, ask.token)
        _pairing.value = null
    }

    private fun drop(offer: PushOffer) {
        _offers.value = _offers.value.filterNot { it.id == offer.id }
        misses.remove(offer.id)
        if (_asking.value?.id == offer.id) _asking.value = if (popupsOn()) _offers.value.firstOrNull() else null
    }

    /**
     * A waiting push leaves within about 30 s of its sender cancelling, leaving the Wi-Fi or
     * closing Filet (two missed checks), and after ten minutes at the latest.
     */
    private fun keepChecking() {
        if (checking?.isActive == true) return
        checking = scope.launch(Dispatchers.IO) {
            while (isActive && _offers.value.isNotEmpty()) {
                delay(PushRules.CHECK_MS)
                val now = System.currentTimeMillis()
                for (o in _offers.value) {
                    val alive = runCatching { get("${o.base}/api/fast?k=${enc(o.key)}").first == 200 }.getOrDefault(false)
                    val m = if (alive) 0 else (misses[o.id] ?: 0) + 1
                    misses[o.id] = m
                    if (PushRules.stale(o.at, now, m)) drop(o)
                }
            }
        }
    }

    // ── fetching ──

    private val fetching = InFlight<String>()

    /**
     * Fetch what a fast share at [base] with [key] offers into Filet/Received, as one Activity job:
     * from an accepted push, or from Filet's own scanner reading a fast share's QR. A folder
     * arrives as its zip.
     *
     * @return false, having started nothing, when this share is already being fetched. A second
     *   copy of the same files is never what was meant - it was a repeated tap or a repeated scan.
     */
    fun fetch(base: String, key: String, fromName: String): Boolean {
        val once = "$base|$key"
        if (!fetching.begin(once)) return false
        val l = ledger
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var id = -1L
            try {
                val (code, text) = get("$base/api/fast?k=${enc(key)}")
                if (code != 200) error("$fromName stopped sharing it")
                val m = JSONObject(text)
                val who = m.optString("device").ifEmpty { fromName }
                val arr = m.optJSONArray("files") ?: JSONArray()
                val files = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (files.isEmpty()) error("nothing is left in it")
                val total = files.sumOf { it.optLong("size").coerceAtLeast(0) }
                val first = files.first().optString("name")
                runCatching { if (vfs.stat(received) == null) vfs.create(received, isDir = true) }
                // Every name chosen before the first byte, clear of what is there and of each other.
                val there = runCatching { vfs.list(received).map { it.name }.toHashSet() }.getOrElse { hashSetOf() }
                val targets = files.map { f ->
                    val name = f.optString("name") + if (f.optBoolean("dir")) ".zip" else ""
                    received.child(PushRules.landName(name) { it in there }.also { there += it })
                }
                id = l?.start(
                    title = if (files.size == 1) "Receiving $first" else "Receiving ${files.size} files",
                    subtitle = "from $who",
                    source = "Nearby",
                    cancel = job,
                    // One file opens where it is; several open the folder they landed in.
                    opened = (targets.singleOrNull() ?: received).toString(),
                    kind = JobKind.RECEIVED,
                    from = base,
                    to = received.toString(),
                    totalItems = files.size,
                ) ?: -1L
                var done = 0L
                files.forEachIndexed { i, f ->
                    val token = f.optString("token")
                    val dir = f.optBoolean("dir")
                    val target = targets[i]
                    val name = target.name
                    val url = if (dir) "$base/zd/${enc(token)}?k=${enc(key)}" else "$base/f/${enc(token)}?k=${enc(key)}"
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 30_000
                        setRequestProperty("X-Filet-Name", selfName())
                    }
                    try {
                        if (conn.responseCode != 200) error("$name: ${conn.responseCode}")
                        conn.inputStream.use { input ->
                            vfs.openWrite(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (!isActive) error("stopped")
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (id >= 0) l?.progress(id, if (total > 0) done.toFloat() / total else null, name, doneItems = i, doneBytes = done, totalBytes = total)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (id >= 0) l?.finish(id, "Filet/Received")
            } catch (e: Throwable) {
                if (id >= 0) l?.fail(id, e.message ?: "could not be fetched") else log("Could not fetch from $fromName", e.message ?: "")
            }
        }
        // On completion rather than in a finally: a job cancelled before it starts never runs its
        // body, and the share would then count as fetching for as long as Filet lives.
        job.invokeOnCompletion { fetching.end(once) }
        job.start()
        return true
    }

    /**
     * What the fast share at [base] holds, asked of the sharing phone. Blocking; null when it has
     * stopped sharing, holds nothing, or cannot be reached.
     */
    fun peek(base: String, key: String): FastPeek? = runCatching {
        val (code, text) = get("$base/api/fast?k=${enc(key)}")
        if (code != 200) return null
        val m = JSONObject(text)
        val arr = m.optJSONArray("files") ?: JSONArray()
        val files = (0 until minOf(arr.length(), 500)).map { i ->
            val f = arr.getJSONObject(i)
            PushFile(f.optString("name").take(200), f.optLong("size"), f.optBoolean("dir"))
        }
        FastPeek(m.optString("device").take(40), files).takeIf { files.isNotEmpty() }
    }.getOrNull()

    private fun log(title: String, detail: String = "") {
        val l = ledger ?: return
        val id = l.start(title = title, subtitle = detail, source = "Nearby", kind = JobKind.OTHER)
        l.finish(id, detail)
    }

    private fun readSet(key: String): Set<String> = runCatching {
        val a = JSONArray(prefs.getString(key) ?: return emptySet())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrElse { emptySet() }

    private fun writeSet(key: String, set: Set<String>) =
        prefs.putString(key, if (set.isEmpty()) null else JSONArray(set.sorted()).toString())

    companion object {
        const val KEY_POPUPS = "nearby.popups"
        const val KEY_BLOCKED = "nearby.blocked"
        const val KEY_ALWAYS = "nearby.alwaysAccept"
        private const val HOUR_MS = 60 * 60 * 1000L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** A GET's status and body, read whole: for the small JSON answers only. */
        fun get(url: String): Pair<Int, String> {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
            }
            return try {
                val code = conn.responseCode
                code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
        }

        /** A POST of [body] as JSON; the answer's JSON, or null when the phone did not answer. */
        /** How a POST went: its reply, or which half of it failed. */
        sealed interface Posted {
            data class Reply(val json: JSONObject) : Posted
            /** The connection itself failed: nothing at that address took it. */
            data object Unreached : Posted
            /** Connected and sent; no reply that could be read came back. */
            data object Unanswered : Posted
        }

        /**
         * [post], telling a phone that was never reached from one that took the request and did
         * not reply in time - the second may well have acted on it.
         */
        fun postFor(url: String, body: JSONObject): Posted {
            val conn = runCatching {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }.getOrElse { return Posted.Unreached }
            try {
                runCatching { conn.connect() }.onFailure { return Posted.Unreached }
                return runCatching {
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    Posted.Reply(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                }.getOrElse { Posted.Unanswered }
            } finally {
                conn.disconnect()
            }
        }

        fun post(url: String, body: JSONObject): JSONObject? = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
