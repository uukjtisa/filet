package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveProviderTest {

    private lateinit var tmp: File
    private lateinit var zip: File
    private lateinit var vfs: Vfs

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-zip-" + System.nanoTime())
        check(tmp.mkdirs())
        zip = File(tmp, "sample.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
            put("top.txt", "hello")
            // NOTE: no explicit "docs/" entry. Most real archives omit directory entries, and a
            // listing built only from real entries would lose the whole subtree.
            put("docs/a.txt", "alpha")
            put("docs/deep/b.txt", "beta")
        }
        vfs = Vfs(listOf(ArchiveProvider()))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun root() = ArchiveProvider.mount(zip.absolutePath.replace(File.separatorChar, '/'))

    @Test fun lists_top_level_including_implicit_directories() = runTest {
        val names = vfs.list(root()).map { it.name }.sorted()
        assertEquals(listOf("docs", "top.txt"), names)
        assertTrue(vfs.list(root()).first { it.name == "docs" }.isDir)
    }

    @Test fun descends_into_an_implicit_directory() = runTest {
        val docs = root().child("docs")
        assertEquals(listOf("a.txt", "deep"), vfs.list(docs).map { it.name }.sorted())
        assertEquals(listOf("b.txt"), vfs.list(docs.child("deep")).map { it.name })
    }

    @Test fun reads_an_entry_without_extracting() = runTest {
        val text = vfs.openRead(root().child("docs").child("a.txt")).use { String(it.readBytes()) }
        assertEquals("alpha", text)
    }

    @Test fun reports_entry_size() = runTest {
        assertEquals(5L, vfs.stat(root().child("top.txt"))!!.size)
    }

    @Test fun stat_of_an_absent_entry_is_null() = runTest {
        assertNull(vfs.stat(root().child("nope.txt")))
    }

    @Test fun stat_of_an_implicit_directory_is_a_directory() = runTest {
        val n = vfs.stat(root().child("docs"))
        assertNotNull(n)
        assertTrue(n!!.isDir)
    }

    @Test fun writing_is_refused_rather_than_silently_ignored() = runTest {
        val e = runCatching { vfs.create(root().child("x.txt"), isDir = false) }.exceptionOrNull()
        assertTrue(e is VfsException.Unsupported)
    }

    @Test fun split_separates_archive_from_inner_path() {
        val p = VPath(ArchiveProvider.SCHEME, "/a/b.zip!/x/y")
        val (archive, inner) = ArchiveProvider.split(p)
        assertEquals("/a/b.zip", archive)
        assertEquals("x/y", inner)
    }

    @Test fun mount_then_split_round_trips_the_host_path() {
        val host = zip.absolutePath.replace(File.separatorChar, '/')
        assertEquals(host, ArchiveProvider.split(ArchiveProvider.mount(host)).first)
    }

    // ── the formats round 7 added ──────────────────────────────────────────

    /**
     * The same three files, in each container, so a difference in what comes back is a
     * difference in the reader rather than in the fixture.
     */
    private fun mountOf(f: File) = ArchiveProvider.mount(f.absolutePath.replace(File.separatorChar, '/'))

    private fun writeTar(name: String, wrap: (OutputStream) -> OutputStream): File {
        val f = File(tmp, name)
        TarArchiveOutputStream(wrap(f.outputStream())).use { tar ->
            fun put(entry: String, body: String) {
                val bytes = body.toByteArray()
                val e = TarArchiveEntry(entry)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            put("top.txt", "hello")
            // A real tar DOES carry directory entries, unlike most zips. Both shapes have to
            // produce the same tree or the provider is guessing.
            val dir = TarArchiveEntry("docs/")
            tar.putArchiveEntry(dir)
            tar.closeArchiveEntry()
            put("docs/a.txt", "alpha")
            put("docs/deep/b.txt", "beta")
        }
        return f
    }

    private fun assertReadsTheSameTree(archive: File) {
        val root = mountOf(archive)
        kotlinx.coroutines.runBlocking {
            assertEquals(
                "top level of ${archive.name}",
                listOf("docs", "top.txt"),
                vfs.list(root).map { it.name }.sorted(),
            )
            assertTrue("${archive.name}: docs is a folder", vfs.list(root).first { it.name == "docs" }.isDir)
            assertEquals(
                "inside docs of ${archive.name}",
                listOf("a.txt", "deep"),
                vfs.list(root.child("docs")).map { it.name }.sorted(),
            )
            assertEquals(
                "${archive.name}: nested member",
                "beta",
                vfs.openRead(root.child("docs").child("deep").child("b.txt"))
                    .use { String(it.readBytes()) },
            )
            assertEquals(
                "${archive.name}: top member",
                "hello",
                vfs.openRead(root.child("top.txt")).use { String(it.readBytes()) },
            )
        }
    }

    @Test fun a_plain_tar_reads() {
        assertReadsTheSameTree(writeTar("sample.tar") { it })
    }

    @Test fun a_gzipped_tar_reads() {
        assertReadsTheSameTree(writeTar("sample.tar.gz") { GzipCompressorOutputStream(it) })
    }

    @Test fun the_tgz_spelling_reads_too() {
        assertReadsTheSameTree(writeTar("sample.tgz") { GzipCompressorOutputStream(it) })
    }

    @Test fun a_bzipped_tar_reads() {
        assertReadsTheSameTree(writeTar("sample.tar.bz2") { BZip2CompressorOutputStream(it) })
    }

    @Test fun an_xz_tar_reads() {
        assertReadsTheSameTree(writeTar("sample.tar.xz") { XZCompressorOutputStream(it) })
    }

    @Test fun a_seven_zip_reads() {
        val f = File(tmp, "sample.7z")
        SevenZOutputFile(f).use { z ->
            fun put(entry: String, body: String) {
                val bytes = body.toByteArray()
                val e = SevenZArchiveEntry()
                e.name = entry
                e.size = bytes.size.toLong()
                z.putArchiveEntry(e)
                z.write(bytes)
                z.closeArchiveEntry()
            }
            put("top.txt", "hello")
            put("docs/a.txt", "alpha")
            put("docs/deep/b.txt", "beta")
        }
        assertReadsTheSameTree(f)
    }

    @Test fun a_single_gzipped_file_shows_one_member_named_without_the_suffix() = runTest {
        val f = File(tmp, "notes.txt.gz")
        GzipCompressorOutputStream(f.outputStream()).use { it.write("just the one".toByteArray()) }
        val root = mountOf(f)
        assertEquals(listOf("notes.txt"), vfs.list(root).map { it.name })
        assertEquals("just the one", vfs.openRead(root.child("notes.txt")).use { String(it.readBytes()) })
    }

    @Test fun an_xz_file_and_a_bz2_file_do_the_same() = runTest {
        val x = File(tmp, "dump.sql.xz")
        XZCompressorOutputStream(x.outputStream()).use { it.write("select 1".toByteArray()) }
        assertEquals("select 1", vfs.openRead(mountOf(x).child("dump.sql")).use { String(it.readBytes()) })

        val b = File(tmp, "log.txt.bz2")
        BZip2CompressorOutputStream(b.outputStream()).use { it.write("line".toByteArray()) }
        assertEquals("line", vfs.openRead(mountOf(b).child("log.txt")).use { String(it.readBytes()) })
    }

    @Test fun a_broken_rar_fails_as_a_broken_archive_rather_than_as_a_licence_problem() = runTest {
        // This file has a RAR signature and nothing behind it. It used to be refused on the
        // NAME, before a byte was read, because no decoder could ship - and the message was
        // about the UnRAR licence. libarchive's BSD readers changed that, so the honest answer
        // is now the ordinary one: this archive cannot be read.
        //
        // On the JVM there is no libarchive at all (`RarNative.available` is false, the .so is
        // an Android artefact), so this also pins the no-native-library path: a refusal, never
        // a crash on a missing symbol.
        val f = File(tmp, "archive.rar")
        f.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21))
        val e = runCatching { vfs.list(mountOf(f)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is VfsException.Unsupported)
        assertFalse(
            "the licence is no longer the reason RAR might fail",
            e!!.message.orEmpty().contains("UnRAR"),
        )
    }

    @Test fun a_member_missing_from_any_format_is_not_found_rather_than_empty() = runTest {
        val tar = writeTar("gaps.tar.gz") { GzipCompressorOutputStream(it) }
        val e = runCatching { vfs.openRead(mountOf(tar).child("nope.txt")) }.exceptionOrNull()
        assertTrue("expected NotFound, got $e", e is VfsException.NotFound)
    }

    @Test fun sizes_come_through_for_a_tar_and_are_honestly_unknown_for_a_stream() = runTest {
        val tar = writeTar("sized.tar") { it }
        assertEquals(5L, vfs.stat(mountOf(tar).child("top.txt"))!!.size)

        // gzip stores the length in a trailer modulo 4 GB and xz stores nothing, so reporting
        // a number here would mean reading the file to invent one.
        val gz = File(tmp, "one.txt.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write("abc".toByteArray()) }
        assertEquals(-1L, vfs.stat(mountOf(gz).child("one.txt"))!!.size)
    }

    @Test fun listing_a_tar_twice_uses_the_cache_rather_than_reading_it_again() = runTest {
        // Not a timing assertion - those are flaky. The observable claim is that the second
        // listing agrees with the first, which is what a stale or per-call cache would break.
        val tar = writeTar("cached.tar.gz") { GzipCompressorOutputStream(it) }
        val once = vfs.list(mountOf(tar)).map { it.name }.sorted()
        val twice = vfs.list(mountOf(tar)).map { it.name }.sorted()
        assertEquals(once, twice)
        assertEquals(listOf("docs", "top.txt"), twice)
    }

    @Test fun an_edited_archive_is_re_read_rather_than_served_from_the_cache() = runTest {
        val f = writeTar("changing.tar") { it }
        assertEquals(listOf("docs", "top.txt"), vfs.list(mountOf(f)).map { it.name }.sorted())
        // Same path, different content and a different length: the cache key carries both.
        Thread.sleep(5)
        TarArchiveOutputStream(f.outputStream()).use { tar ->
            val bytes = "replaced entirely".toByteArray()
            val e = TarArchiveEntry("only.txt")
            e.size = bytes.size.toLong()
            tar.putArchiveEntry(e)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        assertEquals(listOf("only.txt"), vfs.list(mountOf(f)).map { it.name })
    }
}
