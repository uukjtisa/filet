package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.script.ScriptEngine
import dev.niccc2007.filet.script.ScriptPermissions
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetConnections
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.vfs.provider.net.WebDavProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M5's exit criterion.
 *
 * > A Lua script processes a file on a share it has never seen, proving the script API is
 * > bound to the VFS and not to `java.io.File` (PLAN.md §8, M5).
 *
 * Two volumes, neither of which has a `java.io.File` the script could have reached by
 * accident:
 *
 * - **inside a zip**, where `zip:///x.zip!/inner.txt` names bytes that exist nowhere on disk;
 * - **a WebDAV share**, where the file is on another machine entirely.
 *
 * The same unmodified script runs over both. That is the proof: nothing in the script, and
 * nothing in the engine, knows which provider is underneath.
 */
@RunWith(AndroidJUnit4::class)
class ScriptOnVolumesTest {

    private lateinit var tmp: File
    private lateinit var engine: ScriptEngine
    private lateinit var vfs: Vfs
    private lateinit var connections: NetConnections
    private val connId = "davscript"

    /**
     * Counts lines in whatever it is pointed at and writes a receipt beside it. Deliberately
     * ordinary: the interesting part is the address it is given, not what it does.
     */
    private val script = """
        local src = args[1]
        local out = args[2]
        local text = fs.read(src)
        local lines = 0
        for _ in string.gmatch(text, "[^\n]+") do lines = lines + 1 end
        fs.write(out, "lines=" .. lines .. " from=" .. src .. "\n")
        print("counted " .. lines)
    """.trimIndent()

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))
    private fun dav(remote: String) = VPath.of(NetProtocol.WEBDAV.scheme, "/$connId/${remote.trimStart('/')}")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "script-volumes-" + System.nanoTime()).apply { mkdirs() }

        val zip = File(tmp, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("docs/inner.txt"))
            zos.write("alpha\nbeta\ngamma\n".toByteArray())
            zos.closeEntry()
        }

        connections = NetConnections(context)
        connections.save(
            NetConnection(
                id = connId, protocol = NetProtocol.WEBDAV, label = "tiny-dav",
                host = "127.0.0.1", port = PORT, user = "filet", password = "s3cret", share = "/",
            )
        )

        vfs = Vfs(listOf(LocalProvider(listOf(tmp)), ArchiveProvider(), WebDavProvider(connections)))
        engine = ScriptEngine(vfs)
    }

    @After fun tearDown() {
        if (::connections.isInitialized) runCatching { connections.delete(connId) }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    /** Volume one: bytes inside an archive. There is no path on disk for `docs/inner.txt`. */
    @Test fun a_script_reads_a_file_that_only_exists_inside_an_archive() {
        val inner = VPath.of(
            "zip",
            File(tmp, "bundle.zip").absolutePath.replace(File.separatorChar, '/') + "!/docs/inner.txt",
        )
        val receipt = vpath(File(tmp, "receipt.txt"))

        val result = runBlocking {
            engine.run(
                script,
                ScriptPermissions(read = listOf(inner, vpath(tmp)), write = listOf(vpath(tmp))),
                args = listOf(inner.toString(), receipt.toString()),
            )
        }

        assertTrue("script failed: ${result.error}\n${result.output}", result.ok)
        assertTrue(result.output.contains("counted 3"))
        assertEquals("lines=3 from=$inner", File(tmp, "receipt.txt").readText().trim())
    }

    /** Volume two: a share on another machine. The gate's own wording. */
    @Test fun a_script_processes_a_file_on_a_share_it_has_never_seen() {
        assumeTrue(
            "tiny-dav not reachable on 127.0.0.1:$PORT - run tools/testservers/webdav.py",
            runCatching { Socket("127.0.0.1", PORT).close() }.isSuccess,
        )

        val src = dav("/notes.txt")
        val out = dav("/counted-by-script.txt")
        runCatching { runBlocking { vfs.delete(out) } }

        val result = runBlocking {
            engine.run(
                script,
                ScriptPermissions(read = listOf(dav("/")), write = listOf(dav("/"))),
                args = listOf(src.toString(), out.toString()),
            )
        }

        assertTrue("script failed: ${result.error}\n${result.output}", result.ok)
        assertTrue(result.output.contains("counted 2"))

        // Read the receipt back *through the network provider*, so the assertion travels the
        // same path the write did rather than peeking at the server's disk.
        val written = runBlocking { vfs.openRead(out).use { it.readBytes().toString(Charsets.UTF_8) } }
        assertEquals("lines=2 from=$src", written.trim())
        runBlocking { vfs.delete(out) }
    }

    /**
     * The permission model is addressed the same way. A script granted the archive is still
     * refused the share - being on the VFS does not mean being allowed everywhere on it.
     */
    @Test fun permissions_are_per_volume_not_per_device() {
        assumeTrue(runCatching { Socket("127.0.0.1", PORT).close() }.isSuccess)
        val result = runBlocking {
            engine.run(
                "print(fs.read(args[1]))",
                ScriptPermissions(read = listOf(vpath(tmp)), write = emptyList()),
                args = listOf(dav("/notes.txt").toString()),
            )
        }
        assertTrue("a script reached a share it was not granted", !result.ok)
    }

    private companion object { const val PORT = 18080 }
}
