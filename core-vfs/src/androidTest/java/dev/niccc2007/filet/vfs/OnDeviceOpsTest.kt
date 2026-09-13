package dev.niccc2007.filet.vfs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.niccc2007.filet.vfs.provider.AndroidStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G9 — the six M0 operations against REAL Android storage on a real (emulated) device.
 *
 * The JVM tests prove the logic; this proves the platform actually permits it, which is a
 * different claim. Runs in the app's own external files dir so it needs no special grant and
 * cannot damage anything outside itself.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceOpsTest {

    private lateinit var vfs: Vfs
    private lateinit var sandbox: VPath

    @Before fun setUp() { runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        vfs = Vfs(listOf(AndroidStorage.localProvider(ctx)))
        val base = ctx.getExternalFilesDir(null)!!.absolutePath
        sandbox = VPath.of("local", "$base/m0-gate")
        if (vfs.stat(sandbox) != null) vfs.delete(sandbox, recursive = true)
        vfs.create(sandbox, isDir = true)
    } }

    @After fun tearDown() { runBlocking {
        runCatching { vfs.delete(sandbox, recursive = true) }
    } }

    @Test fun browses_real_internal_storage() { runBlocking {
        val roots = vfs.roots()
        assertTrue("no storage roots reported", roots.isNotEmpty())
        // Listing the shared volume is the thing a file manager exists to do.
        val shared = roots.first()
        val entries = vfs.list(shared.path)
        assertTrue("shared volume listed as empty", entries.isNotEmpty())
    } }

    @Test fun mkdir_rename_copy_move_delete_all_work_on_device() { runBlocking {
        // mkdir
        val dir = sandbox.child("work")
        vfs.create(dir, isDir = true)
        assertTrue(vfs.stat(dir)!!.isDir)

        // write + read
        val src = dir.child("note.txt")
        vfs.openWrite(src).use { it.write("filet on device".toByteArray()) }
        assertEquals("filet on device", vfs.openRead(src).use { String(it.readBytes()) })

        // rename
        val renamed = vfs.rename(src, "renamed.txt")
        assertEquals("renamed.txt", renamed.name)
        assertNull(vfs.stat(src))

        // copy into a sibling directory
        val dest = sandbox.child("dest")
        vfs.create(dest, isDir = true)
        vfs.copy(renamed.path, dest)
        assertNotNull(vfs.stat(dest.child("renamed.txt")))
        assertNotNull(vfs.stat(renamed.path))          // copy leaves the source

        // move
        val moved = sandbox.child("moved")
        vfs.create(moved, isDir = true)
        vfs.move(renamed.path, moved)
        assertNotNull(vfs.stat(moved.child("renamed.txt")))
        assertNull(vfs.stat(renamed.path))             // move does not

        // recursive delete
        vfs.delete(dest, recursive = true)
        assertNull(vfs.stat(dest))
    } }

    @Test fun copy_of_a_directory_tree_survives_real_io() { runBlocking {
        val tree = sandbox.child("tree")
        vfs.create(tree, isDir = true)
        vfs.create(tree.child("inner"), isDir = true)
        vfs.openWrite(tree.child("inner").child("deep.bin")).use { it.write(ByteArray(150_000)) }
        val out = sandbox.child("out")
        vfs.create(out, isDir = true)

        vfs.copy(tree, out)

        assertEquals(150_000L, vfs.stat(out.child("tree").child("inner").child("deep.bin"))!!.size)
    } }
}
