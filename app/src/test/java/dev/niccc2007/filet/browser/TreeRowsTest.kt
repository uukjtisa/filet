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
    fun `an opened folder that is empty adds nothing`() {
        val rows = TreeRows.flatten(listOf(a), setOf(a.path), mapOf(a.path to emptyList()))
        assertEquals(1, rows.size)
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
}
