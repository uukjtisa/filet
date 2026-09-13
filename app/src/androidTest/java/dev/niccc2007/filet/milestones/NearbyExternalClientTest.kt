package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.nearby.NearbyHttpServer
import dev.niccc2007.filet.nearby.SharedSet
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M9's second clause, with a client that is genuinely not Filet.
 *
 * > A browser with no Filet installed downloads a shared file from the served URL
 * > (PLAN.md §8, M9).
 *
 * `NearbyServerTest` already drives the server over a real socket, but from inside the same
 * process - which proves the protocol and not the premise. Here the server is started on the
 * phone and left running while **the development host** fetches from it through
 * `adb forward`, using curl. Nothing on that side knows anything about Filet.
 *
 * The handshake is two files in shared storage, because the host cannot call into a running
 * instrumented test:
 *
 * 1. this test writes `nearby-session.txt` (port, PIN, the file it is offering) and waits;
 * 2. the host reads it, fetches, and writes `nearby-done.txt` with what it got;
 * 3. this test reads that back and checks it against what it actually served.
 *
 * It **skips** when the host is not playing along, so the suite stays runnable on its own.
 * `tools/nearby-client.sh` is the other half.
 */
@RunWith(AndroidJUnit4::class)
class NearbyExternalClientTest {

    private lateinit var tmp: File
    private lateinit var shared: SharedSet
    private lateinit var server: NearbyHttpServer
    private lateinit var gates: File
    private lateinit var session: File
    private lateinit var done: File

    private val body = "This file crossed the network to a browser that has never heard of Filet.\n"

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        gates = Gates.dir(context)
        session = File(gates, "nearby-session.txt")
        done = File(gates, "nearby-done.txt")
        assumeTrue("gate storage is not writable in this run", gates.isDirectory)
        assumeTrue(
            "no host-side client is waiting - run tools/nearby-client.sh alongside this test",
            File(gates, "nearby-client-ready.txt").isFile,
        )

        tmp = File(context.cacheDir, "nearby-external-" + System.nanoTime())
        val folder = File(tmp, "Shared").apply { mkdirs() }
        File(folder, "hello-from-filet.txt").writeText(body)

        val vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        shared = SharedSet(
            context, vfs, Prefs(context),
            folder = VPath.of("local", folder.absolutePath.replace(File.separatorChar, '/')),
            quarantine = VPath.of("local", File(tmp, "Received").absolutePath.replace(File.separatorChar, '/')),
        )
        server = NearbyHttpServer(context, vfs, shared, { "Filet on a phone" }, {})
    }

    @After fun tearDown() {
        if (::server.isInitialized) runCatching { server.stop() }
        if (::tmp.isInitialized) tmp.deleteRecursively()
        runCatching { session.delete() }
    }

    @Test fun a_client_that_is_not_filet_downloads_a_shared_file() {
        done.delete()
        val state = server.start(preferredPort = 18400)
        val token = runBlocking { shared.listing() }
            .first { it.name == "hello-from-filet.txt" }.token

        Gates.write(
            session,
            buildString {
                appendLine("port=" + state.port)
                appendLine("pin=" + server.pin)
                appendLine("token=" + token)
                appendLine("name=hello-from-filet.txt")
            },
        )

        // Wait for the host. Ninety seconds is generous for a curl over adb forward, and the
        // failure message has to say which side went quiet or this is impossible to debug.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !done.isFile) Thread.sleep(250)
        assertTrue("the host-side client never reported back", done.isFile)

        val report = done.readText()
        assertTrue("host reported: $report", report.contains("status=200"))
        assertEquals(
            "the bytes the host received are not the bytes that were shared",
            body.trim(),
            report.substringAfter("body<<<").substringBefore(">>>").trim(),
        )
        // The unlocked listing must also have been readable by that same outside client.
        assertTrue("host could not read the listing: $report", report.contains("listed=hello-from-filet.txt"))

        // Keep what the outside client actually saw, as the gate's evidence.
        Gates.write(
            File(gates, "m9-external-client.txt"),
            buildString {
                appendLine("M9 - a client with no Filet installed, fetching over adb forward")
                appendLine(
                    "device:  " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                        " (API " + android.os.Build.VERSION.SDK_INT + ")"
                )
                appendLine(
                    "served:  hello-from-filet.txt on port " + server.port +
                        ", PIN " + server.pin.length + " digits"
                )
                appendLine()
                appendLine(report)
            },
        )
        done.delete()
    }
}
