package dev.niccc2007.filet.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The script tab that stayed stale.
 *
 * Both assertions below fail against the code as it shipped, and they fail for the two
 * different reasons the one symptom had.
 */
class ScriptParamsTest {

    private fun script(id: String, name: String, approved: Boolean = false) = Script(
        id = id,
        name = name,
        source = "-- $id",
        permissions = ScriptPermissions(),
        fingerprint = "fp-$id",
        modifiedAt = 0L,
        approved = approved,
    )

    private val list = listOf(
        script("a", "Archive old logs"),
        script("b", "Batch rename"),
        script("c", "Count files"),
    )

    // ── cause one: an edited script jumped to the bottom ──

    @Test
    fun `editing a script leaves it where it was in the list`() {
        val edited = script("b", "Batch rename")           // same name, new content
        val after = ScriptList.replacing(list, edited)

        assertEquals(listOf("a", "b", "c"), after.map { it.id })
    }

    @Test
    fun `renaming a script moves it to where its new name belongs`() {
        // The control for the test above: order must follow the NAME, not be frozen. If
        // `replacing` simply preserved positions, the test above would pass and this would fail.
        val renamed = script("b", "Zip everything")
        val after = ScriptList.replacing(list, renamed)

        assertEquals(listOf("a", "c", "b"), after.map { it.id })
    }

    @Test
    fun `a new script lands in order rather than at the end`() {
        val added = ScriptList.replacing(list, script("d", "Backup photos"))
        assertEquals(listOf("Archive old logs", "Backup photos", "Batch rename", "Count files"), added.map { it.name })
    }

    @Test
    fun `ordering ignores case, because a capital letter is not a category`() {
        val mixed = listOf(script("x", "zebra"), script("y", "Apple"), script("z", "mango"))
        assertEquals(listOf("Apple", "mango", "zebra"), ScriptList.inOrder(mixed).map { it.name })
    }

    // ── cause two: approving emitted nothing a StateFlow would publish ──

    @Test
    fun `approving a script produces a list that is not equal to the old one`() {
        // This is the entire second bug. `StateFlow` drops a value equal to the one it holds,
        // so if approval is not IN the value, approving changes nothing anybody can observe and
        // the row keeps its old label until an unrelated edit rebuilds the list.
        val after = ScriptList.withApproval(list, "b", approved = true)

        assertNotEquals("a StateFlow would silently swallow an equal list", list, after)
        assertTrue(after.single { it.id == "b" }.approved)
    }

    @Test
    fun `revoking approval is equally visible`() {
        val approved = ScriptList.withApproval(list, "b", approved = true)
        val revoked = ScriptList.withApproval(approved, "b", approved = false)

        assertNotEquals(approved, revoked)
        assertEquals(list, revoked)
    }

    @Test
    fun `approving one script does not touch the others`() {
        val after = ScriptList.withApproval(list, "b", approved = true)
        assertEquals(listOf(false, true, false), after.map { it.approved })
        assertEquals(list.map { it.id }, after.map { it.id })
    }

    @Test
    fun `an id that is not in the list changes nothing`() {
        // A script deleted from another pane while its approval dialog was open.
        assertEquals(list, ScriptList.withApproval(list, "gone", approved = true))
    }
}
