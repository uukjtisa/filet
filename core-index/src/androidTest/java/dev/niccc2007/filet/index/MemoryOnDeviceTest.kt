package dev.niccc2007.filet.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Filet's file memory on a real filesystem, with real inodes, through the real crawl.
 *
 * The layout copies the phone's: the crawl root contains a `Filet` folder holding both the
 * index and the memory, exactly as `/storage/emulated/0/Filet` sits inside internal storage.
 * That is what makes the noise test meaningful - Filet's own files are inside what gets crawled.
 */
@RunWith(AndroidJUnit4::class)
class MemoryOnDeviceTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var index: SqliteIndex

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))
    private fun mem(f: File) = runBlocking { index.memoryOf(vpath(f)) }
    private fun crawl() = runBlocking { index.crawl(listOf(vpath(tmp)), budgetMs = 20_000) }.also {
        assertTrue("the crawl must complete for the sweep and relinks to run", it.complete)
    }

    /** The memory's single-thread writer runs the Vfs listener; give it a moment to drain. */
    private fun settle() = Thread.sleep(300)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "memory-test-" + System.nanoTime())
        File(tmp, "Download").mkdirs()
        File(tmp, "Documents").mkdirs()
        File(tmp, "Download/notes.txt").writeText("first")
        File(tmp, "Download/photo.jpg").writeText("jpeg")
        File(tmp, "Filet/.cache/Beat maps").mkdirs()
        File(tmp, "Filet/.cache/Beat maps/a.bin").writeText("x")

        // OsStats, as the app wires it: the inode is what makes a move CERTAIN.
        vfs = Vfs(listOf(LocalProvider(listOf(tmp), stats = dev.niccc2007.filet.vfs.provider.OsStats)))
        index = open(vfs)
    }

    private fun open(v: Vfs) = ApplicationProvider.getApplicationContext<android.content.Context>().let { context ->
        SqliteIndex(
            context, v,
            dbDir = File(tmp, "Filet/.cache/Search index").absolutePath,
            memoryDir = File(tmp, "Filet/Memory").absolutePath,
        )
    }

    @After fun tearDown() {
        if (::index.isInitialized) runCatching { index.close() }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    @Test fun the_memory_file_lands_in_its_folder() {
        crawl()
        assertTrue(File(tmp, "Filet/Memory/filet-memory.db").isFile)
    }

    @Test fun a_first_pass_records_files_as_already_here() {
        crawl()
        val m = mem(File(tmp, "Download/notes.txt"))?.own
        assertNotNull(m)
        assertTrue("the first look at a volume is a find, not an arrival", m!!.found)
        assertTrue(m.events.isEmpty())
    }

    @Test fun after_a_complete_pass_a_new_file_is_an_arrival() {
        crawl()
        Thread.sleep(1100) // a folder's mtime has one-second resolution on some filesystems
        File(tmp, "Download/new.txt").writeText("n")
        crawl()
        val m = mem(File(tmp, "Download/new.txt"))?.own
        assertNotNull(m)
        assertEquals(false, m!!.found)
    }

    @Test fun filets_own_storage_is_never_recorded_and_its_churn_makes_no_history() {
        crawl()
        assertNull(mem(File(tmp, "Filet/.cache/Beat maps/a.bin")))
        // The kinds of thing Filet does to its own files: write-then-rename, move between its
        // folders (the first-run migration), rename aside.
        runBlocking {
            vfs.rename(vpath(File(tmp, "Filet/.cache/Beat maps/a.bin")), "b.bin")
            vfs.move(vpath(File(tmp, "Filet/.cache/Beat maps/b.bin")), vpath(File(tmp, "Filet")))
        }
        File(tmp, "Filet/b.bin").renameTo(File(tmp, "Filet/b.bin.broken-1"))
        settle()
        crawl()
        assertNull(mem(File(tmp, "Filet/b.bin.broken-1")))
        assertNull(mem(File(tmp, "Filet")))
        // And nothing leaked onto a user file either.
        assertTrue(mem(File(tmp, "Download/notes.txt"))!!.own!!.events.isEmpty())
    }

    @Test fun a_rename_in_filet_is_one_history_entry_and_a_crawl_does_not_add_a_second() {
        crawl()
        val before = mem(File(tmp, "Download/notes.txt"))!!.own!!.firstSeen
        runBlocking { vfs.rename(vpath(File(tmp, "Download/notes.txt")), "notes-v2.txt") }
        settle()
        crawl()
        val m = mem(File(tmp, "Download/notes-v2.txt"))?.own
        assertNotNull(m)
        assertEquals(before, m!!.firstSeen)
        assertEquals(listOf(MemoryEventKind.RENAMED), m.events.map { it.kind })
        assertTrue(m.events[0].fromPath!!.endsWith("/Download/notes.txt"))
        assertNull("the old path is free", mem(File(tmp, "Download/notes.txt"))?.own)
    }

    @Test fun a_move_by_another_app_is_carried_on_inode() {
        crawl()
        val before = mem(File(tmp, "Download/photo.jpg"))!!.own!!.firstSeen
        Thread.sleep(1100)
        assertTrue(File(tmp, "Download/photo.jpg").renameTo(File(tmp, "Documents/photo.jpg")))
        assertEquals(1, crawl().movedFiles)
        val m = mem(File(tmp, "Documents/photo.jpg"))?.own
        assertNotNull(m)
        assertEquals("the lineage moved, so the arrival date did", before, m!!.firstSeen)
        assertEquals(listOf(MemoryEventKind.MOVED), m.events.map { it.kind })
    }

    @Test fun an_editor_saving_by_delete_and_recreate_keeps_the_arrival_and_logs_the_size() {
        crawl()
        val f = File(tmp, "Download/notes.txt")
        val before = mem(f)!!.own!!.firstSeen
        Thread.sleep(1100)
        assertTrue(f.delete())
        f.writeText("a much longer second version")
        crawl()
        val m = mem(f)!!.own!!
        assertEquals(before, m.firstSeen)
        assertEquals(listOf(MemoryEventKind.RESIZED), m.events.map { it.kind })
        assertEquals(5L, m.events[0].oldSize)
    }

    @Test fun deleted_then_a_new_file_with_the_same_name_is_a_new_lineage() {
        crawl()
        val f = File(tmp, "Download/notes.txt")
        Thread.sleep(1100)
        assertTrue(f.delete())
        crawl()
        assertNull("gone frees the path", mem(f)?.own)
        Thread.sleep(1100)
        f.writeText("someone else entirely")
        crawl()
        val m = mem(f)!!.own!!
        assertEquals(false, m.found)
        assertTrue("no history inherited from the file that was deleted", m.events.isEmpty())
    }

    @Test fun a_folder_moved_in_filet_carries_its_children() {
        crawl()
        val child = mem(File(tmp, "Download/notes.txt"))!!.own!!.firstSeen
        runBlocking { vfs.move(vpath(File(tmp, "Download")), vpath(File(tmp, "Documents"))) }
        settle()
        crawl()
        val moved = mem(File(tmp, "Documents/Download/notes.txt"))?.own
        assertNotNull(moved)
        assertEquals(child, moved!!.firstSeen)
        assertTrue(mem(File(tmp, "Documents/Download"))!!.own!!.events.any { it.kind == MemoryEventKind.MOVED })
    }

    @Test fun a_move_matched_only_by_name_and_size_starts_a_new_history() {
        // A backend with no inodes: the index still relinks on (name, size, mtime), which keeps
        // a shortcut alive, but that is a guess, and history does not follow a guess.
        index.close()
        val guessing = Vfs(listOf(LocalProvider(listOf(tmp))))
        index = open(guessing)
        crawl()
        Thread.sleep(1100)
        assertTrue(File(tmp, "Download/photo.jpg").renameTo(File(tmp, "Documents/photo.jpg")))
        assertEquals("the index still carries the id", 1, crawl().movedFiles)
        val m = mem(File(tmp, "Documents/photo.jpg"))?.own
        assertNotNull(m)
        assertTrue("no history inherited on a guess", m!!.events.isEmpty())
        assertNull(mem(File(tmp, "Download/photo.jpg"))?.own)
    }

    @Test fun a_page_of_first_seen_is_one_batch_and_skips_filets_own_files() {
        crawl()
        val asked = listOf(
            vpath(File(tmp, "Download/notes.txt")),
            vpath(File(tmp, "Download/photo.jpg")),
            vpath(File(tmp, "Download/never-existed.txt")),
            vpath(File(tmp, "Filet/.cache/Beat maps/a.bin")),
        )
        val got = runBlocking { index.firstSeenOf(asked) }
        assertEquals(setOf(asked[0], asked[1]), got.keys)
    }
}
