package dev.niccc2007.filet.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Where the bytes of a copy are actually moved.
 *
 * A provider that declares `withContext(Dispatchers.IO)` on `openWrite` only moves the stream's
 * CONSTRUCTION off the caller's thread. Every `write` after that, and the `close` that ends it,
 * run wherever the caller happens to be - and for a copy that caller is a `viewModelScope`
 * coroutine, which is the main thread.
 *
 * Local files tolerate that. A socket does not: Android throws `NetworkOnMainThreadException`
 * on the first byte, so a copy to a mounted share fails while the same copy between two local
 * folders succeeds. That asymmetry is the whole bug and it is invisible from either side alone.
 *
 * Proving it on a real socket would need a device. Proving it here needs only the thread the
 * stream was touched from, which is the actual contract: the VFS moves bytes off the caller's
 * thread, whoever the caller is.
 */
class CopyThreadTest {

    /** A provider that records the thread each stream call arrives on. */
    private class ThreadSpyProvider(
        override val scheme: String,
        val content: ByteArray = ByteArray(64 * 1024) { it.toByte() },
    ) : FileSystemProvider {

        val readThreads = mutableListOf<Thread>()
        val writeThreads = mutableListOf<Thread>()
        val closeThreads = mutableListOf<Thread>()
        var written = 0L

        override val capabilities = setOf(Capability.READ, Capability.WRITE)

        override suspend fun roots() = emptyList<VNode>()
        override suspend fun list(path: VPath) = emptyList<VNode>()

        override suspend fun stat(path: VPath): VNode? = when {
            path.path.endsWith("source.bin") -> VNode(path, false, content.size.toLong(), 0L)
            // The destination exists only once something has been written to it, which is what
            // `copyNode` checks before it starts and again when it finishes.
            path.path.endsWith("landed.bin") && written > 0 -> VNode(path, false, written, 0L)
            else -> null
        }

        override suspend fun openRead(path: VPath): InputStream =
            object : InputStream() {
                private val src = ByteArrayInputStream(content)
                override fun read(): Int {
                    readThreads += Thread.currentThread()
                    return src.read()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    readThreads += Thread.currentThread()
                    return src.read(b, off, len)
                }
            }

        override suspend fun openWrite(path: VPath, append: Boolean): OutputStream =
            object : OutputStream() {
                override fun write(b: Int) {
                    writeThreads += Thread.currentThread()
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writeThreads += Thread.currentThread()
                    written += len
                }

                override fun close() {
                    closeThreads += Thread.currentThread()
                }
            }

        override suspend fun create(path: VPath, isDir: Boolean) = VNode(path, isDir, 0L, 0L)
        override suspend fun delete(path: VPath, recursive: Boolean) = Unit
        override suspend fun rename(path: VPath, newName: String) =
            VNode(path.parent!!.child(newName), false, 0L, 0L)
    }

    @Test
    fun `a copy touches neither stream on the thread that asked for it`() {
        val from = ThreadSpyProvider("spysrc")
        val into = ThreadSpyProvider("spydst")
        val vfs = Vfs(listOf(from, into))

        val caller = Thread.currentThread()
        runBlocking {
            vfs.copy(
                VPath.of("spysrc", "/source.bin"),
                VPath.of("spydst", "/box"),
                rename = "landed.bin",
            )
        }

        assertTrue("nothing was read", from.readThreads.isNotEmpty())
        assertTrue("nothing was written", into.writeThreads.isNotEmpty())
        assertTrue("the stream was never closed", into.closeThreads.isNotEmpty())

        // The close is the one that matters most and is the easiest to leave behind: a buffering
        // provider sends its whole request there, so a close on the caller's thread is a whole
        // upload on the caller's thread.
        for (t in from.readThreads + into.writeThreads + into.closeThreads) {
            assertNotEquals("stream touched on the calling thread", caller, t)
        }
    }

    @Test
    fun `the bytes still arrive`() {
        val from = ThreadSpyProvider("spysrc2")
        val into = ThreadSpyProvider("spydst2")
        val vfs = Vfs(listOf(from, into))

        val landed = runBlocking {
            vfs.copy(
                VPath.of("spysrc2", "/source.bin"),
                VPath.of("spydst2", "/box"),
                rename = "landed.bin",
            )
        }

        assertEquals(64L * 1024, into.written)
        assertEquals("landed.bin", landed.name)
    }
}
