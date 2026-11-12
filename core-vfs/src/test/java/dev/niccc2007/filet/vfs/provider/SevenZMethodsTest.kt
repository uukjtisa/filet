package dev.niccc2007.filet.vfs.provider

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The 7z writer used to ignore the strength picked in the dialog and run LZMA2 over video,
 * which is what made a 1 GB archive crawl. These pin both halves of the fix by reading the
 * archive back and asking each member how it was packed.
 */
class SevenZMethodsTest {

    private fun source(name: String, bytes: ByteArray) =
        ArchiveSource(name, isDir = false, size = bytes.size.toLong(), mtime = 0L, open = { bytes.inputStream() })

    private fun write(file: File, options: ArchiveOptions, vararg sources: ArchiveSource) = runBlocking {
        val format = Archives.creatable.first { it.id == "7z" }
        ArchiveWriter.writeToFile(format, file, sources.toList(), options)
    }

    /** method of each member, by name, and its bytes read back. */
    private fun readBack(file: File): Map<String, Pair<SevenZMethod, ByteArray>> {
        val out = HashMap<String, Pair<SevenZMethod, ByteArray>>()
        SevenZFile.builder().setFile(file).get().use { z ->
            var e = z.nextEntry
            while (e != null) {
                val method = e.contentMethods.first().method
                out[e.name] = method to z.getInputStream(e).readBytes()
                e = z.nextEntry
            }
        }
        return out
    }

    @Test
    fun `video is stored and text is compressed, and both read back intact`() {
        val video = ByteArray(200_000) { (it * 31).toByte() }
        val text = "line\n".repeat(20_000).toByteArray()
        val file = File.createTempFile("filet-7z", ".7z").apply { deleteOnExit() }
        write(file, ArchiveOptions.NONE, source("clip.mp4", video), source("notes.txt", text))

        val back = readBack(file)
        assertEquals(SevenZMethod.COPY, back.getValue("clip.mp4").first)
        assertEquals(SevenZMethod.LZMA2, back.getValue("notes.txt").first)
        assertArrayEquals(video, back.getValue("clip.mp4").second)
        assertArrayEquals(text, back.getValue("notes.txt").second)
    }

    @Test
    fun `the strength picked reaches the encoder`() {
        // Preset 0 uses a 256 KiB dictionary and preset 6 an 8 MiB one, so the dictionary
        // the archive records says which preset wrote it.
        assertEquals(256 * 1024, dictionaryFor(strength = 0))
        assertEquals(8 * 1024 * 1024, dictionaryFor(strength = null))
    }

    private fun dictionaryFor(strength: Int?): Int {
        val file = File.createTempFile("filet-7z", ".7z").apply { deleteOnExit() }
        write(file, ArchiveOptions(strength = strength), source("notes.txt", "x".repeat(4096).toByteArray()))
        SevenZFile.builder().setFile(file).get().use { z ->
            val e = z.nextEntry
            return (e.contentMethods.first().options as Number).toInt()
        }
    }
}
