package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The live walk, over a real folder tree. SEARCH.md §14.2 and §14.4.
 *
 * Three defects measured on a phone, each pinned here: the walk admitted names that only held
 * the query's letters scattered across them; it walked into a hidden thumbnail cache with hidden
 * folders off; and internal storage waited behind a network share in one shared queue.
 */
class WalkSearchTest {

    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-walk-" + System.nanoTime()).apply { mkdirs() }
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun vpath(f: File, scheme: String = "local") =
        VPath.of(scheme, f.absolutePath.replace(File.separatorChar, '/'))

    private fun file(dir: File, name: String) = File(dir, name).apply { parentFile.mkdirs(); writeText("x") }

    /** A provider over a real folder that can be told to hang on one directory. */
    private class Slow(
        private val inner: FileSystemProvider,
        override val remote: Boolean = false,
    ) : FileSystemProvider by inner {
        val gate = CompletableDeferred<Unit>()
        @Volatile var holding = false
        override suspend fun list(path: VPath): List<VNode> {
            if (holding) gate.await()
            return inner.list(path)
        }
    }

    private fun request(query: String, reach: Reach, roots: List<VPath>, hidden: Boolean = false, onWalked: ((VPath, Int) -> Unit)? = null) =
        SearchRequest(query, reach, origin = null, roots = roots, showHidden = hidden, onWalked = onWalked)

    @Test
    fun `scattered letters are not an answer`() = runBlocking {
        val phone = File(tmp, "phone")
        file(phone, "7_6M_views_51K_reactions_EPIC_spinning_backfire.jpg")
        file(phone, "Minecraft_world.zip")
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone)))))

        val names = walk.search(request("minecraft", Reach.Everywhere, listOf(vpath(phone)))).toList().map { it.node.name }
        assertEquals(listOf("Minecraft_world.zip"), names)
    }

    @Test
    fun `a hit carries the characters that matched`() = runBlocking {
        val phone = File(tmp, "phone")
        file(phone, "Microsoft.MinecraftUWP_8wekyb3d8bbwe")
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone)))))

        val hit = walk.search(request("minecraft", Reach.Everywhere, listOf(vpath(phone)))).toList().single()
        assertEquals(listOf(10..18), hit.spans)
    }

    @Test
    fun `hidden folders are not walked unless asked for`() = runBlocking {
        val phone = File(tmp, "phone")
        file(File(phone, ".hwthumbnails"), "minecraft_thumb.jpg")
        file(phone, "minecraft.zip")
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone)))))
        val roots = listOf(vpath(phone))

        val off = walk.search(request("minecraft", Reach.Everywhere, roots, hidden = false)).toList().map { it.node.name }
        val on = walk.search(request("minecraft", Reach.Everywhere, roots, hidden = true)).toList().map { it.node.name }.toSet()
        assertEquals(listOf("minecraft.zip"), off)
        assertEquals(setOf("minecraft.zip", "minecraft_thumb.jpg"), on)
    }

    @Test
    fun `local results do not wait for a share that is not answering`() = runBlocking {
        // THE ordering defect. One queue over every root meant internal storage waited its turn
        // behind each directory of a share. With a walker per drive, a share that hangs entirely
        // cannot hold up a single local result.
        val phone = File(tmp, "phone")
        val nas = File(tmp, "nas")
        file(phone, "minecraft_local.zip")
        file(nas, "minecraft_remote.zip")
        val share = Slow(LocalProvider(listOf(nas), scheme = "smb"), remote = true).apply { holding = true }
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone)), share)))
        val req = request("minecraft", Reach.Everywhere, listOf(vpath(nas, "smb"), vpath(phone)))

        val got = ConcurrentHashMap.newKeySet<String>()
        val run = async(Dispatchers.Default) { walk.search(req).collect { got += it.node.name } }
        // The local hit arrives while the share is still hung - and the share is listed FIRST.
        withTimeout(5_000) { while ("minecraft_local.zip" !in got) kotlinx.coroutines.delay(10) }
        assertFalse("the share answered while it was meant to be hung", "minecraft_remote.zip" in got)

        share.gate.complete(Unit)
        run.await()
        assertTrue("minecraft_remote.zip" in got)
    }

    @Test
    fun `every folder read is handed on, for indexing what a search already read`() = runBlocking {
        // SEARCH.md §14.7: a share walked by a search ends up indexed without being crawled,
        // because the walk hands each listing to whoever is listening.
        val phone = File(tmp, "phone")
        file(File(phone, "a"), "one.txt")
        file(File(phone, "b"), "two.txt")
        val listed = ConcurrentHashMap.newKeySet<String>()
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone))))) { dir, _ -> listed += dir.name }

        walk.search(request("anything", Reach.Everywhere, listOf(vpath(phone)))).toList()
        assertEquals(setOf("phone", "a", "b"), listed)
    }

    @Test
    fun `each walker reports how many folders it has read`() = runBlocking {
        val phone = File(tmp, "phone")
        file(File(phone, "a"), "one.txt")
        file(File(phone, "b"), "two.txt")
        val counts = ConcurrentHashMap<VPath, Int>()
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone)))))

        walk.search(request("x", Reach.Everywhere, listOf(vpath(phone))) { root, n -> counts[root] = n }).toList()
        assertEquals(3, counts[vpath(phone)])
    }

    @Test
    fun `a listener that throws does not fail the search`() = runBlocking {
        val phone = File(tmp, "phone")
        file(phone, "minecraft.zip")
        val walk = WalkSearchSource(Vfs(listOf(LocalProvider(listOf(phone))))) { _, _ -> error("index unavailable") }

        val names = walk.search(request("minecraft", Reach.Everywhere, listOf(vpath(phone)))).toList().map { it.node.name }
        assertEquals(listOf("minecraft.zip"), names)
    }
}
