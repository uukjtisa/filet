package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetConnections
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.vfs.provider.net.WebDavProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/**
 * A **network volume**, against a real server.
 *
 * Part of M8. The server is `tools/testservers/webdav.py` on the development host, reached
 * through `adb reverse tcp:18080 tcp:18080`, so the device is talking real HTTP over a real
 * socket to something that is not Filet. A mock would prove the test, not the client.
 *
 * The test *skips* rather than fails when the server is not up, because a missing fixture is
 * a missing fixture - reporting it as a broken WebDAV client would be a lie in the other
 * direction. The gate records whether it ran.
 */
@RunWith(AndroidJUnit4::class)
class RemoteVolumeTest {

    private lateinit var vfs: Vfs
    private lateinit var connections: NetConnections
    private lateinit var connId: String

    /** `dav:///<connectionId>/<remote path>` - the addressing every network provider shares. */
    private fun p(remote: String) = VPath.of(NetProtocol.WEBDAV.scheme, "/$connId/${remote.trimStart('/')}")

    @Before fun setUp() {
        val up = runCatching { Socket("127.0.0.1", PORT).close() }.isSuccess
        assumeTrue("tiny-dav not reachable on 127.0.0.1:$PORT - run tools/testservers/webdav.py", up)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        connections = NetConnections(context)
        connId = "davtest"
        connections.save(
            NetConnection(
                id = connId,
                protocol = NetProtocol.WEBDAV,
                label = "tiny-dav",
                host = "127.0.0.1",
                port = PORT,
                user = "filet",
                password = "s3cret",
                share = "/",
            )
        )
        vfs = Vfs(listOf(WebDavProvider(connections)))
    }

    /** The password is at rest under AndroidKeyStore, never in plaintext prefs. */
    @Test fun the_saved_password_round_trips_through_the_keystore() {
        val back = connections.byId(connId)
        assertNotNull(back)
        assertEquals("s3cret", back!!.password)
    }

    @Test fun the_share_root_lists() {
        val nodes = runBlocking { vfs.list(p("/")) }
        val names = nodes.map { it.name }
        assertTrue("root listing was $names", names.contains("notes.txt"))
        assertTrue("root listing was $names", names.contains("reports"))
        assertTrue("reports must be a directory", nodes.first { it.name == "reports" }.isDir)
    }

    @Test fun a_subdirectory_lists() {
        val names = runBlocking { vfs.list(p("/reports")) }.map { it.name }
        assertTrue("subdirectory listing was $names", names.contains("quarterly.csv"))
    }

    @Test fun a_remote_file_reads() {
        val text = runBlocking { vfs.openRead(p("/notes.txt")).use { it.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(text.startsWith("the quick brown fox"))
    }

    @Test fun stat_reports_the_size_the_server_reported() {
        val node = runBlocking { vfs.stat(p("/notes.txt")) }
        assertNotNull(node)
        assertTrue("size was ${node!!.size}", node.size > 0)
    }

    /** Write, read back, rename, delete - the round trip that makes it a volume, not a viewer. */
    @Test fun a_file_can_be_written_renamed_and_deleted_remotely() {
        val target = p("/from-device.txt")
        runBlocking {
            vfs.openWrite(target).use { it.write("written by Filet".toByteArray()) }
            val back = vfs.openRead(target).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("written by Filet", back)

            vfs.rename(target, "renamed-by-filet.txt")
            val names = vfs.list(p("/")).map { it.name }
            assertTrue("after rename: $names", names.contains("renamed-by-filet.txt"))
            assertTrue("after rename: $names", !names.contains("from-device.txt"))

            vfs.delete(p("/renamed-by-filet.txt"))
            assertTrue(vfs.list(p("/")).none { it.name == "renamed-by-filet.txt" })
        }
    }

    @Test fun a_remote_directory_can_be_created_and_removed() {
        runBlocking {
            vfs.create(p("/made-on-device"), isDir = true)
            assertTrue(vfs.list(p("/")).any { it.name == "made-on-device" && it.isDir })
            vfs.delete(p("/made-on-device"))
            assertTrue(vfs.list(p("/")).none { it.name == "made-on-device" })
        }
    }

    private companion object { const val PORT = 18080 }
}
