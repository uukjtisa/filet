package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveEditor
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.EditMode
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Changing one file inside an archive.
 *
 * Two claims, and they need completely different assertions.
 *
 * 1. **It works** - the member really changes and nothing else does. A round trip catches that.
 * 2. **Nothing else was recompressed** - which a round trip cannot see at all, because a
 *    re-deflated archive reads back identically to a raw-copied one. So the untouched entries'
 *    COMPRESSED bytes are compared, which is the only thing that can tell a raw copy from a
 *    fast re-deflate.
 *
 * And the one that protects somebody's data: an interrupted save must leave the original
 * exactly as it was. An archive is usually the only copy of what is in it.
 */
class ArchiveEditTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-edit-" + System.nanoTime())
        check(tmp.mkdirs())
        vfs = Vfs(listOf(ArchiveProvider()))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun sources(): List<ArchiveSource> = buildList {
        add(ArchiveSource("docs", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        for (i in 1..8) {
            // Compressible, so "was it re-deflated" is a question with a visible answer.
            val body = ("line $i of a file that compresses well\n").repeat(200).toByteArray()
            add(
                ArchiveSource("docs/f$i.txt", isDir = false, size = body.size.toLong(), mtime = 1_700_000_000_000L) {
                    ByteArrayInputStream(body)
                },
            )
        }
        val note = "before".toByteArray()
        add(
            ArchiveSource("note.txt", isDir = false, size = note.size.toLong(), mtime = 1_700_000_000_000L) {
                ByteArrayInputStream(note)
            },
        )
    }

    private suspend fun build(id: String): File {
        val format = Archives.ALL.first { it.id == id }
        val file = File(tmp, "archive${format.suffix}")
        if (ArchiveWriter.needsRealFile(format)) ArchiveWriter.writeToFile(format, file, sources())
        else file.outputStream().use { ArchiveWriter.writeToStream(format, it, sources()) }
        return file
    }

    private suspend fun read(file: File, member: String): String {
        val root = ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/'))
        val path = member.split('/').fold(root) { acc, seg -> acc.child(seg) }
        return vfs.openRead(path).use { it.readBytes().decodeToString() }
    }

    /** name -> (compressed size, crc) for every entry. The fingerprint of the stored bytes. */
    private fun rawFingerprint(zip: File): Map<String, Pair<Long, Long>> =
        ZipFile.builder().setFile(zip).get().use { z ->
            z.entries.asSequence().associate { it.name to (it.compressedSize to it.crc) }
        }

    // ── it works ──

    @Test fun a_member_of_a_zip_really_changes() = runTest {
        val file = build("zip")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        assertEquals("after", read(file, "note.txt"))
    }

    @Test fun every_other_member_of_a_zip_is_untouched() = runTest {
        val file = build("zip")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        for (i in 1..8) {
            assertTrue("docs/f$i.txt changed", read(file, "docs/f$i.txt").startsWith("line $i of a file"))
        }
    }

    @Test fun a_member_of_a_plain_tar_really_changes() = runTest {
        val file = build("tar")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        assertEquals("after", read(file, "note.txt"))
        assertTrue(read(file, "docs/f3.txt").startsWith("line 3"))
    }

    @Test fun a_member_of_a_compressed_tar_really_changes() = runTest {
        val file = build("tar.gz")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        assertEquals("after", read(file, "note.txt"))
        assertTrue(read(file, "docs/f5.txt").startsWith("line 5"))
    }

    @Test fun a_member_of_a_7z_really_changes() = runTest {
        val file = build("7z")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        assertEquals("after", read(file, "note.txt"))
        assertTrue(read(file, "docs/f2.txt").startsWith("line 2"))
    }

    @Test fun a_longer_replacement_than_the_original_is_fine() = runTest {
        // A tar states each entry's length in its header, so growing an entry is the case that
        // corrupts everything after it if the new size is not measured.
        val file = build("tar")
        val big = "x".repeat(50_000)
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream(big.toByteArray()) }
        assertEquals(big, read(file, "note.txt"))
        assertTrue(read(file, "docs/f8.txt").startsWith("line 8"))
    }

    // ── nothing else was recompressed ──

    @Test fun the_untouched_entries_keep_their_exact_compressed_bytes() = runTest {
        // The assertion his ask is actually about. A re-deflated archive reads back identically,
        // so only the STORED bytes can tell the two apart.
        val file = build("zip")
        val before = rawFingerprint(file)
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        val after = rawFingerprint(file)

        assertEquals(before.keys, after.keys)
        for ((name, fp) in before) {
            if (name == "note.txt") continue
            assertEquals("$name was recompressed", fp, after[name])
        }
    }

    @Test fun the_replaced_entry_is_the_only_one_whose_stored_bytes_moved() = runTest {
        val file = build("zip")
        val before = rawFingerprint(file)
        ArchiveEditor.replace(file, "note.txt") {
            ByteArrayInputStream("a completely different body".toByteArray())
        }
        val after = rawFingerprint(file)
        assertNotEquals("the replaced entry did not change", before["note.txt"], after["note.txt"])
        val moved = before.keys.filter { before[it] != after[it] }
        assertEquals(listOf("note.txt"), moved)
    }

    // ── what each format is allowed to offer ──

    @Test fun the_mode_matches_what_the_container_can_actually_do() {
        assertEquals(EditMode.PATCH, ArchiveEditor.modeFor("a.zip"))
        assertEquals(EditMode.PATCH, ArchiveEditor.modeFor("a.tar"))
        assertEquals(EditMode.REBUILD, ArchiveEditor.modeFor("a.tar.gz"))
        assertEquals(EditMode.REBUILD, ArchiveEditor.modeFor("a.7z"))
        assertEquals(EditMode.COPY_ONLY, ArchiveEditor.modeFor("a.rar"))
    }

    @Test fun a_rar_refuses_with_the_reason_rather_than_failing_obscurely() {
        val fake = File(tmp, "photos.rar")
        fake.writeText("not really a rar")
        try {
            ArchiveEditor.replace(fake, "note.txt") { ByteArrayInputStream("x".toByteArray()) }
            throw AssertionError("a RAR accepted an edit")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("saved somewhere else"))
        }
    }

    @Test fun a_member_that_is_not_there_is_refused_and_the_archive_is_left_alone() = runTest {
        val file = build("zip")
        val before = file.readBytes()
        try {
            ArchiveEditor.replace(file, "not-a-member.txt") { ByteArrayInputStream("x".toByteArray()) }
            throw AssertionError("replaced a member that does not exist")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not in"))
        }
        assertTrue("the archive was modified anyway", before.contentEquals(file.readBytes()))
    }

    // ── an interrupted save must not cost an archive ──

    @Test fun a_failure_part_way_through_leaves_the_original_exactly_as_it_was() = runTest {
        val file = build("zip")
        val before = file.readBytes()
        try {
            ArchiveEditor.replace(file, "note.txt") {
                object : java.io.InputStream() {
                    var n = 0
                    override fun read(): Int {
                        if (n++ > 4) throw IOException("the source went away mid-write")
                        return 'x'.code
                    }
                }
            }
            throw AssertionError("a failing source produced a successful save")
        } catch (expected: IOException) {
        }
        assertTrue("the original archive was damaged", before.contentEquals(file.readBytes()))
    }

    @Test fun no_working_files_are_left_behind_after_a_failure() = runTest {
        val file = build("zip")
        runCatching {
            ArchiveEditor.replace(file, "nope.txt") { ByteArrayInputStream("x".toByteArray()) }
        }
        val strays = tmp.listFiles()!!.map { it.name }.filter { it.endsWith(".editing") || it.endsWith(".previous") }
        assertEquals(emptyList<String>(), strays)
    }

    @Test fun no_working_files_are_left_behind_after_a_success() = runTest {
        val file = build("zip")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        val strays = tmp.listFiles()!!.map { it.name }.filter { it.endsWith(".editing") || it.endsWith(".previous") }
        assertEquals(emptyList<String>(), strays)
    }

    @Test fun the_archive_still_opens_normally_afterwards() = runTest {
        // The end-to-end sanity check: a rewritten archive that Filet cannot browse would pass
        // every assertion above about one member and still be broken.
        val file = build("zip")
        ArchiveEditor.replace(file, "note.txt") { ByteArrayInputStream("after".toByteArray()) }
        val root = ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/'))
        val names = vfs.list(root).map { it.name }.sorted()
        assertEquals(listOf("docs", "note.txt"), names)
    }
}
