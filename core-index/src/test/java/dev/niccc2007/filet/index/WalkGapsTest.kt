package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision 15: the index answers, the walk fills gaps. See [walkGaps]. */
class WalkGapsTest {

    private val phone = VPath.of("local", "/storage/emulated/0")
    private val card = VPath.of("local", "/storage/1A2B-3C4D")
    private val nas = VPath.of("smb", "/n1a2b3c")
    private val roots = listOf(phone, card, nas)

    @Test
    fun `a drive the index has walked completely is not walked again`() {
        assertEquals(listOf(card, nas), walkGaps(roots, setOf(phone.toString()), Reach.Everywhere))
    }

    @Test
    fun `with nothing covered the walk reads every drive`() {
        assertEquals(roots, walkGaps(roots, emptySet(), Reach.Everywhere))
    }

    @Test
    fun `a share with a complete index is the index's too`() {
        val covered = setOf(phone.toString(), card.toString(), nas.toString())
        assertEquals(emptyList<VPath>(), walkGaps(roots, covered, Reach.Drives(setOf(VolumeKey(nas)))))
    }

    @Test
    fun `a folder or a subtree is always read`() {
        val all = roots.map { it.toString() }.toSet()
        assertEquals(roots, walkGaps(roots, all, Reach.Folder))
        assertEquals(roots, walkGaps(roots, all, Reach.Subtree))
    }
}
