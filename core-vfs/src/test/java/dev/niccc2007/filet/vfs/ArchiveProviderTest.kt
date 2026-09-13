package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
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
}
