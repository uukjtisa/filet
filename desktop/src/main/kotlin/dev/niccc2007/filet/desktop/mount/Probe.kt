package dev.niccc2007.filet.desktop.mount

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Space on a share, as the share reports it (RFC 4331).
 *
 * Trap handled here: **Windows reports the local disk's free space for a WebDAV drive.** The
 * redirector ignores `quota-available-bytes` entirely, so a phone with 2 GB left shows in
 * Explorer with whatever the PC's C: has, and a copy that cannot fit starts anyway and fails near
 * the end. Nothing on the server side can change what Explorer draws, so the tool reads the real
 * figure itself and shows it on the mount row - at least one place on the PC tells the truth.
 */
data class Space(val usedBytes: Long, val availableBytes: Long) {
    val totalBytes: Long get() = usedBytes + availableBytes
}

sealed interface ProbeResult {
    data class Ok(val space: Space?) : ProbeResult
    data class Refused(val message: String, val status: Int) : ProbeResult
    data object Unreachable : ProbeResult
}

object Probe {
    private val BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:quota-used-bytes/><D:quota-available-bytes/></D:prop></D:propfind>"""

    /**
     * Built once. `java.net.http` rather than `HttpURLConnection` because the latter refuses any
     * method outside its own fixed list and PROPFIND is not on it - the usual workaround is
     * reflection into a private field, which the module system now blocks.
     */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    /** Blocking. One PROPFIND, Depth 0, against the share's root. */
    fun check(target: DavTarget): ProbeResult = try {
        val req = HttpRequest.newBuilder(URI(target.url))
            // Ten seconds, not six: a phone's Wi-Fi in power save can take several to wake for a
            // packet, and a probe that gives up first reports a sleeping phone as a gone one.
            .timeout(Duration.ofSeconds(10))
            .header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(BODY))
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        val refusal = ProbeErrors.message(res.statusCode())
        if (refusal == null) ProbeResult.Ok(parseSpace(res.body()))
        else ProbeResult.Refused(refusal, res.statusCode())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ProbeResult.Unreachable
    } catch (t: Throwable) {
        // Throwable, not Exception: a packaged runtime missing `java.net.http` throws
        // NoClassDefFoundError here, and that must cost the space figure, not the window.
        ProbeResult.Unreachable
    }

    /**
     * The two quota figures from a multistatus body, or null when either is absent.
     *
     * Matched on local name with any namespace prefix: the phone writes `D:` but nothing in the
     * protocol fixes the prefix, and a parser keyed on `D:` reads nothing from a server that
     * chose `d:` or a default namespace.
     */
    fun parseSpace(xml: String): Space? {
        fun read(name: String): Long? =
            Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>\\s*(-?\\d+)\\s*</(?:[A-Za-z0-9_]+:)?$name>")
                .find(xml)?.groupValues?.get(1)?.toLongOrNull()
        val used = read("quota-used-bytes") ?: return null
        val avail = read("quota-available-bytes") ?: return null
        if (used < 0 || avail < 0) return null
        return Space(used, avail)
    }

    /** `12.3 GB free of 64 GB`, in decimal units because that is what Explorer and phones show. */
    fun describe(space: Space): String = "${size(space.availableBytes)} free of ${size(space.totalBytes)}"

    fun size(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return if (i == 0 || v >= 100) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
    }
}
