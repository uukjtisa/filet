package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A paste, a move and a delete into an archive: one rewrite, all of it or none, and a move's
 * originals kept until the archive holds them.
 */
class ArchivePasteTest {

    private lateinit var tmp: File
    private lateinit var ops: FileOperations
    private lateinit var zip: File

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-apaste-" + System.nanoTime())
        check(tmp.mkdirs())
        ops = FileOperations(Vfs(listOf(LocalProvider(listOf(tmp)), ArchiveProvider())), JobLedger())
        zip = File(tmp, "box.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for ((n, b) in listOf("top.txt" to "t", "old/one.txt" to "1", "old/two.txt" to "2")) {
                z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
        }
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun local(f: File): VPath = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))
    private fun root(): VPath = ArchiveProvider.mount(zip.absolutePath.replace(File.separatorChar, '/'))
    private fun loose(name: String, body: String, dir: File = tmp) = File(dir, name).apply { parentFile!!.mkdirs(); writeText(body) }
    private fun names(): List<String> = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }

    @Test fun three_files_pasted_into_a_zip_all_land() = runTest {
        val r = ops.copy(listOf(loose("a.txt", "a"), loose("b.txt", "b"), loose("c.txt", "c")).map(::local), root())
        assertEquals(3, r.succeeded)
        assertTrue(r.ok)
        assertTrue(names().containsAll(listOf("a.txt", "b.txt", "c.txt", "top.txt")))
    }

    @Test fun one_bad_item_in_a_paste_writes_none_of_it_and_the_report_names_it() = runTest {
        val before = zip.readBytes()
        val missing = File(tmp, "gone.txt")
        val r = ops.copy(listOf(local(loose("a.txt", "a")), local(missing), local(loose("c.txt", "c"))), root())
        assertEquals(0, r.succeeded)
        assertEquals(3, r.failed.size)
        // First in the list, because the toast is built from the first failure.
        assertEquals("gone.txt", r.failed.first().first.name)
        val why = r.failed.first().second
        assertTrue(why, why.contains("gone.txt") && why.contains("nothing was written"))
        assertArrayEquals(before, zip.readBytes())
    }

    @Test fun two_files_with_one_name_in_one_paste_get_two_names() = runTest {
        val r = ops.copy(listOf(local(loose("a.txt", "first", File(tmp, "x"))), local(loose("a.txt", "second", File(tmp, "y")))), root())
        assertEquals(2, r.succeeded)
        assertTrue(names().containsAll(listOf("a.txt", "a (2).txt")))
    }

    @Test fun a_move_into_a_zip_removes_the_originals_once_they_are_in_it() = runTest {
        val a = loose("a.txt", "a")
        val b = loose("b.txt", "b")
        val r = ops.move(listOf(local(a), local(b)), root())
        assertEquals(2, r.succeeded)
        assertTrue(names().containsAll(listOf("a.txt", "b.txt")))
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test fun a_move_that_fails_part_way_keeps_every_original() = runTest {
        val before = zip.readBytes()
        val a = loose("a.txt", "a")
        val r = ops.move(listOf(local(a), local(File(tmp, "gone.txt"))), root())
        assertEquals(0, r.succeeded)
        // Bug identified here, in advance: the copy waits for the archive to be written at the
        // end, and a move that deleted each original as it went would have lost this one.
        assertTrue(a.exists())
        assertArrayEquals(before, zip.readBytes())
    }

    @Test fun a_move_inside_the_zip_is_a_rename_and_takes_nothing_from_outside() = runTest {
        val r = ops.move(listOf(root().child("top.txt")), root().child("old"))
        assertEquals(1, r.succeeded)
        assertEquals(listOf("old/one.txt", "old/top.txt", "old/two.txt"), names().sorted())
    }

    @Test fun deleting_from_a_zip_is_one_rewrite_and_all_or_none() = runTest {
        val r = ops.delete(listOf(root().child("old").child("one.txt"), root().child("top.txt")))
        assertEquals(2, r.succeeded)
        assertEquals(listOf("old/two.txt"), names())

        val before = zip.readBytes()
        val bad = ops.delete(listOf(root().child("old").child("two.txt"), root().child("nope.txt")))
        assertEquals(0, bad.succeeded)
        assertEquals("nope.txt", bad.failed.first().first.name)
        assertTrue(bad.failed.first().second.contains("nothing was deleted"))
        assertArrayEquals(before, zip.readBytes())
    }

    @Test fun a_move_out_of_a_zip_lands_and_takes_them_out_in_one_go() = runTest {
        val out = File(tmp, "out").apply { mkdirs() }
        val r = ops.move(listOf(root().child("top.txt"), root().child("old").child("one.txt")), local(out))
        assertEquals(2, r.succeeded)
        assertEquals("t", File(out, "top.txt").readText())
        assertEquals("1", File(out, "one.txt").readText())
        assertEquals(listOf("old/two.txt"), names())
    }

    @Test fun a_move_out_that_fails_part_way_moves_nothing_and_leaves_no_copies() = runTest {
        val out = File(tmp, "out").apply { mkdirs() }
        val before = zip.readBytes()
        val r = ops.move(listOf(root().child("top.txt"), root().child("nope.txt")), local(out))
        assertEquals(0, r.succeeded)
        assertEquals("nope.txt", r.failed.first().first.name)
        assertTrue(r.failed.first().second.contains("nothing was moved"))
        assertArrayEquals(before, zip.readBytes())
        assertTrue(out.list()!!.isEmpty())
    }

    @Test fun a_file_a_failed_move_out_was_replacing_is_put_back() = runTest {
        val out = File(tmp, "out").apply { mkdirs() }
        File(out, "top.txt").writeText("was here first")
        val r = ops.move(listOf(root().child("top.txt"), root().child("nope.txt")), local(out), Conflict.OVERWRITE)
        assertEquals(0, r.succeeded)
        // Bug identified here, in advance: removing the copies on the way out took the file the
        // copy had replaced with them.
        assertEquals("was here first", File(out, "top.txt").readText())
        assertEquals(listOf("top.txt"), out.list()!!.toList())
    }

    @Test fun a_move_out_that_replaces_a_file_leaves_only_the_new_one() = runTest {
        val out = File(tmp, "out").apply { mkdirs() }
        File(out, "top.txt").writeText("old")
        val r = ops.move(listOf(root().child("top.txt")), local(out), Conflict.OVERWRITE)
        assertEquals(1, r.succeeded)
        assertEquals("t", File(out, "top.txt").readText())
        assertEquals(listOf("top.txt"), out.list()!!.toList())
        assertFalse(names().contains("top.txt"))
    }

    @Test fun a_move_from_one_zip_to_another_takes_them_out_of_the_first() = runTest {
        val other = File(tmp, "other.zip")
        ZipOutputStream(other.outputStream()).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write("x".toByteArray()); z.closeEntry() }
        val target = ArchiveProvider.mount(other.absolutePath.replace(File.separatorChar, '/'))
        val r = ops.move(listOf(root().child("top.txt"), root().child("old")), target)
        assertEquals(2, r.succeeded)
        val there = ZipFile(other).use { z -> z.entries().toList().map { it.name } }
        assertTrue(there.containsAll(listOf("x.txt", "top.txt", "old/one.txt", "old/two.txt")))
        assertTrue(names().isEmpty())
    }
}
