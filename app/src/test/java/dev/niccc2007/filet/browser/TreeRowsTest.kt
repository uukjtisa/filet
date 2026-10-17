package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeRowsTest {

    private fun dir(path: String) = VNode(VPath.of("local", path), true, -1, 0)
    private fun file(path: String) = VNode(VPath.of("local", path), false, 10, 0)

    private val a = dir("/a")
    private val b = file("/b")

    @Test
    fun `a closed tree is the listing it started as`() {
        val rows = TreeRows.flatten(listOf(a, b), emptySet(), emptyMap())
        assertEquals(listOf("/a", "/b"), rows.map { it.node.path.path })
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test
    fun `an opened folder shows its children under it, one deeper`() {
        val rows = TreeRows.flatten(
            roots = listOf(a, b),
            open = setOf(a.path),
            branches = mapOf(a.path to listOf(file("/a/one"), dir("/a/two"))),
        )
        assertEquals(listOf("/a", "/a/one", "/a/two", "/b"), rows.map { it.node.path.path })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
    }

    @Test
    fun `an opened folder that has not been read yet says so`() {
        val rows = TreeRows.flatten(listOf(a), setOf(a.path), emptyMap())
        assertEquals(2, rows.size)
        assertTrue(rows[1].loading)
        assertEquals(1, rows[1].depth)
    }

    @Test
    fun `an opened folder that is empty says so`() {
        // It used to add nothing, on the reasoning that the chevron turning was enough
        // acknowledgement. A folder known to be empty has no chevron any more, so reaching
        // this state means the count was stale or the read failed - and "it opened and
        // nothing happened" is indistinguishable from the chevron being broken.
        val rows = TreeRows.flatten(listOf(a), setOf(a.path), mapOf(a.path to emptyList()))
        assertEquals(2, rows.size)
        assertTrue(rows[1].empty)
        assertEquals(1, rows[1].depth)
        // It carries its PARENT's node, same as a loading row, so the guide geometry and the
        // working-folder frame already know where it belongs.
        assertEquals(a.path, rows[1].node.path)
    }

    @Test
    fun `the depth guard says so rather than stopping silently`() {
        // A tree that simply stops twelve deep looks broken at exactly the moment somebody is
        // lost enough to be twelve deep.
        val rows = TreeRows.flatten(
            roots = listOf(a),
            open = setOf(a.path),
            branches = mapOf(a.path to listOf(file("/a/one"))),
            depth = 1,
        )
        assertTrue("the guard drew no note", rows.any { it.empty })
    }

    @Test
    fun `the way to a folder is every folder on the way, including it`() {
        val root = VPath.of("local", "/storage/emulated/0")
        val target = VPath.of("local", "/storage/emulated/0/Download/Music")
        val path = TreeRows.pathTo(root, target)
        assertEquals(
            setOf(
                "/storage/emulated/0",
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Download/Music",
            ),
            path.map { it.path }.toSet(),
        )
    }

    @Test
    fun `the way to the root is the root`() {
        val root = VPath.of("local", "/storage/emulated/0")
        assertEquals(setOf(root), TreeRows.pathTo(root, root))
    }

    @Test
    fun `a target outside the root does not walk for ever`() {
        // The guard that matters: a parent chain that never reaches the root would otherwise
        // spin on the main thread, and this is reachable with an archive mount or a remote.
        val root = VPath.of("local", "/storage/emulated/0")
        val elsewhere = VPath.of("local", "/data/local/tmp/thing")
        assertEquals(setOf(elsewhere), TreeRows.pathTo(root, elsewhere))
    }

    @Test
    fun `nesting goes as deep as it is opened`() {
        val rows = TreeRows.flatten(
            roots = listOf(a),
            open = setOf(a.path, VPath.of("local", "/a/two")),
            branches = mapOf(
                a.path to listOf(dir("/a/two")),
                VPath.of("local", "/a/two") to listOf(file("/a/two/deep")),
            ),
        )
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    }

    @Test
    fun `the depth guard stops a loop from freezing the list`() {
        // A folder whose branch contains itself - a symlinked loop, which exists.
        val loop = dir("/loop")
        val rows = TreeRows.flatten(
            roots = listOf(loop),
            open = setOf(loop.path),
            branches = mapOf(loop.path to listOf(loop)),
            depth = 3,
        )
        assertTrue("the tree ran away: ${rows.size}", rows.size <= 4)
    }

    @Test
    fun `closing a folder closes everything under it`() {
        val open = setOf(
            VPath.of("local", "/a"),
            VPath.of("local", "/a/two"),
            VPath.of("local", "/b"),
        )
        val after = TreeRows.toggle(open, VPath.of("local", "/a"))
        assertEquals(setOf(VPath.of("local", "/b")), after)
    }

    @Test
    fun `a sibling with a shared prefix is not closed by accident`() {
        // "/ab" starts with "/a" as a string and is not inside it.
        val open = setOf(VPath.of("local", "/a"), VPath.of("local", "/ab"))
        val after = TreeRows.toggle(open, VPath.of("local", "/a"))
        assertEquals(setOf(VPath.of("local", "/ab")), after)
    }

    @Test
    fun `toggling a closed folder opens it`() {
        val after = TreeRows.toggle(emptySet(), VPath.of("local", "/a"))
        assertEquals(setOf(VPath.of("local", "/a")), after)
    }
    @Test
    fun `spines say whether each ANCESTOR has siblings left, which is one level out from the column that uses it`() {
        // The contract that was read wrong, and the reason a line hung in mid-air.
        //
        // A row's guide in column L is the line coming down from the ancestor at depth L,
        // and it continues past the row only while that ancestor still has children below -
        // which is `spines[L + 1]`, the ancestor one level further in. `spines[L]` is a
        // different question: whether the OUTER folder has siblings somewhere further down
        // the screen, which is true long after its own sub-list has finished.
        //
        // a is not last; its only child b is. So a row under b must draw nothing in
        // column 0, even though spines[0] is true.
        val a = dir("/a")
        val b = dir("/a/b")
        val c = dir("/a/b/c")
        val z = dir("/z")
        val rows = TreeRows.flatten(
            roots = listOf(a, z),
            open = setOf(a.path, b.path),
            branches = mapOf(a.path to listOf(b), b.path to listOf(c)),
        )
        val deep = rows.first { it.node.path == c.path }
        assertEquals(2, deep.depth)
        assertEquals(listOf(true, false), deep.spines)
    }

}
