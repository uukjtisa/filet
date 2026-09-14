package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.EditCosts
import dev.niccc2007.filet.vfs.provider.EditMode
import dev.niccc2007.filet.vfs.provider.EncryptionMethod
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What a save into an archive costs, and the archives it must refuse.
 *
 * Two gates live here and they fail in opposite directions.
 *
 * The cost sentence is what makes editing a 7z an informed decision rather than a surprise, so
 * the test asserts the *shape* of it - a count, a size, and a reason - rather than pinning a
 * string, which would only prove the string had not been edited.
 *
 * The refusal is the one that protects data. Rewriting one entry into an encrypted zip without
 * the password produces an archive whose other entries are still protected and whose new one is
 * not: it opens in nothing, and it looks exactly like corruption. Every positive case here is
 * paired with a negative one, because a refusal that fires on everything is not a check.
 */
class EditCostTest {

    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-cost-" + System.nanoTime())
        check(tmp.mkdirs())
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun sources(n: Int, each: Int = 4_000): List<ArchiveSource> = buildList {
        add(ArchiveSource("docs", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        for (i in 1..n) {
            val body = ("line $i of something compressible\n").repeat(each / 34).toByteArray()
            add(
                ArchiveSource("docs/f$i.txt", isDir = false, size = body.size.toLong(), mtime = 1_700_000_000_000L) {
                    ByteArrayInputStream(body)
                },
            )
        }
    }

    private suspend fun build(id: String, n: Int = 6, options: ArchiveOptions = ArchiveOptions.NONE): File {
        val format = Archives.ALL.first { it.id == id }
        val file = File(tmp, "archive-$id-${System.nanoTime()}${format.suffix}")
        if (ArchiveWriter.needsRealFile(format, options)) {
            ArchiveWriter.writeToFile(format, file, sources(n), options)
        } else {
            file.outputStream().use { ArchiveWriter.writeToStream(format, it, sources(n), options) }
        }
        return file
    }

    // ── the cost ──

    @Test fun a_zip_counts_its_members_without_decompressing_them() = runTest {
        val cost = EditCosts.costOf(build("zip", n = 6), "docs/f1.txt")
        assertEquals(EditMode.PATCH, cost.mode)
        assertEquals(6, cost.members)
        assertTrue("nothing was measured", cost.totalBytes > 0)
        // A patch rewrites exactly one member - that is the entire claim of the feature.
        assertEquals(1, cost.rewritten)
        assertTrue("the member itself was not measured", cost.rewrittenBytes > 0)
        assertTrue("a patch must not claim the whole archive", cost.rewrittenBytes < cost.totalBytes)
    }

    @Test fun a_7z_says_it_rewrites_everything() = runTest {
        val cost = EditCosts.costOf(build("7z", n = 5), "docs/f1.txt")
        assertEquals(EditMode.REBUILD, cost.mode)
        assertEquals(5, cost.members)
        assertEquals(5, cost.rewritten)
        assertEquals(cost.totalBytes, cost.rewrittenBytes)
        assertTrue(cost.isWholeArchive)
    }

    @Test fun a_compressed_tar_is_a_rebuild_and_a_plain_one_is_not() = runTest {
        assertTrue(EditCosts.costOf(build("tar.gz", n = 3), "docs/f1.txt").isWholeArchive)
        // Plain tar has no compression stream to redo, so each member stands alone.
        assertEquals(EditMode.PATCH, EditCosts.costOf(build("tar", n = 3), "docs/f1.txt").mode)
    }

    @Test fun the_sentence_for_a_rebuild_says_how_many_how_big_and_why() = runTest {
        val words = EditCosts.describe(EditCosts.costOf(build("7z", n = 5), "docs/f1.txt"))
        assertTrue("no count: $words", words.contains("5"))
        assertTrue("no size: $words", Regex("\\d+(\\.\\d+)? ?(B|KB|MB|GB)").containsMatchIn(words))
        // The reason matters as much as the number: without it this reads as a bug in Filet.
        assertTrue("no reason: $words", words.contains("one compression stream"))
    }

    @Test fun the_sentence_for_a_patch_says_the_rest_is_left_alone() = runTest {
        val words = EditCosts.describe(EditCosts.costOf(build("zip", n = 6), "docs/f1.txt"))
        assertTrue("no count: $words", words.contains("6"))
        assertTrue("does not say the rest is untouched: $words", words.contains("re-compressed"))
    }

    @Test fun a_read_only_format_is_described_as_save_elsewhere_only() {
        val rar = File(tmp, "something.rar")
        rar.writeBytes(ByteArray(64))
        val refusal = EditCosts.refusalFor(rar)
        assertNotNull("a rar must refuse", refusal)
        assertTrue("the refusal must offer the way out: $refusal", refusal!!.contains("somewhere else"))
    }

    @Test fun a_sentence_exists_for_every_format_that_can_hold_an_edit() = runTest {
        for (f in Archives.ALL.filter { it.canCreate }) {
            val words = EditCosts.describe(EditCosts.costOf(build(f.id, n = 3), "docs/f1.txt"))
            assertTrue("${f.id} produced nothing to show", words.length > 20)
        }
    }

    // ── the refusal ──

    @Test fun a_plain_zip_is_not_refused() = runTest {
        // The positive control. Without it, a check that refused everything would pass below.
        assertNull(EditCosts.refusalFor(build("zip")))
    }

    @Test fun an_aes_zip_is_refused_with_the_reason_and_the_way_out() = runTest {
        val file = build(
            "zip",
            options = ArchiveOptions(password = "correct horse".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        val refusal = EditCosts.refusalFor(file)
        assertNotNull("an encrypted zip must refuse", refusal)
        assertTrue("does not say it is encrypted: $refusal", refusal!!.contains("encrypted"))
        assertTrue("does not offer the way out: $refusal", refusal.contains("somewhere else"))
    }

    @Test fun a_zipcrypto_zip_is_refused_too() = runTest {
        // Weak encryption is still encryption: rewriting an entry drops it just the same.
        val file = build(
            "zip",
            options = ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.ZIP_CRYPTO),
        )
        assertNotNull(EditCosts.refusalFor(file))
    }

    @Test fun formats_with_no_encryption_in_them_are_never_refused_for_it() = runTest {
        for (id in listOf("tar", "tar.gz", "tar.bz2", "tar.xz")) {
            assertNull("$id has no encryption to lose", EditCosts.refusalFor(build(id, n = 2)))
        }
    }

    @Test fun a_plain_7z_is_not_refused() = runTest {
        assertNull(EditCosts.refusalFor(build("7z", n = 3)))
    }

    @Test fun something_that_is_not_an_archive_is_refused_by_name() {
        val f = File(tmp, "notes.txt")
        f.writeText("hello")
        assertNotNull(EditCosts.refusalFor(f))
    }
}
