package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.CollisionChoice
import dev.niccc2007.filet.vfs.provider.ExtractOptions
import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.vfs.provider.WrapChoice
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Plan an extraction, run it, and check the disk against the plan.
 *
 * This is the gate the whole round turns on. `ExtractPlanTest` proves the arithmetic; this
 * proves that **what the preview shows is what lands**, by comparing the tree that actually
 * appeared against the plan's own item list rather than against a hand-written expectation.
 *
 * A hand-written expectation would let both halves be wrong together. Comparing to the plan
 * catches the one failure that matters here: a preview that tells the truth and an extractor
 * that quietly does something else, which nobody finds out about until the files are on disk.
 */
class ExtractOpsTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var ops: ExtractOperations

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-xops-" + System.nanoTime())
        check(tmp.mkdirs())
        vfs = Vfs(listOf(LocalProvider(listOf(tmp)), ArchiveProvider()))
        ops = ExtractOperations(vfs, JobLedger())
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    /** A local VPath for a file under the temp root, spelled the way the provider wants it. */
    private fun local(f: File): VPath =
        VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    private fun src(path: String, body: String) =
        ArchiveSource(path, isDir = false, size = body.toByteArray().size.toLong(), mtime = 1_700_000_000_000L) {
            ByteArrayInputStream(body.toByteArray())
        }

    private fun dir(path: String) = ArchiveSource(path, isDir = true, size = -1L, mtime = 1_700_000_000_000L)

    private suspend fun makeZip(name: String, sources: List<ArchiveSource>): Pair<VPath, String> {
        val file = File(tmp, name)
        val format = Archives.ALL.first { it.id == "zip" }
        file.outputStream().use { ArchiveWriter.writeToStream(format, it, sources) }
        assertTrue("$name produced nothing", file.length() > 0)
        return ArchiveProvider.mount(file.absolutePath.replace(File.separatorChar, '/')) to name
    }

    private fun dest(name: String): Pair<File, VPath> {
        val d = File(tmp, name)
        check(d.mkdirs())
        return d to local(d)
    }

    /** Everything under [root], as relative paths, the way the plan spells them. */
    private fun treeOf(root: File): List<String> =
        root.walkTopDown().filter { it != root }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .sorted().toList()

    // ── the assertion the round exists for ──

    @Test fun what_the_plan_showed_is_exactly_what_landed() = runTest {
        val (archive, label) = makeZip(
            "photos.zip",
            listOf(src("sunset.jpg", "a"), src("harbour.jpg", "b"), src("readme.txt", "c")),
        )
        val (destFile, destPath) = dest("out1")

        val plan = ops.plan(archive, destPath, label)
        assertEquals("photos", plan.wrapFolder)

        val result = ops.run(archive, destPath, plan, label)
        assertTrue(result.failed.toString(), result.ok)

        // The comparison is against the PLAN, not against a list typed out here. A hand-written
        // expectation lets the preview and the extractor be wrong in the same direction.
        assertEquals(plan.items.map { it.path }.sorted(), treeOf(destFile))
    }

    @Test fun a_loose_archive_lands_in_a_folder_named_after_it() = runTest {
        // The bomb, from the other side: three files where one folder was expected.
        val (archive, label) = makeZip("bomb.zip", listOf(src("a.txt", "1"), src("b.txt", "2")))
        val (destFile, destPath) = dest("out2")
        val plan = ops.plan(archive, destPath, label)
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("bomb", "bomb/a.txt", "bomb/b.txt"), treeOf(destFile))
    }

    @Test fun a_redundant_parent_is_lifted_away_on_disk_too() = runTest {
        val (archive, label) = makeZip(
            "backup.zip",
            listOf(dir("backup"), src("backup/one.txt", "1"), src("backup/two.txt", "2")),
        )
        val (destFile, destPath) = dest("out3")
        val plan = ops.plan(archive, destPath, label)
        assertEquals(listOf("backup"), plan.strippedFolders)
        assertNull(plan.wrapFolder)
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("one.txt", "two.txt"), treeOf(destFile))
    }

    @Test fun his_nested_chain_extracts_to_the_bare_file() = runTest {
        // archive.zip/archive/archivehere/archive.txt -> archive.txt, on disk.
        val (archive, label) = makeZip(
            "archive.zip",
            listOf(
                dir("archive"), dir("archive/archivehere"),
                src("archive/archivehere/archive.txt", "deep"),
            ),
        )
        val (destFile, destPath) = dest("out4")
        val plan = ops.plan(archive, destPath, label)
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("archive.txt"), treeOf(destFile))
        assertEquals("deep", File(destFile, "archive.txt").readText())
    }

    @Test fun the_contents_come_out_of_the_right_entries() = runTest {
        // A strip changes every destination path, so reading from the destination path instead
        // of the source path would put each file's contents under a different name. Checked by
        // body, not just by name.
        val (archive, label) = makeZip(
            "proj.zip",
            listOf(
                dir("proj"), dir("proj/docs"),
                src("proj/docs/a.txt", "alpha"), src("proj/docs/b.txt", "beta"),
                src("proj/top.txt", "top"),
            ),
        )
        val (destFile, destPath) = dest("out5")
        val plan = ops.plan(archive, destPath, label)
        ops.run(archive, destPath, plan, label)
        assertEquals("alpha", File(destFile, "docs/a.txt").readText())
        assertEquals("beta", File(destFile, "docs/b.txt").readText())
        assertEquals("top", File(destFile, "top.txt").readText())
    }

    // ── the buttons ──

    @Test fun forcing_a_folder_off_puts_the_files_straight_into_the_destination() = runTest {
        val (archive, label) = makeZip("loose.zip", listOf(src("a.txt", "1"), src("b.txt", "2")))
        val (destFile, destPath) = dest("out6")
        val plan = ops.plan(archive, destPath, label, ExtractOptions(wrap = WrapChoice.FORCE_OFF))
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("a.txt", "b.txt"), treeOf(destFile))
    }

    @Test fun undoing_one_level_keeps_the_outer_folder_on_disk() = runTest {
        val (archive, label) = makeZip(
            "deep.zip",
            listOf(dir("a"), dir("a/b"), src("a/b/c.txt", "x")),
        )
        val (destFile, destPath) = dest("out7")
        val plan = ops.plan(archive, destPath, label, ExtractOptions(stripLevels = 1))
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("b", "b/c.txt"), treeOf(destFile))
    }

    // ── collisions, on real files ──

    @Test fun skipping_a_collision_leaves_what_was_already_there() = runTest {
        val (archive, label) = makeZip("c.zip", listOf(src("a.txt", "NEW"), src("b.txt", "NEW")))
        val (destFile, destPath) = dest("out8")
        File(destFile, "a.txt").writeText("OLD")

        val plan = ops.plan(
            archive, destPath, label,
            ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.SKIP),
        )
        ops.run(archive, destPath, plan, label)
        assertEquals("OLD", File(destFile, "a.txt").readText())
        assertEquals("NEW", File(destFile, "b.txt").readText())
    }

    @Test fun overwriting_a_collision_really_replaces_it() = runTest {
        val (archive, label) = makeZip("c2.zip", listOf(src("a.txt", "NEW")))
        val (destFile, destPath) = dest("out9")
        File(destFile, "a.txt").writeText("OLD")

        val plan = ops.plan(
            archive, destPath, label,
            ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.OVERWRITE),
        )
        ops.run(archive, destPath, plan, label)
        assertEquals("NEW", File(destFile, "a.txt").readText())
    }

    @Test fun keeping_both_leaves_two_files_and_loses_neither() = runTest {
        val (archive, label) = makeZip("c3.zip", listOf(src("a.txt", "NEW")))
        val (destFile, destPath) = dest("out10")
        File(destFile, "a.txt").writeText("OLD")

        val plan = ops.plan(
            archive, destPath, label,
            ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.KEEP_BOTH),
        )
        ops.run(archive, destPath, plan, label)
        assertEquals(listOf("a (1).txt", "a.txt"), treeOf(destFile))
        assertEquals("OLD", File(destFile, "a.txt").readText())
        assertEquals("NEW", File(destFile, "a (1).txt").readText())
    }

    // ── what the preview tells you before you commit ──

    @Test fun the_plan_knows_the_size_and_the_count_before_anything_is_written() = runTest {
        val (archive, label) = makeZip(
            "cost.zip",
            listOf(src("a.txt", "12345"), src("b.txt", "123"), dir("d")),
        )
        val (_, destPath) = dest("out11")
        val plan = ops.plan(archive, destPath, label)
        assertEquals(2, plan.entryCount)
        assertEquals(8, plan.needsBytes)
    }

    @Test fun the_plan_asks_the_destinations_own_volume_about_free_space() = runTest {
        val (archive, label) = makeZip("space.zip", listOf(src("a.txt", "x")))
        val (_, destPath) = dest("out12")
        val plan = ops.plan(archive, destPath, label)
        // A real volume answers, so this must not be the "unknown" sentinel, and one byte fits.
        assertTrue("free space was not asked for", plan.freeBytes > 0)
        assertTrue(plan.fits)
    }

    @Test fun an_existing_file_at_the_destination_is_seen_before_the_run() = runTest {
        val (archive, label) = makeZip("seen.zip", listOf(src("a.txt", "NEW"), src("b.txt", "NEW")))
        val (destFile, destPath) = dest("out13")
        File(destFile, "a.txt").writeText("OLD")
        val plan = ops.plan(archive, destPath, label, ExtractOptions(wrap = WrapChoice.FORCE_OFF))
        assertEquals(listOf("a.txt"), plan.collisions.map { it.path })
    }

    @Test fun extracting_into_a_folder_twice_is_stable() = runTest {
        // Second run with the default SKIP must not duplicate or damage anything, because
        // "extract again to be sure" is a thing people do.
        val (archive, label) = makeZip("twice.zip", listOf(src("a.txt", "1"), src("b.txt", "2")))
        val (destFile, destPath) = dest("out14")
        ops.plan(archive, destPath, label).let { ops.run(archive, destPath, it, label) }
        val first = treeOf(destFile)
        ops.plan(archive, destPath, label).let { ops.run(archive, destPath, it, label) }
        assertEquals(first, treeOf(destFile))
    }
}
