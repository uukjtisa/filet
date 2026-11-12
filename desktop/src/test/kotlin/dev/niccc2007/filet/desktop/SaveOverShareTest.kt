package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.webdav.DavShare
import dev.niccc2007.filet.webdav.WebDavServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.URLEncoder
import java.nio.file.Files

/**
 * The phone's text editor saving over a file on a PC share, request for request.
 *
 * The editor never writes straight over a file: it puts the new bytes beside it, reads them back,
 * sets the original aside, swaps the new one in, and deletes the old. Each step below is the exact
 * request the phone's WebDAV client sends for it.
 */
class SaveOverShareTest {

    private lateinit var dir: File
    private lateinit var server: WebDavServer
    private val log = StringBuilder()

    @Before
    fun up() {
        dir = Files.createTempDirectory("filet save ").toFile()
        server = WebDavServer(
            vfs = Vfs(listOf(LocalProvider(rootDirs = emptyList()))),
            rootFor = { null },
            onEvent = {},
        )
        server.preferredPort = 0
        // Bare, as the PC's config stores a whole-drive share: `C:`, no separator.
        val drive = dir.toPath().root.toString().trimEnd('\\', '/')
        server.shares = listOf(
            DavShare(1, "Folder", customRoot = HostService.folderVPath(dir.path).toString(), writable = true, views = false, idleMinutes = 0),
            DavShare(2, "Drive", customRoot = HostService.folderVPath(drive).toString(), writable = true, views = false, idleMinutes = 0),
        )
        server.start(1)
        server.start(2)
    }

    @After
    fun down() {
        server.stop()
        dir.deleteRecursively()
        println(log)
    }

    @Test
    fun `a save over a file in a shared folder`() = save(shareId = 1, prefix = "")

    @Test
    fun `a save over a file on a shared drive root`() {
        val under = dir.path.substring(dir.toPath().root.toString().length).replace('\\', '/')
        save(shareId = 2, prefix = "$under/")
    }

    private fun save(shareId: Long, prefix: String) {
        val code = server.state().shares.first { it.share.id == shareId }.code
        val name = "notes v2.txt"
        File(dir, name).writeText("old text")
        fun url(rel: String) = "/a/$code/" + (prefix + rel).split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val temp = ".filet-saving-$name"
        val aside = ".filet-original-$name"
        val dest = { rel: String -> "http://127.0.0.1:${server.port}" + url(rel) }

        assertEquals(200, call("GET", url(name)).first)
        assertEquals(404, call("PROPFIND", url(temp), mapOf("Depth" to "0")).first)
        assertEquals(201, call("PUT", url(temp), body = "new text").first)
        assertEquals("new text", call("GET", url(temp)).second)
        assertEquals(404, call("PROPFIND", url(aside), mapOf("Depth" to "0")).first)
        assertEquals(201, call("MOVE", url(name), mapOf("Destination" to dest(aside), "Overwrite" to "F")).first)
        assertEquals(201, call("MOVE", url(temp), mapOf("Destination" to dest(name), "Overwrite" to "F")).first)
        assertEquals(204, call("DELETE", url(aside)).first)
        assertEquals("new text", File(dir, name).readText())
    }

    /** One request on its own connection, the way the phone's client sends it. */
    private fun call(method: String, target: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10_000
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: Filet\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (body != null || method == "PUT") head.append("Content-Length: ${bytes.size}\r\n")
            head.append("\r\n")
            s.getOutputStream().apply { write(head.toString().toByteArray()); write(bytes); flush() }
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = text.substringAfter(' ').substringBefore(' ').toInt()
            log.append("$method ${target.substringAfterLast('/')} -> $code\n")
            return code to text.substringAfter("\r\n\r\n")
        }
    }
}
