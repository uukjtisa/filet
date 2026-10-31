package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveEdits
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.EncryptionMethod
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Changing what is inside an archive, through the VFS, against real archives on disk.
 *
 * Every check reads the result back with a reader that is not Filet's - java.util.zip, or
 * commons-compress opened by hand - because an archive only Filet can read back is the bug
 * this round exists not to ship.
 */
class ArchiveWriteTest {

    private lateinit var tmp: File
    private lateinit var staged: File
    private lateinit var vfs: Vfs

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-awrite-" + System.nanoTime())
        staged = File(tmp, "staged")
        check(staged.mkdirs())
        vfs = Vfs(listOf(ArchiveProvider(stagedUnder = { listOf(staged.absolutePath) }), LocalProvider(listOf(tmp))))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun local(f: File): VPath = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))
    private fun mountOf(f: File): VPath = ArchiveProvider.mount(f.absolutePath.replace(File.separatorChar, '/'))

    private fun loose(name: String, body: String): File = File(tmp, name).apply { writeText(body) }

    private fun zip(name: String, entries: Map<String, ByteArray>, dir: File = tmp): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            // The fastest level, so a member recompressed at the default would come out a
            // different size and the raw-copy check below would see it.
            z.setLevel(1)
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return f
    }

    private fun zipText(name: String, vararg entries: Pair<String, String>, dir: File = tmp) =
        zip(name, entries.associate { (n, b) -> n to b.toByteArray() }, dir)

    private fun zipNames(f: File): List<String> = ZipFile(f).use { z -> z.entries().toList().map { it.name } }

    private fun zipText(f: File, name: String): String =
        ZipFile(f).use { z -> z.getInputStream(z.getEntry(name)!!).use { String(it.readBytes()) } }

    private fun leftBeside(f: File): List<String> =
        f.parentFile!!.list()!!.filter { it.startsWith(f.name + ".") }

    private fun spools(): Int =
        File(System.getProperty("java.io.tmpdir")).list()!!.count { it.startsWith("filet-put-") }

    @Test fun a_zip_says_it_can_be_written_and_its_members_do_too() = runTest {
        val f = zipText("w.zip", "top.txt" to "hello")
        assertTrue(vfs.canWrite(mountOf(f)))
        assertTrue(vfs.list(mountOf(f)).single().writable)
    }

    @Test fun a_file_copied_into_a_zip_is_in_it_and_the_rest_is_copied_as_it_was_stored() = runTest {
        val body = ByteArray(64 * 1024) { (it % 7).toByte() }
        val f = zip("raw.zip", mapOf("keep/a.bin" to body, "b.txt" to "bee".toByteArray()))
        val before = ZipFile(f).use { z -> z.getEntry("keep/a.bin")!!.let { Triple(it.compressedSize, it.crc, it.method) } }

        vfs.copy(local(loose("new.txt", "fresh")), mountOf(f))

        assertEquals(listOf("keep/a.bin", "b.txt", "new.txt"), zipNames(f))
        assertEquals("fresh", zipText(f, "new.txt"))
        ZipFile(f).use { z ->
            val e = z.getEntry("keep/a.bin")!!
            // Same compressed size, CRC and method: the stored bytes were carried over, not
            // inflated and deflated again.
            assertEquals(before, Triple(e.compressedSize, e.crc, e.method))
            assertArrayEquals(body, z.getInputStream(e).use { it.readBytes() })
        }
        assertTrue(leftBeside(f).isEmpty())
    }

    @Test fun the_listing_shows_what_a_rewrite_put_in() = runTest {
        val f = zipText("list.zip", "top.txt" to "hello")
        assertEquals(listOf("top.txt"), vfs.list(mountOf(f)).map { it.name })
        vfs.copy(local(loose("second.txt", "2")), mountOf(f))
        assertEquals(listOf("second.txt", "top.txt"), vfs.list(mountOf(f)).map { it.name }.sorted())
    }

    @Test fun a_folder_made_a_file_renamed_moved_and_a_folder_deleted_inside_a_zip() = runTest {
        val f = zipText("ops.zip", "top.txt" to "t", "docs/a.txt" to "alpha", "docs/deep/b.txt" to "beta")
        val root = mountOf(f)

        vfs.create(root.child("new"), isDir = true)
        vfs.rename(root.child("top.txt"), "renamed.txt")
        vfs.move(root.child("docs").child("a.txt"), root.child("new"))
        vfs.delete(root.child("docs"), recursive = true)

        assertEquals(listOf("new/", "new/a.txt", "renamed.txt"), zipNames(f).sorted())
        assertEquals("alpha", zipText(f, "new/a.txt"))
        assertEquals("t", zipText(f, "renamed.txt"))
    }

    @Test fun a_move_from_outside_lands_and_only_then_takes_the_original_away() = runTest {
        val f = zipText("in.zip", "top.txt" to "t")
        val src = loose("going.txt", "moved")
        vfs.move(local(src), mountOf(f))
        assertEquals("moved", zipText(f, "going.txt"))
        assertFalse(src.exists())
    }

    @Test fun a_folder_copied_in_arrives_whole() = runTest {
        val f = zipText("tree.zip", "top.txt" to "t")
        val dir = File(tmp, "album").apply { mkdirs() }
        File(dir, "one.txt").writeText("1")
        File(dir, "inner").mkdirs()
        File(dir, "inner/two.txt").writeText("2")
        vfs.copy(local(dir), mountOf(f))
        val names = zipNames(f)
        assertTrue(names.containsAll(listOf("album/", "album/one.txt", "album/inner/", "album/inner/two.txt")))
        assertEquals("2", zipText(f, "album/inner/two.txt"))
    }

    @Test fun a_batch_is_written_once_at_its_end() = runTest {
        val f = zipText("batch.zip", "top.txt" to "t")
        val original = f.readBytes()
        vfs.batch(mountOf(f)) {
            vfs.copy(local(loose("a.txt", "a")), mountOf(f))
            vfs.copy(local(loose("b.txt", "b")), mountOf(f))
            vfs.create(mountOf(f).child("c"), isDir = true)
            // Nothing on the disk yet, and the listing already shows all of it.
            assertArrayEquals(original, f.readBytes())
            assertEquals(listOf("a.txt", "b.txt", "c", "top.txt"), vfs.list(mountOf(f)).map { it.name }.sorted())
            assertEquals("a", vfs.openRead(mountOf(f).child("a.txt")).use { String(it.readBytes()) })
        }
        assertEquals(listOf("top.txt", "a.txt", "b.txt", "c/"), zipNames(f))
    }

    @Test fun a_batch_that_fails_part_way_changes_nothing_and_leaves_nothing_behind() = runTest {
        val f = zipText("all-or-none.zip", "top.txt" to "t")
        val original = f.readBytes()
        val spoolsBefore = spools()
        val e = runCatching {
            vfs.batch(mountOf(f)) {
                vfs.copy(local(loose("a.txt", "a")), mountOf(f))
                vfs.copy(local(loose("b.txt", "b")), mountOf(f))
                vfs.delete(mountOf(f).child("not-there.txt"))
            }
        }.exceptionOrNull()
        assertTrue("expected NotFound, got $e", e is VfsException.NotFound)
        assertArrayEquals(original, f.readBytes())
        assertTrue(leftBeside(f).isEmpty())
        assertEquals(spoolsBefore, spools())
    }

    @Test fun a_name_already_taken_in_the_batch_is_refused_at_the_item_that_took_it() = runTest {
        val f = zipText("clash.zip", "top.txt" to "t")
        val e = runCatching {
            vfs.batch(mountOf(f)) {
                vfs.create(mountOf(f).child("x"), isDir = true)
                vfs.create(mountOf(f).child("x"), isDir = true)
            }
        }.exceptionOrNull()
        assertTrue("expected AlreadyExists, got $e", e is VfsException.AlreadyExists)
        assertEquals(listOf("top.txt"), zipNames(f))
    }

    @Test fun a_tar_gz_takes_an_add_and_a_delete() = runTest {
        val f = File(tmp, "t.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(f.outputStream())).use { tar ->
            for ((n, b) in listOf("keep.txt" to "kept", "drop.txt" to "dropped")) {
                val bytes = b.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(n).apply { size = bytes.size.toLong(); mode = 0b111_101_101 })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        vfs.batch(mountOf(f)) {
            vfs.copy(local(loose("added.txt", "new")), mountOf(f))
            vfs.delete(mountOf(f).child("drop.txt"))
        }
        val back = LinkedHashMap<String, Pair<String, Int>>()
        TarArchiveInputStream(GzipCompressorInputStream(f.inputStream())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                back[e.name] = String(tar.readBytes()) to e.mode
            }
        }
        assertEquals(listOf("keep.txt", "added.txt"), back.keys.toList())
        assertEquals("kept", back["keep.txt"]!!.first)
        // Kept as it was stored, permissions included.
        assertEquals(0b111_101_101, back["keep.txt"]!!.second and 0b111_111_111)
        assertEquals("new", back["added.txt"]!!.first)
    }

    @Test fun a_7z_takes_an_add_and_a_delete() = runTest {
        val f = File(tmp, "s.7z")
        SevenZOutputFile(f).use { z ->
            for ((n, b) in listOf("keep.txt" to "kept", "drop.txt" to "dropped")) {
                val bytes = b.toByteArray()
                z.putArchiveEntry(SevenZArchiveEntry().apply { name = n; size = bytes.size.toLong() })
                z.write(bytes)
                z.closeArchiveEntry()
            }
        }
        vfs.batch(mountOf(f)) {
            vfs.copy(local(loose("added.txt", "new")), mountOf(f))
            vfs.delete(mountOf(f).child("drop.txt"))
        }
        val back = LinkedHashMap<String, String>()
        SevenZFile.builder().setFile(f).get().use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                back[e.name] = if (e.isDirectory) "" else z.getInputStream(e).use { String(it.readBytes()) }
            }
        }
        assertEquals(mapOf("keep.txt" to "kept", "added.txt" to "new"), back)
        assertTrue(leftBeside(f).isEmpty())
    }

    @Test fun a_lone_gz_says_it_can_be_replaced_and_nothing_else() = runTest {
        val f = File(tmp, "notes.txt.gz")
        GzipCompressorOutputStream(f.outputStream()).use { it.write("one".toByteArray()) }
        val why = vfs.writeRefusal(mountOf(f))
        assertNotNull(why)
        assertTrue(why!!, why.contains("Gzip") && why.contains("replace"))
        assertFalse(vfs.canWrite(mountOf(f)))
        val e = runCatching { vfs.copy(local(loose("other.txt", "x")), mountOf(f)) }.exceptionOrNull()
        assertTrue("expected Unsupported, got $e", e is VfsException.Unsupported)
    }

    @Test fun rar_is_refused_by_name() = runTest {
        val f = File(tmp, "photos.rar").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)) }
        val why = vfs.writeRefusal(mountOf(f))
        assertTrue("$why", why != null && why.contains("RAR"))
        assertFalse(vfs.canWrite(mountOf(f)))
    }

    @Test fun a_staged_copy_of_a_remote_archive_says_to_download_it_first() = runTest {
        val f = zipText("remote.zip", "top.txt" to "t", dir = staged)
        val original = f.readBytes()
        val why = vfs.writeRefusal(mountOf(f))
        assertTrue("$why", why != null && why.contains("Download"))
        assertFalse(vfs.canWrite(mountOf(f)))
        val e = runCatching { vfs.copy(local(loose("a.txt", "a")), mountOf(f)) }.exceptionOrNull()
        assertTrue("expected Unsupported, got $e", e is VfsException.Unsupported)
        assertArrayEquals(original, f.readBytes())
    }

    @Test fun the_editor_saving_into_a_staged_copy_is_refused_with_the_same_sentence() = runTest {
        val f = zipText("remote.zip", "notes.txt" to "old", dir = staged)
        val original = f.readBytes()
        val member = mountOf(f).child("notes.txt")
        val why = ArchiveEdits.refusalFor(member, listOf(staged.absolutePath))
        assertEquals(vfs.writeRefusal(mountOf(f)), why)
        // Without the staging folders it is an ordinary zip, which is what made the save go through.
        assertEquals(null, ArchiveEdits.refusalFor(member))
        val e = runCatching {
            ArchiveEdits.replace(member, listOf(staged.absolutePath)) { ByteArrayInputStream("new".toByteArray()) }
        }.exceptionOrNull()
        assertTrue("$e", e is java.io.IOException && e.message == why)
        assertArrayEquals(original, f.readBytes())
    }

    @Test fun an_encrypted_zip_is_refused_before_anything_is_copied() = runTest {
        val f = File(tmp, "locked.zip")
        val bytes = "secret".toByteArray()
        val src = ArchiveSource("inside.txt", isDir = false, size = bytes.size.toLong(), mtime = 1_700_000_000_000L) {
            ByteArrayInputStream(bytes)
        }
        f.outputStream().use {
            ArchiveWriter.writeToStream(
                Archives.ALL.first { a -> a.id == "zip" }, it, listOf(src),
                ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_256),
            )
        }
        val original = f.readBytes()
        val why = vfs.writeRefusal(mountOf(f))
        assertTrue("$why", why != null && why.contains("encrypted"))
        assertFalse(vfs.canWrite(mountOf(f)))
        val spoolsBefore = spools()
        val e = runCatching { vfs.copy(local(loose("a.txt", "a")), mountOf(f)) }.exceptionOrNull()
        assertTrue("expected Unsupported, got $e from ${e?.cause}", e is VfsException.Unsupported)
        // Refused at the first write, before a byte of the file was spooled.
        assertEquals(spoolsBefore, spools())
        assertArrayEquals(original, f.readBytes())
    }

    @Test fun stopped_while_the_archive_is_rewritten_it_is_left_as_it_was() = runBlocking {
        val random = Random(7)
        val f = zip("big.zip", (1..8).associate { "part$it.bin" to random.nextBytes(512 * 1024) })
        val original = f.readBytes()
        val add = loose("small.txt", "s")
        lateinit var job: Job
        var seen = false
        job = launch(kotlinx.coroutines.Dispatchers.Default) {
            vfs.batch(mountOf(f), onCommit = { at -> if (at > 0f && !seen) { seen = true; job.cancel() } }) {
                vfs.copy(local(add), mountOf(f))
            }
        }
        job.join()
        assertTrue("the rewrite never reported progress", seen)
        assertTrue(job.isCancelled)
        assertArrayEquals(original, f.readBytes())
        assertTrue(leftBeside(f).isEmpty())
        // And it still opens, with nothing of the stopped edit in it.
        assertEquals(8, zipNames(f).size)
    }

    @Test fun stop_reads_as_a_stop_and_not_as_an_error() = runBlocking {
        val random = Random(9)
        val f = zip("stop.zip", (1..4).associate { "p$it.bin" to random.nextBytes(512 * 1024) })
        var thrown: Throwable? = null
        lateinit var job: Job
        job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                vfs.batch(mountOf(f), onCommit = { at -> if (at > 0f) job.cancel() }) {
                    vfs.copy(local(loose("x.txt", "x")), mountOf(f))
                }
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.join()
        assertTrue("expected a cancellation, got $thrown", thrown is CancellationException)
    }
}
