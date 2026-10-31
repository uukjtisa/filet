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

    private fun p(path: String) = VPath.of("local", path)

    @Test
    fun `only folds that draw under the root are visible, shallowest first`() {
        val root = p("/storage/emulated/0")
        val open = setOf(
            p("/storage/emulated/0/DCIM/Camera"),
            p("/storage/emulated/0/DCIM"),
            p("/storage/emulated/0/Download"),
            // Under a folder that is not open, so it cannot draw.
            p("/storage/emulated/0/Music/Albums"),
            // Left open in a folder the pane walked out of.
            p("/sdcard2/Movies"),
        )
        assertEquals(
            listOf(
                p("/storage/emulated/0/DCIM"),
                p("/storage/emulated/0/Download"),
                p("/storage/emulated/0/DCIM/Camera"),
            ),
            TreeRows.visibleFolds(root, open),
        )
    }

    @Test
    fun `the root itself and other schemes are never visible folds`() {
        val root = p("/a")
        val open = setOf(root, VPath.of("smb", "/a/b"), p("/a/b"))
        assertEquals(listOf(p("/a/b")), TreeRows.visibleFolds(root, open))
    }

    @Test
    fun `visible folds agree with what the renderer draws`() {
        // The rule exists so three callers agree with flatten. Checked against it directly:
        // every visible fold has a row, and no fold left out has one.
        val root = p("/")
        val open = setOf(p("/a"), p("/a/two"), p("/c/x"))
        val rows = TreeRows.flatten(
            roots = listOf(a, dir("/c")),
            open = open,
            branches = mapOf(a.path to listOf(dir("/a/two")), p("/a/two") to emptyList()),
        )
        val drawn = rows.filter { !it.loading && !it.empty }.map { it.node.path }.toSet()
        val visible = TreeRows.visibleFolds(root, open)
        assertTrue(visible.all { it in drawn })
        assertTrue(p("/c/x") !in visible)
    }

    @Test
    fun `a capped save never keeps a child without its parent`() {
        val root = p("/r")
        val open = setOf(p("/r/a/b/c"), p("/r/a/b"), p("/r/a"), p("/r/z"))
        val saved = TreeRows.foldsToSave(root, open, cap = 2)
        assertEquals(listOf(p("/r/a"), p("/r/z")), saved)
        assertEquals(emptyList<VPath>(), TreeRows.foldsToSave(null, open))
    }

    @Test
    fun `a tree is read only once every visible fold has something back`() {
        val root = p("/r")
        val open = setOf(p("/r/a"), p("/r/a/b"), p("/elsewhere/x"))
        val partly = mapOf(p("/r/a") to listOf(dir("/r/a/b")))
        assertTrue(!TreeRows.foldsRead(root, open, partly))
        // A fold that does not draw is not waited for.
        val whole = partly + (p("/r/a/b") to emptyList())
        assertTrue(TreeRows.foldsRead(root, open, whole))
    }

    @Test
    fun `a reveal lands on the folder's own row, never its placeholder`() {
        val target = p("/a")
        val rows = listOf(
            TreeRow(a, 1, loading = true),
            TreeRow(a, 0),
        )
        assertEquals(1, TreeRows.revealIndex(rows, target))
        assertEquals(-1, TreeRows.revealIndex(listOf(TreeRow(a, 1, empty = true)), target))
    }

    @Test
    fun `reach leaves half of a phone viewport empty`() {
        assertEquals(280f, ThumbReach.gapDp(560f, on = true), 0.01f)
        assertEquals(0f, ThumbReach.gapDp(600f, on = false), 0.01f)
    }

    @Test
    fun `reach always keeps room for three rows`() {
        // A landscape phone: two fifths of 250 would leave about two rows on screen.
        assertEquals(250f - ThumbReach.KEEP_DP, ThumbReach.gapDp(250f, on = true), 0.01f)
        // Shorter than the rows themselves: no gap at all, never a negative one.
        assertEquals(0f, ThumbReach.gapDp(120f, on = true), 0.01f)
        assertEquals(0f, ThumbReach.gapDp(0f, on = true), 0.01f)
    }
}
