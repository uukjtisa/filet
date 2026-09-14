package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.EncryptionMethod
import dev.niccc2007.filet.vfs.provider.SplitSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Writes the archives that `tools/check-archives.mjs` hands to **7-Zip**.
 *
 * Everything else in this suite reads Filet's output with Filet's own readers, and for the zip
 * volume set that means zip4j checking zip4j - which proves the two halves agree with each
 * other and nothing about whether the file is a zip. This exists so a program that has never
 * heard of Filet gets to say.
 *
 * Not an assertion-heavy test on purpose: its job is to produce files at a known path. The
 * assertions about them are in the checker, because the judge has to be the other tool.
 */
class ArchiveFixturesTest {

    private val out = File("build/archive-fixtures")

    private fun sources(count: Int, size: Int): List<ArchiveSource> = buildList {
        add(ArchiveSource("data", isDir = true, size = -1L, mtime = 1_700_000_000_000L))
        for (i in 1..count) {
            val bytes = ByteArray(size)
            java.util.Random((i * 6151).toLong()).nextBytes(bytes)
            add(
                ArchiveSource("data/f$i.bin", isDir = false, size = bytes.size.toLong(), mtime = 1_700_000_000_000L) {
                    ByteArrayInputStream(bytes)
                },
            )
        }
        val note = "filet round 9 fixture".toByteArray()
        add(
            ArchiveSource("note.txt", isDir = false, size = note.size.toLong(), mtime = 1_700_000_000_000L) {
                ByteArrayInputStream(note)
            },
        )
    }

    @Test fun write_the_fixtures_other_tools_will_judge() = runTest {
        if (out.exists()) out.deleteRecursively()
        check(out.mkdirs())
        val zip = Archives.ALL.first { it.id == "zip" }
        val sevenZ = Archives.ALL.first { it.id == "7z" }

        // 1. AES-256, password "filet-test". 7-Zip must refuse it without the password and
        //    read it with one - the claim that matters about encryption.
        File(out, "aes.zip").outputStream().use {
            ArchiveWriter.writeToStream(
                zip, it, sources(2, 4_000),
                ArchiveOptions(password = "filet-test".toCharArray(), encryption = EncryptionMethod.AES_256),
            )
        }

        // 2. Legacy ZipCrypto, for the tools that read nothing else.
        File(out, "legacy.zip").outputStream().use {
            ArchiveWriter.writeToStream(
                zip, it, sources(1, 2_000),
                ArchiveOptions(password = "filet-test".toCharArray(), encryption = EncryptionMethod.ZIP_CRYPTO),
            )
        }

        // 3. A zip VOLUME set. The one Filet's own tests cannot judge, because zip4j writes it
        //    and zip4j reads it.
        ArchiveWriter.writeToFile(
            zip, File(out, "volumes.zip"), sources(6, 20_000),
            ArchiveOptions(splitBytes = 64 * 1024),
        )

        // 4. A NUMBERED split of a 7z, which is how 7-Zip writes its own .001 volumes.
        val whole = File(out, "numbered.7z")
        ArchiveWriter.writeToFile(sevenZ, whole, sources(4, 20_000))
        SplitSink(out, "numbered.7z", partBytes = 24 * 1024).use { sink ->
            whole.inputStream().use { it.copyTo(sink) }
        }
        check(whole.delete())

        // 5. A plain zip at each end of the strength scale.
        //
        //    COMPRESSIBLE input, and that is the whole point. The first version used the same
        //    random payload as everything else, and on random bytes deflate ADDS a few bytes of
        //    overhead - so store came out smaller than maximum and the check reported the level
        //    was backwards. It was the measurement that was backwards. A level setting can only
        //    be observed on data that compresses.
        val text = buildString { repeat(4_000) { append("the quick brown fox jumps over the lazy dog\n") } }
            .toByteArray()
        val compressible = listOf(
            ArchiveSource("note.txt", isDir = false, size = text.size.toLong(), mtime = 1_700_000_000_000L) {
                ByteArrayInputStream(text)
            },
        )
        for (level in listOf(0, 9)) {
            File(out, "level$level.zip").outputStream().use {
                ArchiveWriter.writeToStream(zip, it, compressible, ArchiveOptions(strength = level))
            }
        }

        assertTrue("no fixtures were written", (out.listFiles()?.size ?: 0) >= 7)
    }
}
