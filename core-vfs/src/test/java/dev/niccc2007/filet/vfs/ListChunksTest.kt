package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.vfs.provider.Stat
import dev.niccc2007.filet.vfs.provider.Stats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The chunk plan, and that a chunked listing is still the same listing.
 *
 * The property worth defending is not the chunk sizes - those are a judgement about screens and
 * can move. It is that breaking a listing up cannot change what the listing contains. A streamed
 * read that drops the last partial chunk, or repeats one, or reorders across a boundary, is a
 * folder that lies about its contents in a way nobody would see until a file went missing from
 * a list it is really in.
 */
class ListChunksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── the plan ──

    @Test
    fun `an empty folder has no chunks`() {
        assertEquals(emptyList<Int>(), ListChunks.plan(0))
    }

    @Test
    fun `an ordinary folder is one piece`() {
        assertEquals(listOf(1), ListChunks.plan(1))
        assertEquals(listOf(40), ListChunks.plan(40))
        assertEquals(listOf(ListChunks.WHOLE), ListChunks.plan(ListChunks.WHOLE))
        assertFalse(ListChunks.streams(ListChunks.WHOLE))
    }

    @Test
    fun `one entry past the threshold starts streaming`() {
        assertTrue(ListChunks.streams(ListChunks.WHOLE + 1))
        assertEquals(ListChunks.FIRST, ListChunks.plan(ListChunks.WHOLE + 1).first())
    }

    @Test
    fun `the first chunk is the small one`() {
        val plan = ListChunks.plan(5000)
        assertEquals(ListChunks.FIRST, plan[0])
        assertTrue(plan[1] > plan[0])
    }

    @Test
    fun `every plan sums to exactly the number of entries`() {
        // The one that catches an off-by-one in the remainder loop, which is the bug that would
        // silently truncate the tail of every large folder in the app.
        for (n in listOf(0, 1, 119, 120, 121, 399, 400, 401, 520, 521, 1242, 4999, 100_000)) {
            assertEquals("total $n", n, ListChunks.plan(n).sum())
        }
    }

    @Test
    fun `no chunk is empty and none is oversized`() {
        for (n in listOf(401, 1242, 4999, 100_000)) {
            val plan = ListChunks.plan(n)
            assertTrue("total $n", plan.all { it in 1..ListChunks.REST })
        }
    }

    @Test
    fun `a folder exactly one chunk past the first divides evenly`() {
        val n = ListChunks.FIRST + ListChunks.REST
        assertEquals(listOf(ListChunks.FIRST, ListChunks.REST), ListChunks.plan(n))
    }

    // ── the streamed listing against the whole one ──

    private val stats = Stats { p ->
        Stat(mode = 0x81A4, size = 7, mtimeMillis = 1L, ino = p.hashCode().toLong(), uid = 1, gid = 1)
    }

    private fun seed(n: Int): Pair<LocalProvider, VPath> {
        val d = tmp.newFolder("many")
        repeat(n) { File(d, "f%05d.bin".format(it)).writeText("x") }
        return LocalProvider(listOf(tmp.root), stats = stats) to
            VPath.of("local", d.absolutePath.replace('\\', '/'))
    }

    private fun streamed(p: LocalProvider, path: VPath): List<List<VNode>> {
        val out = mutableListOf<List<VNode>>()
        runBlocking { p.list(path) { out += it } }
        return out
    }

    @Test
    fun `a streamed listing concatenates to the whole listing`() {
        val (p, path) = seed(1242)
        val whole = runBlocking { p.list(path) }.map { it.path.path }.sorted()
        val parts = streamed(p, path).flatten().map { it.path.path }.sorted()
        assertEquals(whole, parts)
    }

    @Test
    fun `chunks are disjoint`() {
        val (p, path) = seed(1242)
        val all = streamed(p, path).flatten().map { it.path.path }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a big folder really does arrive in more than one piece`() {
        // Without this the two tests above would pass just as happily against a provider that
        // ignored the plan entirely and sent everything at once.
        val (p, path) = seed(1242)
        assertTrue(streamed(p, path).size > 1)
    }

    @Test
    fun `a small folder arrives in exactly one piece`() {
        val (p, path) = seed(12)
        assertEquals(1, streamed(p, path).size)
    }

    @Test
    fun `an empty folder still gets one chunk`() {
        // A caller that waits for a chunk before drawing anything would hang forever on an empty
        // folder if this sent nothing, and an empty folder is not an error.
        val d = tmp.newFolder("nothing")
        val p = LocalProvider(listOf(tmp.root), stats = stats)
        val path = VPath.of("local", d.absolutePath.replace('\\', '/'))
        val out = streamed(p, path)
        assertEquals(1, out.size)
        assertTrue(out.single().isEmpty())
    }

    @Test
    fun `a missing folder throws rather than streaming nothing`() {
        val p = LocalProvider(listOf(tmp.root), stats = stats)
        val path = VPath.of("local", File(tmp.root, "gone").absolutePath.replace('\\', '/'))
        var chunks = 0
        val thrown = runCatching { runBlocking { p.list(path) { chunks++ } } }.exceptionOrNull()
        assertTrue("expected NotFound, got $thrown", thrown is VfsException.NotFound)
        assertEquals(0, chunks)
    }

    @Test
    fun `the default contract sends one chunk for a backend that cannot stream`() {
        // Every provider that does not override gets this, and it has to behave like a listing
        // rather than like silence.
        val fake = object : FileSystemProvider {
            override val scheme = "fake"
            override val capabilities = emptySet<Capability>()
            override suspend fun roots() = emptyList<VNode>()
            override suspend fun list(path: VPath) = listOf(
                VNode(VPath.of("fake", "/a"), isDir = false, size = 1, mtime = 0),
                VNode(VPath.of("fake", "/b"), isDir = false, size = 2, mtime = 0),
            )
            override suspend fun stat(path: VPath): VNode? = null
            override suspend fun openRead(path: VPath) = throw UnsupportedOperationException()
            override suspend fun openWrite(path: VPath, append: Boolean) =
                throw UnsupportedOperationException()
            override suspend fun create(path: VPath, isDir: Boolean) =
                throw UnsupportedOperationException()
            override suspend fun delete(path: VPath, recursive: Boolean) = Unit
            override suspend fun rename(path: VPath, newName: String) =
                throw UnsupportedOperationException()
        }
        val out = mutableListOf<List<VNode>>()
        runBlocking { fake.list(VPath.of("fake", "/")) { out += it } }
        assertEquals(1, out.size)
        assertEquals(2, out.single().size)
    }
}
