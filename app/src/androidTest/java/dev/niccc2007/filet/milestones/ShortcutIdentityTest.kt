package dev.niccc2007.filet.milestones

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.shortcuts.ShortcutRouterActivity
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.shortcuts.Shortcuts
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4's exit criterion.
 *
 * > A pinned shortcut still opens its file after that file has been moved (PLAN.md §8, M4).
 *
 * > A shortcut never stores a path. It stores a Filet file ID, and Filet resolves ID to the
 * > current path at launch (PLAN.md L5).
 *
 * This exercises the resolution `ShortcutRouterActivity` performs - `index.pathFor(id)` then
 * `vfs.stat` - against the app's **real** graph and its real index, not a fixture instance.
 * The launcher half (tapping the icon) is driven from the host against this same ID, which
 * the test prints to logcat.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutIdentityTest {

    private lateinit var dir: File
    private lateinit var archive: File
    private lateinit var file: File
    private lateinit var path: VPath

    private val graph get() = FiletApp.graphOf(ApplicationProvider.getApplicationContext())

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        dir = Gates.dir(ApplicationProvider.getApplicationContext())
        assumeTrue("gate storage is not writable in this run", dir.isDirectory)
        archive = File(dir, "archive").apply { mkdirs() }
        file = File(dir, "pinned-${System.nanoTime()}.txt")
        file.writeText("the file a shortcut points at")
        path = vpath(file)

        // `available` means "something has been crawled", not "the index works" - it is false
        // on a fresh install. Crawl first, then require it, so a genuinely dead index (the
        // NullIndex fallback) still skips instead of failing as if the feature were broken.
        crawl()
        assumeTrue("the index is unavailable on this device", graph.index.status.value.available)
    }

    @After fun tearDown() {
        if (::file.isInitialized) {
            runCatching { runBlocking { graph.index.forget(path) } }
            file.delete()
            File(archive, file.name).delete()
        }
        // m4-launch-target.txt is NOT cleaned up: the launcher-side check runs after the
        // suite has finished and needs the file its id resolves to still to exist.
    }

    private fun crawl() = runBlocking { graph.index.crawl(listOf(vpath(dir)), budgetMs = 30_000) }

    @Test fun a_pinned_shortcut_still_opens_its_file_after_the_file_moves() {
        crawl()
        val id = runBlocking { graph.index.idFor(path) }
        assertNotNull("a file must have a stable id before it can be pinned", id)
        android.util.Log.i(TAG, "FILE_ID=$id name=${file.name}")

        // What the launcher stores. Note what it does NOT store.
        val shortcutId = Shortcuts.shortcutId(id, path)
        assertTrue("a shortcut id must not embed a path: $shortcutId", !shortcutId.contains("/storage"))

        // The move.
        val moved = File(archive, file.name)
        assertTrue(file.renameTo(moved))
        crawl()

        // Resolution, exactly as ShortcutRouterActivity does it.
        val resolved = runBlocking { graph.index.pathFor(id!!) }
        assertNotNull("the pinned id stopped resolving after a move - the shortcut would die", resolved)
        assertEquals(vpath(moved), resolved)
        assertNotEquals(path, resolved)

        val node = runBlocking { graph.vfs.stat(resolved!!) }
        assertNotNull("resolution is only a claim until the filesystem agrees", node)
        assertEquals(file.name, node!!.name)

        // Put it back so tearDown finds it where it expects.
        moved.renameTo(file)
    }

    /**
     * Leaves a live id on the device for the launcher-side half of the gate.
     *
     * The hop `am start` -> [dev.niccc2007.filet.shortcuts.ShortcutRouterActivity] -> the
     * browser is the part only a real launch can show, and it needs a file that is still
     * there afterwards - so this one is deliberately not cleaned up.
     */
    @Test fun a_live_shortcut_target_is_left_for_the_launcher_check() {
        val target = File(dir, "m4-launch-target.txt")
        target.writeText("the file a pinned shortcut points at")
        val targetPath = vpath(target)
        crawl()

        val id = runBlocking { graph.index.idFor(targetPath) }
        assertNotNull("the launcher target has no stable id", id)

        val moved = File(archive, target.name)
        assertTrue(target.renameTo(moved))
        crawl()
        val resolved = runBlocking { graph.index.pathFor(id!!) }
        assertEquals(vpath(moved), resolved)

        Gates.write(
            File(dir, "m4-shortcut.txt"),
            buildString {
                appendLine("M4 - a pinned shortcut after its file moved")
                appendLine("device:    " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                appendLine("file id:   " + id)
                appendLine("pinned at: " + targetPath.path)
                appendLine("now at:    " + resolved!!.path)
                appendLine("shortcut id stores no path: " + Shortcuts.shortcutId(id, targetPath))
                appendLine()
                appendLine("launcher half:")
                appendLine("  adb shell am start -n dev.niccc2007.filet.debug/dev.niccc2007.filet.shortcuts.ShortcutRouterActivity \\")
                appendLine("    --el filet.fileId " + id)
            },
        )
    }

    /**
     * The other half of R1: when the file is really gone the shortcut must fail *honestly*,
     * not resolve to a stale path.
     */
    @Test fun a_deleted_file_resolves_to_nothing_rather_than_to_a_stale_path() {
        crawl()
        val id = runBlocking { graph.index.idFor(path) }!!
        assertTrue(file.delete())
        crawl()

        val resolved = runBlocking { graph.index.pathFor(id) }
        val node = resolved?.let { runBlocking { graph.vfs.stat(it) } }
        assertNull("a deleted file must not still be reachable through its id", node)

        file.writeText("restored for teardown")
    }

    /**
     * The launcher hop, for real: an Intent into
     * [dev.niccc2007.filet.shortcuts.ShortcutRouterActivity] carrying nothing but a number,
     * and the browser opening on the file's *current* location.
     *
     * An `ActivityMonitor` catches the browser as it starts, which is the only way to read
     * the Intent the router built - and the Intent is the thing under test, since that is
     * where the resolved path appears.
     */
    @Test fun launching_a_shortcut_by_id_opens_the_browser_at_the_current_path() {
        crawl()
        val id = runBlocking { graph.index.idFor(path) }!!
        val moved = File(archive, file.name)
        assertTrue(file.renameTo(moved))
        crawl()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            ctx.startActivity(
                Intent(ctx, ShortcutRouterActivity::class.java)
                    .putExtra(Shortcuts.EXTRA_FILE_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            val opened = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("the shortcut never reached the browser", opened)
            val target = opened!!.intent.getStringExtra(ShortcutRouterActivity.EXTRA_TARGET)
            assertEquals(
                "the shortcut opened the old path - the whole point is that it does not",
                vpath(moved).toString(),
                target,
            )
            instrumentation.runOnMainSync { opened.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            moved.renameTo(file)
        }
    }

    private companion object { const val TAG = "FiletShortcutGate" }
}
