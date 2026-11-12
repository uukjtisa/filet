package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.webdav.DavDiscovery
import dev.niccc2007.filet.webdav.FiletHello
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finding phones that mDNS did not, by asking every address on this PC's subnets.
 *
 * mDNS is passive and often blocked - guest Wi-Fi, routers that drop multicast between bands, a
 * phone whose new address is not announced yet while the old record is still cached. Each Filet
 * listener answers `GET /.well-known/filet` with its device id, name and live shares (see
 * [FiletHello]), so a sweep turns "some address on this network" back into "that phone".
 *
 * Bounded on purpose: at most a /24 per adapter, so 253 hosts, times a handful of ports, 48 at a
 * time with a [TIMEOUT_MS] connect and read. A wider subnet is narrowed to the /24 this PC is in
 * rather than swept whole - a /16 is 65,000 knocks, which is a port scan and not a lookup.
 */
object Sweep {
    const val TIMEOUT_MS = 250
    const val PARALLEL = 48
    /** The port a phone's sharing prefers; saved phones add the ports they were last seen on. */
    const val PHONE_PORT = 8321

    /** One answer, as [FiletHello.json] wrote it. */
    data class Hello(val id: String, val name: String, val kind: String, val port: Int, val shares: List<HelloShare>)
    data class HelloShare(val label: String, val scope: String, val writable: Boolean)

    /**
     * Every (host, port) to ask: each host on each adapter's subnet, capped to a /24, never this
     * PC's own - and every address in [known] as well.
     *
     * [known] is where devices have been seen before: saved drives' hosts and every address a
     * share was heard on. Those are asked first, and asked even when they sit outside this PC's
     * /24, which a device on a wider subnet or a routed second network does.
     */
    fun targets(subnets: List<HostAddress>, ports: Set<Int>, known: Collection<String> = emptyList()): List<Pair<String, Int>> {
        val own = subnets.mapNotNull { v4(it.ip) }.toSet()
        val hosts = LinkedHashSet<Long>()
        for (k in known) v4(k)?.takeIf { it !in own }?.let { hosts += it }
        for (c in subnets) {
            val ip = v4(c.ip) ?: continue
            val prefix = c.prefix.coerceIn(24, 30)
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val net = ip and mask
            val last = net or (mask.inv() and 0xFFFFFFFFL)
            for (h in net + 1 until last) if (h !in own) hosts += h
        }
        return hosts.flatMap { h -> ports.sorted().map { dotted(h) to it } }
    }

    /** An answer body read back, or null when it is not a Filet answer. */
    fun parse(body: String): Hello? = runCatching {
        val o = JSONObject(body)
        if (o.optInt("v", 0) < 1) return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val arr = o.optJSONArray("shares")
        val shares = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val s = arr!!.optJSONObject(i) ?: return@mapNotNull null
            HelloShare(s.optString("label"), s.optString("scope"), s.optBoolean("w", false)).takeIf { it.label.isNotBlank() }
        }
        Hello(id, o.optString("name"), o.optString("kind"), o.optInt("port", 0), shares)
    }.getOrNull()

    /**
     * The shares in [h], as the peers mDNS would have listed for them.
     *
     * The name is rebuilt the way the beacon builds a service name, so a share found by the sweep
     * and the same share heard over mDNS are one row (see [Peers.same]), not two.
     */
    fun peersOf(h: Hello, host: String, port: Int): List<Peer> = h.shares.map { s ->
        Peer(
            name = DavDiscovery.displayName(DavDiscovery.serviceName(h.name, s.label)),
            host = host, port = port, secure = false, base = "/a/",
            scope = s.scope, writable = s.writable, deviceId = h.id,
        )
    }

    /** Ask one address. Blocking, at most about twice [TIMEOUT_MS]. */
    fun ask(host: String, port: Int): Hello? = runCatching {
        val c = URL("http", host, port, FiletHello.PATH).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        try {
            if (c.responseCode != 200) return null
            parse(c.inputStream.use { String(it.readNBytes(MAX_BODY), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Ask every target and return the shares found, leaving out this PC's own [selfId]. */
    fun run(subnets: List<HostAddress>, ports: Set<Int>, selfId: String, known: Collection<String> = emptyList()): List<Peer> {
        val targets = targets(subnets, ports, known)
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r, "filet-sweep").apply { isDaemon = true } }
        return try {
            val jobs = targets.map { (h, p) -> Callable { ask(h, p)?.let { hello -> Triple(h, p, hello) } } }
            pool.invokeAll(jobs, 30, TimeUnit.SECONDS)
                .mapNotNull { f -> runCatching { f.get() }.getOrNull() }
                .filter { (_, _, hello) -> hello.id != selfId }
                .flatMap { (h, p, hello) -> peersOf(hello, h, p) }
        } finally {
            pool.shutdownNow()
        }
    }

    private const val MAX_BODY = 64 * 1024

    private fun v4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (p in parts) n = (n shl 8) or (p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toLong()
        return n
    }

    private fun dotted(n: Long) = "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"
}
