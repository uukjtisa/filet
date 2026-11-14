package dev.niccc2007.filet.storage

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walk behind Storage: everything counted once, and a folder final only when all of it is. */
class StorageWalkTest {

    private val root = "file:///storage/emulated/0"

    private fun dir(p: String, inode: Long = 0) = VNode(path = VPath.parse("$root/$p"), isDir = true, size = -1, mtime = 0, inode = inode)
    private fun file(p: String, size: Long) = VNode(path = VPath.parse("$root/$p"), isDir = false, size = size, mtime = 0)

    /** A small tree; `locked` refuses its listing as Android/data does. */
    private val tree: Map<String, List<VNode>> = mapOf(
        "" to listOf(dir("Download"), dir("Android"), file("a.jpg", 10)),
        "Download" to listOf(dir("Download/Movies"), file("Download/x.zip", 90)),
        "Download/Movies" to listOf(file("Download/Movies/m.mp4", 4_000)),
        "Android" to listOf(dir("Android/locked")),
    )

    private fun lister(seen: MutableList<String> = ArrayList()): suspend (VPath) -> List<VNode> = { p ->
        val rel = p.toString().removePrefix(root).trimStart('/')
        seen += rel
        if (rel == "Android/locked") throw SecurityException("no")
        tree[rel] ?: emptyList()
    }

    @Test fun `every file is counted once and every folder settles`() = runBlocking {
        val map = SizeMap(VPath.parse(root).toString())
        val walk = StorageWalk(lister())
        walk.run(VPath.parse(root), map)
        assertEquals(4_100L, map.bytes(VPath.parse(root).toString()))
        assertEquals(4_090L, map.bytes(VPath.parse("$root/Download").toString()))
        assertTrue(map.settled(VPath.parse(root).toString()))
        assertTrue(map.settled(VPath.parse("$root/Download/Movies").toString()))
        // A refused folder is passed, counted, and still lets the folders above it settle.
        assertEquals(1, walk.unreadable)
        assertTrue(map.settled(VPath.parse("$root/Android").toString()))
        assertEquals(SizeKind.VIDEOS to 4_000L, map.kinds(VPath.parse(root).toString()).first())
    }

    @Test fun `a folder is not final while a folder inside it is unread`() = runBlocking {
        val map = SizeMap(VPath.parse(root).toString())
        val seen = ArrayList<String>()
        var checked = false
        val base = lister(seen)
        // Look at the map at the moment Movies is about to be listed: Download is listed, Movies not.
        val walk = StorageWalk({ p ->
            if (p.toString().endsWith("/Movies")) {
                assertFalse(map.settled(VPath.parse("$root/Download").toString()))
                assertFalse(map.settled(VPath.parse(root).toString()))
                checked = true
            }
            base(p)
        })
        walk.run(VPath.parse(root), map)
        assertTrue(checked)
        assertTrue(map.settled(VPath.parse("$root/Download").toString()))
    }

    @Test fun `a link back into the tree is walked once, not forever`() = runBlocking {
        val loop: Map<String, List<VNode>> = mapOf(
            "" to listOf(dir("a", inode = 7)),
            "a" to listOf(dir("a/again", inode = 7), file("a/f.bin", 5)),
        )
        val map = SizeMap(VPath.parse(root).toString())
        StorageWalk({ p -> loop[p.toString().removePrefix(root).trimStart('/')] ?: emptyList() }).run(VPath.parse(root), map)
        assertEquals(5L, map.bytes(VPath.parse(root).toString()))
        assertTrue(map.settled(VPath.parse(root).toString()))
    }

    @Test fun `ticks are spaced by the clock and there is always one at the end`() = runBlocking {
        var t = 0L
        var ticks = 0
        val walk = StorageWalk(lister(), clock = { t += 100; t }, tickMs = 250)
        walk.run(VPath.parse(root), SizeMap(VPath.parse(root).toString())) { ticks++ }
        // Five listings at 100 ms each: one tick at 300 ms, then the last.
        assertTrue("ticks=$ticks", ticks in 2..3)
    }
}
