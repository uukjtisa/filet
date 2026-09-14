package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveCapabilities
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.PartSets
import dev.niccc2007.filet.vfs.provider.SplitSink
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Cutting an archive into parts, and putting it back together.
 *
 * **The round trip is the whole test.** A splitter that produces plausible-looking numbered
 * files is worthless; what matters is that the parts reassemble into the exact bytes, because
 * the person on the other end has one chance to find that out and it is after the download.
 *
 * The two styles are genuinely different mechanisms and are tested separately. A numbered
 * stream split concatenates. A zip volume set does NOT - its final `.zip` carries the central
 * directory - so it is proved by opening the set rather than by joining it.
 */
class SplitArchiveTest {

    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-split-" + System.nanoTime())
        check(tmp.mkdirs())
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun sources(count: Int = 6, size: Int = 20_000): List<ArchiveSource> = buildList {
        add(ArchiveSource("data", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        for (i in 1..count) {
            // Genuinely incompressible, from a fixed seed so the test is deterministic.
            //
            // The first version of this used an arithmetic pattern and called it incompressible
            // in a comment. It has a period of 251 bytes, so deflate crushed 120 KB to a couple
            // of KB and the "split" produced exactly one volume - the test failed for a reason
            // that had nothing to do with the splitter. Random bytes do not deflate.
            val bytes = ByteArray(size)
            java.util.Random((i * 1000).toLong()).nextBytes(bytes)
            add(
                ArchiveSource("data/f$i.bin", isDir = false, size = bytes.size.toLong(), mtime = 1_700_000_000_000L) {
                    ByteArrayInputStream(bytes)
                },
            )
        }
    }

    // ── the byte-stream split: it must concatenate ──

    @Test fun a_numbered_split_reassembles_byte_for_byte() {
        val original = ByteArray(50_000) { ((it * 17) % 256).toByte() }
        SplitSink(tmp, "backup.7z", partBytes = 16_384).use { it.write(original) }

        val parts = tmp.listFiles()!!.filter { it.name.startsWith("backup.7z.") }.sortedBy { it.name }
        assertEquals(listOf("backup.7z.001", "backup.7z.002", "backup.7z.003", "backup.7z.004"), parts.map { it.name })

        val joined = parts.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
        assertArrayEquals(original, joined)
    }

    @Test fun every_part_but_the_last_is_exactly_the_part_size() {
        // Ragged parts still concatenate, but they fail the size check every other tool makes,
        // and a set that looks wrong is one somebody distrusts.
        SplitSink(tmp, "x.7z", partBytes = 1000).use { it.write(ByteArray(3500)) }
        val parts = tmp.listFiles()!!.sortedBy { it.name }
        assertEquals(4, parts.size)
        for (p in parts.dropLast(1)) assertEquals(p.name, 1000L, p.length())
        assertEquals(500L, parts.last().length())
    }

    @Test fun many_small_writes_split_at_the_same_places_as_one_big_one() {
        // The single-byte path and the array path are different code and must agree, or a
        // writer that happens to flush in small chunks produces a differently-cut set.
        val data = ByteArray(4096) { (it % 256).toByte() }
        SplitSink(tmp, "a.7z", partBytes = 1000).use { s -> for (b in data) s.write(b.toInt()) }
        val oneAtATime = tmp.listFiles()!!.sortedBy { it.name }.map { it.length() }

        val other = File(tmp, "sub").apply { mkdirs() }
        SplitSink(other, "a.7z", partBytes = 1000).use { it.write(data) }
        val bulk = other.listFiles()!!.sortedBy { it.name }.map { it.length() }

        assertEquals(bulk, oneAtATime)
    }

    @Test fun a_set_that_would_run_past_its_numbering_throws_instead_of_writing_it() {
        // Renaming .001 after .999 rolled over would mean rewriting the set, so it refuses at
        // the boundary. A .1000 sorts before .002 and some tools will not see it at all.
        try {
            SplitSink(tmp, "big.7z", partBytes = 10, padding = 3).use { s ->
                s.write(ByteArray(20_000))
            }
            throw AssertionError("wrote past the numbering without complaining")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("bigger part size"))
        }
    }

    @Test fun an_abandoned_split_leaves_nothing_behind() {
        // A folder of numbered files that look like an archive and are not one is worse than a
        // failed write, because it is not obviously broken until somebody tries to use it.
        val sink = SplitSink(tmp, "gone.7z", partBytes = 100)
        sink.write(ByteArray(450))
        sink.abandon()
        assertEquals(0, tmp.listFiles()!!.size)
    }

    @Test fun the_width_comes_from_the_expected_part_count() {
        assertEquals(3, SplitSink.paddingFor(totalBytes = 1000, partBytes = 100))
        assertEquals(3, SplitSink.paddingFor(totalBytes = 99_900, partBytes = 100))
        assertEquals(4, SplitSink.paddingFor(totalBytes = 100_000, partBytes = 100))
    }

    @Test fun every_part_name_parses_back_to_its_own_index() {
        SplitSink(tmp, "backup.tar.gz", partBytes = 1000).use { it.write(ByteArray(3500)) }
        val parts = tmp.listFiles()!!.sortedBy { it.name }
        parts.forEachIndexed { i, f ->
            val ref = PartSets.refFor(f.name)
            assertNotNull("${f.name} is not recognised as a part", ref)
            assertEquals(f.name, i + 1, ref!!.index)
            assertEquals(f.name, "backup.tar.gz", ref.setName)
        }
    }

    // ── the zip volume set: proved by opening it, not by joining it ──

    @Test fun a_split_zip_writes_volumes_and_opens_as_one_archive() = runTest {
        val format = Archives.ALL.first { it.id == "zip" }
        val target = File(tmp, "set.zip")
        val options = ArchiveOptions(splitBytes = 64 * 1024)
        assertTrue("a split zip needs a real file", ArchiveWriter.needsRealFile(format, options))
        ArchiveWriter.writeToFile(format, target, sources(), options)

        val names = tmp.listFiles()!!.map { it.name }.sorted()
        assertTrue("no volumes were written: $names", names.any { it.endsWith(".z01") })
        assertTrue("no final .zip: $names", "set.zip" in names)

        // Opening the set is the proof. zip4j follows the volumes itself from the final .zip.
        ZipFile(target).use { z ->
            assertTrue(z.isSplitArchive)
            val entries = z.fileHeaders.filterNot { it.isDirectory }.map { it.fileName }.sorted()
            assertEquals((1..6).map { "data/f$it.bin" }, entries)
            val one = z.getFileHeader("data/f3.bin")!!
            z.getInputStream(one).use { assertEquals(20_000, it.readBytes().size) }
        }
    }

    @Test fun a_split_zip_can_also_carry_a_password() = runTest {
        // The two features are independent and both touch the entry headers, so the
        // combination is the one most likely to be wrong.
        val format = Archives.ALL.first { it.id == "zip" }
        val target = File(tmp, "locked.zip")
        ArchiveWriter.writeToFile(
            // Six files of 20 KB against a 64 KB part size: this really does span volumes,
            // which is the point - encryption on a one-volume "split" would prove nothing.
            format, target, sources(count = 6),
            ArchiveOptions(splitBytes = 64 * 1024, password = "pw".toCharArray()),
        )
        ZipFile(target, "pw".toCharArray()).use { z ->
            val h = z.getFileHeader("data/f1.bin")!!
            assertTrue(h.isEncrypted)
            z.getInputStream(h).use { assertEquals(20_000, it.readBytes().size) }
        }
    }

    @Test fun an_unsplit_zip_still_goes_to_a_stream() = runTest {
        val format = Archives.ALL.first { it.id == "zip" }
        assertFalse(ArchiveWriter.needsRealFile(format, ArchiveOptions.NONE))
    }

    // ── refusing a part size that cannot work, before writing ──

    @Test fun a_part_below_the_formats_floor_is_refused() {
        val zip = ArchiveCapabilities.of(Archives.ALL.first { it.id == "zip" })
        assertNotNull(ArchiveCapabilities.splitProblem(zip, partBytes = 1024, totalBytes = 10_000_000))
    }

    @Test fun a_part_size_needing_more_parts_than_the_scheme_allows_is_refused() {
        val zip = ArchiveCapabilities.of(Archives.ALL.first { it.id == "zip" })
        val why = ArchiveCapabilities.splitProblem(zip, 64 * 1024, 4L * 1024 * 1024 * 1024)
        assertNotNull(why)
        assertTrue(why!!.contains("parts"))
    }

    @Test fun a_workable_part_size_is_not_refused() {
        // The positive control. Without it a refusal that always fired would look correct.
        val zip = ArchiveCapabilities.of(Archives.ALL.first { it.id == "zip" })
        assertNull(ArchiveCapabilities.splitProblem(zip, 10L * 1024 * 1024, 100L * 1024 * 1024))
    }

    @Test fun asking_to_split_a_format_that_cannot_be_split_is_refused_before_writing() = runTest {
        val rar = Archives.ALL.first { it.id == "rar" }
        val problem = ArchiveOptions(splitBytes = 1024 * 1024).problemFor(ArchiveCapabilities.of(rar))
        assertNotNull(problem)
    }

    @Test fun a_zero_part_size_is_not_a_split_at_all() {
        // `isSplit` guards every branch that opens a volume, so an off-by-one there would send
        // an ordinary archive down the splitting path.
        assertFalse(ArchiveOptions(splitBytes = 0).isSplit)
        assertFalse(ArchiveOptions(splitBytes = null).isSplit)
        assertTrue(ArchiveOptions(splitBytes = 1).isSplit)
    }
}
