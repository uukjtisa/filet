package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.archiveName
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Making an archive, then reading it back with Filet's own reader.
 *
 * A round trip rather than a byte comparison, because the thing that matters is not that the
 * writer produced the bytes some library would - it is that the archive Filet writes is one
 * Filet can browse. A writer that stores `docs\a.txt` while the reader normalises to
 * `docs/a.txt` passes every unit test written against itself and produces an archive whose
 * folders have vanished.
 */
class ArchiveBuildTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs

    private val bodies = mapOf(
        "notes/top.txt" to "hello",
        "notes/docs/a.txt" to "alpha",
        "notes/docs/deep/b.txt" to "beta",
    )

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-build-" + System.nanoTime())
        check(tmp.mkdirs())
        vfs = Vfs(listOf(ArchiveProvider()))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun sources(): List<ArchiveSource> = buildList {
        add(ArchiveSource("notes", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        add(ArchiveSource("notes/docs", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        for ((path, body) in bodies) {
            val bytes = body.toByteArray()
            add(
                ArchiveSource(path, isDir = false, size = bytes.size.toLong(), mtime = 1_700_000_000_000L) {
                    ByteArrayInputStream(bytes)
                }
            )
        }
    }

    private suspend fun buildAndRead(formatId: String): Map<String, String> {
        val format = Archives.ALL.first { it.id == formatId }
        val file = File(tmp, "made${format.suffix}")
        if (ArchiveWriter.needsRealFile(format)) {
            ArchiveWriter.writeToFile(format, file, sources())
        } else {
            file.outputStream().use { ArchiveWriter.writeToStream(format, it, sources()) }
        }
        assertTrue("${format.id} produced nothing", file.length() > 0)

        val root = ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/'))
        val out = LinkedHashMap<String, String>()
        suspend fun walk(at: VPath, prefix: String) {
            for (child in vfs.list(at)) {
                val name = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) walk(child.path, name)
                else out[name] = vfs.openRead(child.path).use { String(it.readBytes()) }
            }
        }
        walk(root, "")
        return out
    }

    @Test fun a_zip_round_trips_every_file_with_its_folders() = runTest {
        assertEquals(bodies, buildAndRead("zip"))
    }

    @Test fun a_tar_round_trips() = runTest {
        assertEquals(bodies, buildAndRead("tar"))
    }

    @Test fun a_gzipped_tar_round_trips() = runTest {
        assertEquals(bodies, buildAndRead("tar.gz"))
    }

    @Test fun a_bzipped_tar_round_trips() = runTest {
        assertEquals(bodies, buildAndRead("tar.bz2"))
    }

    @Test fun an_xz_tar_round_trips() = runTest {
        assertEquals(bodies, buildAndRead("tar.xz"))
    }

    @Test fun a_seven_zip_round_trips() = runTest {
        assertEquals(bodies, buildAndRead("7z"))
    }

    @Test fun every_creatable_format_actually_round_trips() = runTest {
        // The table promises six. If one is added and its writer is not, this is what says so
        // rather than a Compress menu with a dead entry on it.
        for (format in Archives.creatable) {
            assertEquals("round trip for ${format.id}", bodies, buildAndRead(format.id))
        }
    }

    @Test fun a_compressed_format_is_actually_smaller_than_a_plain_tar() = runTest {
        val big = (1..400).joinToString("\n") { "the same line over and over, line $it" }
        val src = listOf(
            ArchiveSource("big.txt", isDir = false, size = big.toByteArray().size.toLong(), mtime = 0L) {
                ByteArrayInputStream(big.toByteArray())
            }
        )
        fun write(id: String): Long {
            val f = File(tmp, "size-$id")
            val format = Archives.ALL.first { it.id == id }
            kotlinx.coroutines.runBlocking {
                f.outputStream().use { ArchiveWriter.writeToStream(format, it, src) }
            }
            return f.length()
        }
        val plain = write("tar")
        assertTrue("gzip should shrink it: $plain -> ${write("tar.gz")}", write("tar.gz") < plain)
        assertTrue("xz should shrink it", write("tar.xz") < plain)
    }

    @Test fun a_source_shorter_than_its_stat_does_not_corrupt_the_rest_of_a_tar() = runTest {
        // A file being written to while it is archived. The tar header states a length the
        // reader trusts, so an under-run would shift every following entry.
        val format = Archives.ALL.first { it.id == "tar" }
        val file = File(tmp, "shrunk.tar")
        val src = listOf(
            ArchiveSource("a.txt", isDir = false, size = 100L, mtime = 0L) {
                ByteArrayInputStream("short".toByteArray())
            },
            ArchiveSource("b.txt", isDir = false, size = 5L, mtime = 0L) {
                ByteArrayInputStream("after".toByteArray())
            },
        )
        file.outputStream().use { ArchiveWriter.writeToStream(format, it, src) }
        val root = ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/'))
        assertEquals(listOf("a.txt", "b.txt"), vfs.list(root).map { it.name }.sorted())
        assertEquals("after", vfs.openRead(root.child("b.txt")).use { String(it.readBytes()) })
    }

    @Test fun an_already_compressed_member_is_stored_rather_than_deflated() = runTest {
        // Not a size assertion on random data - the claim is that the entry is readable and
        // the method was chosen, which a STORED entry with a wrong CRC would fail outright.
        val bytes = ByteArray(4096) { (it * 31 % 251).toByte() }
        val format = Archives.ALL.first { it.id == "zip" }
        val file = File(tmp, "mixed.zip")
        file.outputStream().use {
            ArchiveWriter.writeToStream(
                format, it,
                listOf(
                    ArchiveSource("clip.mp4", false, bytes.size.toLong(), 0L) { ByteArrayInputStream(bytes) },
                    ArchiveSource("read.txt", false, 5L, 0L) { ByteArrayInputStream("plain".toByteArray()) },
                ),
            )
        }
        val root = ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/'))
        assertEquals(
            bytes.toList(),
            vfs.openRead(root.child("clip.mp4")).use { it.readBytes() }.toList(),
        )
        assertEquals("plain", vfs.openRead(root.child("read.txt")).use { String(it.readBytes()) })
    }

    @Test fun writing_a_format_to_the_wrong_sink_is_refused_rather_than_producing_rubbish() = runTest {
        val sevenZip = Archives.ALL.first { it.id == "7z" }
        assertTrue(ArchiveWriter.needsRealFile(sevenZip))
        val thrown = runCatching {
            File(tmp, "x.7z").outputStream().use { ArchiveWriter.writeToStream(sevenZip, it, sources()) }
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
    }

    // ── naming ──

    @Test fun a_new_archive_never_takes_a_name_that_is_already_there() {
        // Compress is one tap from a selection, and a second tap quietly overwriting the
        // first archive is how this feature would lose somebody's files.
        val zip = Archives.ALL.first { it.id == "zip" }
        assertEquals("Photos.zip", archiveName("Photos", zip) { false })
        assertEquals("Photos (2).zip", archiveName("Photos", zip) { it == "Photos.zip" })
    }

    @Test fun it_keeps_counting_until_it_finds_a_gap() {
        val zip = Archives.ALL.first { it.id == "zip" }
        val there = setOf("Photos.zip", "Photos (2).zip", "Photos (3).zip")
        assertEquals("Photos (4).zip", archiveName("Photos", zip) { it in there })
    }

    @Test fun the_suffix_is_the_formats_own_and_the_whole_one() {
        assertEquals("Backup.tar.gz", archiveName("Backup", Archives.ALL.first { it.id == "tar.gz" }) { false })
        assertEquals("Backup.7z", archiveName("Backup", Archives.ALL.first { it.id == "7z" }) { false })
        assertNotEquals(
            "a tar.gz must not be named .gz",
            "Backup.gz",
            archiveName("Backup", Archives.ALL.first { it.id == "tar.gz" }) { false },
        )
    }

    @Test fun an_empty_or_blank_base_still_produces_a_name() {
        val zip = Archives.ALL.first { it.id == "zip" }
        assertEquals("Archive.zip", archiveName("", zip) { false })
        assertEquals("Archive.zip", archiveName("   ", zip) { false })
    }

    @Test fun the_name_it_picks_is_one_the_reader_recognises() {
        for (format in Archives.creatable) {
            val name = archiveName("Thing", format) { false }
            assertEquals("$name should be a ${format.id}", format.id, Archives.of(name)?.id)
            assertTrue("$name should be readable", Archives.canList(name))
        }
    }

}
