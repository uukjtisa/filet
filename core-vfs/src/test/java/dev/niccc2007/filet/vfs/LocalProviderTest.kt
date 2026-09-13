package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The six M0 operations, exercised against a real temp directory on the JVM.
 * No Android, no Robolectric, no device — which is the point of keeping LocalProvider
 * free of `Environment`.
 */
class LocalProviderTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var root: VPath

    private fun p(rel: String) = VPath.of(LocalProvider.SCHEME, tmp.absolutePath.replace('\\', '/') + "/" + rel)

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-test-" + System.nanoTime())
        check(tmp.mkdirs())
        vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        root = VPath.of(LocalProvider.SCHEME, tmp.absolutePath.replace('\\', '/'))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    @Test fun roots_reports_the_configured_root() = runTest {
        val roots = vfs.roots()
        assertEquals(1, roots.size)
        assertTrue(roots[0].isDir)
    }

    @Test fun create_list_and_stat() = runTest {
        vfs.create(p("a.txt"), isDir = false)
        vfs.create(p("sub"), isDir = true)

        val names = vfs.list(root).map { it.name }.sorted()
        assertEquals(listOf("a.txt", "sub"), names)

        val f = vfs.stat(p("a.txt"))
        assertNotNull(f)
        assertFalse(f!!.isDir)
        assertEquals("txt", f.extension)
        assertEquals(-1L, vfs.stat(p("sub"))!!.size)   // directories report -1, not 0
    }

    @Test fun stat_returns_null_for_absent_rather_than_throwing() = runTest {
        assertNull(vfs.stat(p("nope.txt")))
    }

    @Test fun create_twice_is_already_exists() = runTest {
        vfs.create(p("dup.txt"), isDir = false)
        val e = runCatching { vfs.create(p("dup.txt"), isDir = false) }.exceptionOrNull()
        assertTrue(e is VfsException.AlreadyExists)
    }

    @Test fun write_then_read_round_trips() = runTest {
        vfs.openWrite(p("hello.txt")).use { it.write("filet".toByteArray()) }
        val back = vfs.openRead(p("hello.txt")).use { String(it.readBytes()) }
        assertEquals("filet", back)
        assertEquals(5L, vfs.stat(p("hello.txt"))!!.size)
    }

    @Test fun rename_moves_within_the_parent() = runTest {
        vfs.create(p("old.txt"), isDir = false)
        val renamed = vfs.rename(p("old.txt"), "new.txt")
        assertEquals("new.txt", renamed.name)
        assertNull(vfs.stat(p("old.txt")))
        assertNotNull(vfs.stat(p("new.txt")))
    }

    @Test fun rename_onto_an_existing_name_is_refused() = runTest {
        vfs.create(p("a.txt"), isDir = false)
        vfs.create(p("b.txt"), isDir = false)
        val e = runCatching { vfs.rename(p("a.txt"), "b.txt") }.exceptionOrNull()
        assertTrue(e is VfsException.AlreadyExists)
        assertNotNull(vfs.stat(p("a.txt")))   // and the source survives
    }

    @Test fun delete_refuses_a_non_empty_directory_without_recursive() = runTest {
        vfs.create(p("d"), isDir = true)
        vfs.create(p("d/x.txt"), isDir = false)
        val e = runCatching { vfs.delete(p("d")) }.exceptionOrNull()
        assertTrue(e is VfsException.Unsupported)
        assertNotNull(vfs.stat(p("d/x.txt")))

        vfs.delete(p("d"), recursive = true)
        assertNull(vfs.stat(p("d")))
    }

    @Test fun copy_duplicates_a_file_and_leaves_the_source() = runTest {
        vfs.create(p("dest"), isDir = true)
        vfs.openWrite(p("src.bin")).use { it.write(ByteArray(200_000) { i -> i.toByte() }) }

        vfs.copy(p("src.bin"), p("dest"))

        assertEquals(200_000L, vfs.stat(p("dest/src.bin"))!!.size)
        assertNotNull(vfs.stat(p("src.bin")))
    }

    @Test fun copy_recurses_through_directories() = runTest {
        vfs.create(p("tree"), isDir = true)
        vfs.create(p("tree/inner"), isDir = true)
        vfs.openWrite(p("tree/inner/deep.txt")).use { it.write("deep".toByteArray()) }
        vfs.create(p("out"), isDir = true)

        vfs.copy(p("tree"), p("out"))

        assertEquals("deep", vfs.openRead(p("out/tree/inner/deep.txt")).use { String(it.readBytes()) })
    }

    @Test fun copy_reports_progress_that_reaches_the_total() = runTest {
        vfs.create(p("dest"), isDir = true)
        vfs.openWrite(p("big.bin")).use { it.write(ByteArray(300_000)) }

        var last = 0L
        var total = -2L
        vfs.copy(p("big.bin"), p("dest")) { last = it.done; total = it.total }

        assertEquals(300_000L, last)
        assertEquals(300_000L, total)
    }

    @Test fun move_relocates_and_removes_the_source() = runTest {
        vfs.create(p("dest"), isDir = true)
        vfs.openWrite(p("m.txt")).use { it.write("x".toByteArray()) }

        vfs.move(p("m.txt"), p("dest"))

        assertNotNull(vfs.stat(p("dest/m.txt")))
        assertNull(vfs.stat(p("m.txt")))
    }

    @Test fun copying_a_directory_into_itself_is_refused() = runTest {
        vfs.create(p("self"), isDir = true)
        val e = runCatching { vfs.copy(p("self"), p("self")) }.exceptionOrNull()
        assertTrue("expected refusal, got $e", e is VfsException.Unsupported || e is VfsException.AlreadyExists)
    }

    @Test fun list_of_a_file_is_not_a_directory() = runTest {
        vfs.create(p("f.txt"), isDir = false)
        val e = runCatching { vfs.list(p("f.txt")) }.exceptionOrNull()
        assertTrue(e is VfsException.NotADirectory)
    }

    @Test fun unknown_scheme_is_unsupported_not_a_crash() = runTest {
        val e = runCatching { vfs.stat(VPath.of("smb", "/share/x")) }.exceptionOrNull()
        assertTrue(e is VfsException.Unsupported)
    }
}
