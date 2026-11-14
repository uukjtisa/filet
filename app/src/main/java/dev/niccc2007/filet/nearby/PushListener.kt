package dev.niccc2007.filet.nearby

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The inbox's own door, open while Filet is on screen and sharing is off, so pushes from other
 * Filets arrive whenever Filet is open and not only while sharing.
 *
 * It answers the inbox's three paths and `/api/info`, and nothing else: no file is reachable
 * through it. While sharing is on the share's own server answers the same paths and this is shut.
 *
 * @param handle the inbox: the JSON to answer for a path, or null for one that is not the inbox's.
 * @param info what `/api/info` says, so a phone that finds this one can name it.
 */
class PushListener(
    private val handle: (path: String, body: JSONObject, address: String) -> JSONObject?,
    private val info: () -> JSONObject,
) {
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: 0
    val isOpen: Boolean get() = socket?.isClosed == false

    /** Opens on the first free port from [from]; returns the port, or 0 when none would open. */
    fun open(from: Int = FIRST_PORT): Int {
        if (isOpen) return port
        for (p in from until from + 10) {
            val s = runCatching { ServerSocket(p) }.getOrNull() ?: continue
            socket = s
            Thread({ accept(s) }, "filet-inbox").apply { isDaemon = true }.start()
            return p
        }
        return 0
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            pool.execute { runCatching { c.use { serve(it) } } }
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 10_000
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val parts = line(input)?.split(' ') ?: return
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var length = 0
        while (true) {
            val h = line(input) ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val address = c.inetAddress?.hostAddress ?: "?"
        val answer = when {
            path == "/api/info" -> info()
            method == "POST" && length in 1..MAX_BODY -> {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                runCatching { JSONObject(String(body, 0, read)) }.getOrNull()?.let { handle(path, it, address) }
            }
            else -> null
        }
        respond(out, answer)
    }

    private fun respond(out: OutputStream, answer: JSONObject?) {
        val bytes = (answer ?: JSONObject().put("error", "not found")).toString().toByteArray()
        val status = if (answer == null) "404 Not Found" else "200 OK"
        out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            if (b.size() > 8192) return null
            b.write(c)
        }
    }

    companion object {
        const val FIRST_PORT = 8790
        /** A push's description of its files; anything bigger is not one. */
        const val MAX_BODY = 256 * 1024
    }
}
