package dev.niccc2007.filet.portal

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two things Portal says to a host: a multipart upload streamed at a fixed length, so a large
 * file never sits in memory, and a plain download.
 */
object PortalHttp {
    /** Hosts ask for an honest client name; this is the app, not a person. */
    private const val AGENT = "Filet"

    /** Upload [length] bytes written by [body] as the file of [req]; returns the host's answer. */
    fun upload(req: PortalHost.Upload, fileName: String, length: Long, body: (OutputStream) -> Unit): String {
        val boundary = "filet" + PortalCrypto.keyText(PortalCrypto.newKey()).filter { it.isLetterOrDigit() }.take(24)
        val head = buildString {
            for ((k, v) in req.fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"${req.fileField}\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setFixedLengthStreamingMode(head.size + length + tail.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", AGENT)
        }
        try {
            BufferedOutputStream(conn.outputStream, 64 * 1024).use { out ->
                out.write(head)
                body(out)
                out.write(tail)
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { read(it, 64 * 1024) }.orEmpty()
            if (code !in 200..299) throw IOException("the host answered $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Download [url] into [read]; returns what [read] returns. Throws on anything but a 2xx. */
    fun <T> fetch(url: String, read: (InputStream) -> T): T {
        if (!PortalHost.anyOwns(url)) throw IOException("not a Portal host")
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("the host answered ${conn.responseCode}")
            return conn.inputStream.use(read)
        } finally {
            conn.disconnect()
        }
    }

    /** [url]'s bytes, up to [limit]; null when the host no longer has it. */
    fun fetchBytes(url: String, limit: Long): ByteArray? {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 404 || code == 410) return null
            if (code !in 200..299) throw IOException("the host answered $code")
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IOException("too large to be a Portal list")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", AGENT)
    }

    private fun read(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
