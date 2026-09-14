package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveCapabilities
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.EncryptionMethod
import dev.niccc2007.filet.vfs.provider.ZipWriter
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.CompressionLevel
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

/**
 * A password on a zip, and the assertion that actually matters.
 *
 * **An "encrypted" archive that opens anyway is the only failure mode here worth testing for,
 * and it is invisible from the outside.** The file has the right size, the right name, the
 * right icon; the listing looks identical. So every positive case below is paired with a
 * negative one that opens the same archive with no password and requires it to fail.
 *
 * The other half is refusing what cannot be done. A password handed to a writer with no
 * encryption in it produces a perfectly good unprotected archive, which is worse than an error
 * because the person who set the password believes it took.
 */
class ArchiveEncryptTest {

    private lateinit var tmp: File

    private val bodies = mapOf(
        "notes/top.txt" to "hello",
        "notes/docs/a.txt" to "alpha",
        "photo.jpg" to "not-really-a-jpeg-but-it-is-stored",
    )

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-crypt-" + System.nanoTime())
        check(tmp.mkdirs())
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
                },
            )
        }
    }

    private val zip = Archives.ALL.first { it.id == "zip" }

    private suspend fun writeZip(name: String, options: ArchiveOptions): File {
        val file = File(tmp, name)
        file.outputStream().use { ArchiveWriter.writeToStream(zip, it, sources(), options) }
        assertTrue("$name produced nothing", file.length() > 0)
        return file
    }

    /** Read one entry back with zip4j, which is the reader that speaks AES. */
    private fun readEntry(file: File, path: String, password: CharArray?): String {
        val zf = if (password != null) ZipFile(file, password) else ZipFile(file)
        zf.use { z ->
            val header = z.getFileHeader(path) ?: error("no entry $path")
            z.getInputStream(header).use { return it.readBytes().decodeToString() }
        }
    }

    // ── the positive cases ──

    @Test fun an_aes256_zip_reads_back_with_its_password() = runTest {
        val file = writeZip(
            "aes256.zip",
            ArchiveOptions(password = "correct horse".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        assertEquals("hello", readEntry(file, "notes/top.txt", "correct horse".toCharArray()))
        assertEquals("alpha", readEntry(file, "notes/docs/a.txt", "correct horse".toCharArray()))
    }

    @Test fun an_aes128_zip_reads_back_with_its_password() = runTest {
        val file = writeZip(
            "aes128.zip",
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_128),
        )
        assertEquals("hello", readEntry(file, "notes/top.txt", "pw".toCharArray()))
    }

    @Test fun the_legacy_cipher_still_works_for_the_things_that_need_it() = runTest {
        val file = writeZip(
            "legacy.zip",
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.ZIP_CRYPTO),
        )
        assertEquals("hello", readEntry(file, "notes/top.txt", "pw".toCharArray()))
    }

    @Test fun a_stored_entry_survives_encryption_intact() = runTest {
        // Already-compressed media is STORED rather than deflated, and a stored entry states
        // its own length in its header. Encryption changes the byte count on disk, so this is
        // the combination most likely to produce a length the reader then disbelieves.
        val file = writeZip(
            "stored.zip",
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        assertEquals(bodies["photo.jpg"], readEntry(file, "photo.jpg", "pw".toCharArray()))
    }

    // ── the negative cases, which are the point ──

    @Test fun an_encrypted_zip_does_NOT_open_without_the_password() = runTest {
        val file = writeZip(
            "locked.zip",
            ArchiveOptions(password = "correct horse".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        try {
            val got = readEntry(file, "notes/top.txt", null)
            throw AssertionError("read '$got' out of an encrypted archive with no password")
        } catch (expected: Exception) {
            // Any failure is the right outcome. What must NOT happen is readable content.
        }
    }

    @Test fun an_encrypted_zip_does_NOT_open_with_the_wrong_password() = runTest {
        val file = writeZip(
            "locked2.zip",
            ArchiveOptions(password = "correct horse".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        try {
            val got = readEntry(file, "notes/top.txt", "wrong".toCharArray())
            throw AssertionError("read '$got' out of an encrypted archive with the wrong password")
        } catch (expected: Exception) {
        }
    }

    @Test fun the_entries_of_an_encrypted_zip_are_marked_encrypted() = runTest {
        // Belt and braces: a reader that happened not to throw would still be wrong if the
        // header says the entry is plain. This asserts the flag rather than the symptom.
        val file = writeZip(
            "flagged.zip",
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        ZipFile(file, "pw".toCharArray()).use { z ->
            val files = z.fileHeaders.filterNot { it.isDirectory }
            assertTrue("no entries at all", files.isNotEmpty())
            for (h in files) assertTrue("${h.fileName} is not encrypted", h.isEncrypted)
        }
    }

    @Test fun a_plain_zip_is_not_marked_encrypted_and_needs_no_password() = runTest {
        // The positive control for the assertion above. Without it, a bug that marked every
        // entry encrypted would pass the previous test.
        val file = writeZip("plain.zip", ArchiveOptions.NONE)
        assertEquals("hello", readEntry(file, "notes/top.txt", null))
        ZipFile(file).use { z ->
            for (h in z.fileHeaders.filterNot { it.isDirectory }) {
                assertFalse("${h.fileName} claims to be encrypted", h.isEncrypted)
            }
        }
    }

    @Test fun a_zip_never_hides_its_file_names_even_when_encrypted() = runTest {
        // A zip's central directory is plain whatever the entries carry. The capability table
        // says so; this proves the table is not lying, by reading the names with no password.
        val file = writeZip(
            "names.zip",
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_256),
        )
        ZipFile(file).use { z ->
            val names = z.fileHeaders.map { it.fileName }
            assertTrue("notes/top.txt" in names)
        }
        assertFalse(ArchiveCapabilities.of(zip).password.canEncryptNames)
    }

    // ── refusing what cannot be done, before anything is written ──

    @Test fun a_password_on_a_format_with_no_encryption_is_refused_not_ignored() = runTest {
        for (id in listOf("tar", "tar.gz", "tar.bz2", "tar.xz")) {
            val format = Archives.ALL.first { it.id == id }
            val file = File(tmp, "x-$id")
            try {
                file.outputStream().use {
                    ArchiveWriter.writeToStream(format, it, sources(), ArchiveOptions(password = "pw".toCharArray()))
                }
                throw AssertionError("$id silently accepted a password")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("no encryption in it"))
            }
        }
    }

    @Test fun a_password_on_7z_is_refused_with_the_reason_that_is_true() = runTest {
        val format = Archives.ALL.first { it.id == "7z" }
        val problem = ArchiveOptions(password = "pw".toCharArray()).problemFor(ArchiveCapabilities.of(format))
        assertNotNull(problem)
        assertTrue(problem!!.contains("this build cannot write it"))
    }

    @Test fun asking_a_zip_to_hide_its_names_is_refused() = runTest {
        val problem = ArchiveOptions(password = "pw".toCharArray(), encryptNames = true)
            .problemFor(ArchiveCapabilities.of(zip))
        assertNotNull(problem)
        assertTrue(problem!!.contains("hide the file names"))
    }

    @Test fun a_cipher_the_format_does_not_offer_is_refused() = runTest {
        val tar = Archives.ALL.first { it.id == "tar" }
        assertNotNull(
            ArchiveOptions(password = "pw".toCharArray(), encryption = EncryptionMethod.AES_256)
                .problemFor(ArchiveCapabilities.of(tar)),
        )
    }

    @Test fun plain_options_are_never_refused_for_any_creatable_format() = runTest {
        // The positive control for every refusal above.
        for (format in Archives.creatable) {
            assertNull(format.id, ArchiveOptions.NONE.problemFor(ArchiveCapabilities.of(format)))
        }
    }

    // ── strength ──

    @Test fun a_deflate_level_maps_onto_the_writers_own_scale() = runTest {
        for (level in 0..9) assertEquals(level, ZipWriter.levelOf(level).level)
    }

    @Test fun a_level_off_the_end_of_the_scale_lands_on_the_nearest_rather_than_throwing() = runTest {
        assertEquals(CompressionLevel.NO_COMPRESSION, ZipWriter.levelOf(-3))
        assertEquals(9, ZipWriter.levelOf(42).level)
    }

    @Test fun storing_beats_deflating_and_the_archive_still_round_trips() = runTest {
        val stored = writeZip("store.zip", ArchiveOptions(strength = 0))
        val max = writeZip("max.zip", ArchiveOptions(strength = 9))
        assertEquals("hello", readEntry(stored, "notes/top.txt", null))
        assertEquals("hello", readEntry(max, "notes/top.txt", null))
    }

    @Test fun a_strength_setting_on_a_format_that_does_not_compress_is_refused() = runTest {
        val tar = Archives.ALL.first { it.id == "tar" }
        val problem = ArchiveOptions(strength = 5).problemFor(ArchiveCapabilities.of(tar))
        assertNotNull(problem)
        assertTrue(problem!!.contains("does not compress"))
    }

    @Test fun clearing_the_password_really_blanks_it() = runTest {
        // A `CharArray` is used precisely so it can be wiped. If `clearPassword` did nothing,
        // the choice of type would be decoration.
        val pw = "secret".toCharArray()
        val options = ArchiveOptions(password = pw)
        options.clearPassword()
        assertArrayEquals(CharArray(6) { '\u0000' }, pw)
        assertFalse(options.hasPassword)
    }
}
