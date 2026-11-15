package dev.niccc2007.filet.handlers

/**
 * An editor's undo history, kept apart from any editor so it can be tested and shared: the text
 * editor and the hex editor each hold one, with their own kind of edit.
 *
 * It is a TREE of states rather than two stacks. Undoing and then making a new edit starts a new
 * branch from where the undo stopped; the old branch is kept, out of Redo's reach, so a branch
 * view can be put on top later without changing how the history is stored. Redo follows the
 * newest branch, which is what makes "new typing after an undo ends Redo" true: the new edit is
 * the newest branch, and it has nothing after it.
 *
 * The saved state is a node too. The file is clean exactly when the editor stands on that node,
 * so undoing back to what was saved makes it clean again, and a save marks wherever the editor
 * stands.
 *
 * Typing runs: an edit pushed soon after the last, onto a node still open for it, may be folded
 * into that node by [merge] rather than becoming a step of its own. A node closes when the editor
 * leaves it (undo, redo) or the file is saved, so a run never swallows a save or an undo.
 *
 * @param merge the edit [older] and [newer] make together, or null when they are two steps.
 * @param runMs how close two edits must be to be one step.
 */
class EditHistory<E : Any>(
    private val merge: (older: E, newer: E) -> E? = { _, _ -> null },
    private val runMs: Long = RUN_MS,
) {
    private class Node<E : Any>(var edit: E?, val parent: Node<E>?, var at: Long) {
        val children = ArrayList<Node<E>>()
    }

    private val root = Node<E>(null, null, 0L)
    private var current = root
    private var clean: Node<E>? = root
    private var open = false

    val canUndo: Boolean get() = current !== root
    val canRedo: Boolean get() = current.children.isNotEmpty()

    /** Whether the editor stands where the file was last saved. */
    val isClean: Boolean get() = current === clean

    /** How many steps Undo can take from here. */
    val depth: Int
        get() {
            var n = 0
            var at: Node<E>? = current
            while (at != null && at !== root) {
                n++
                at = at.parent
            }
            return n
        }

    /** Record [edit], made at [at] milliseconds. */
    fun push(edit: E, at: Long) {
        val top = current
        if (open && top !== root && top !== clean && top.children.isEmpty() && at - top.at in 0..runMs) {
            val folded = merge(top.edit!!, edit)
            if (folded != null) {
                top.edit = folded
                top.at = at
                return
            }
        }
        val next = Node(edit, top, at)
        top.children += next
        current = next
        open = true
    }

    /** Step back: the edit to take out, or null at the start. */
    fun undo(): E? {
        val from = current
        val back = from.parent ?: return null
        current = back
        open = false
        return from.edit
    }

    /** Step forward along the newest branch: the edit to put back, or null when there is none. */
    fun redo(): E? {
        val next = current.children.lastOrNull() ?: return null
        current = next
        open = false
        return next.edit
    }

    /** The file now holds what the editor shows. */
    fun markClean() {
        clean = current
        open = false
    }

    /** End the typing run, so the next edit is a step of its own. */
    fun breakRun() {
        open = false
    }

    companion object {
        /** Typing pauses longer than this start a new step. */
        const val RUN_MS = 1_000L
    }
}

/**
 * One change to a text: at [start], [removed] was taken out and [inserted] put in.
 *
 * @param typed made by one keystroke - a character in or out - and so free to join a run.
 */
data class TextEdit(val start: Int, val removed: String, val inserted: String, val typed: Boolean = isKeystroke(removed, inserted)) {
    /** The edit that takes this one back out. */
    fun inverse(): TextEdit = TextEdit(start, inserted, removed, typed)

    companion object {
        private fun isKeystroke(removed: String, inserted: String) =
            (removed.isEmpty() && inserted.length == 1) || (inserted.isEmpty() && removed.length == 1)

        /**
         * Two keystrokes as one step, the way editors group typing: letters typed one after
         * another, or deleted one after another with either key, until a line ends or a word
         * does. A paste is always a step of its own.
         */
        fun merge(older: TextEdit, newer: TextEdit): TextEdit? {
            if (!older.typed || !newer.typed) return null
            // Typing on.
            if (older.removed.isEmpty() && newer.removed.isEmpty() && newer.start == older.start + older.inserted.length) {
                val c = newer.inserted[0]
                if (c == '\n') return null
                // A space after a word ends the word's step.
                if (c.isWhitespace() && !older.inserted.last().isWhitespace()) return null
                return TextEdit(older.start, "", older.inserted + newer.inserted, typed = true)
            }
            // Deleting backwards.
            if (older.inserted.isEmpty() && newer.inserted.isEmpty() && newer.start + newer.removed.length == older.start) {
                if (newer.removed[0] == '\n') return null
                return TextEdit(newer.start, newer.removed + older.removed, "", typed = true)
            }
            // Deleting forwards.
            if (older.inserted.isEmpty() && newer.inserted.isEmpty() && newer.start == older.start) {
                if (newer.removed[0] == '\n') return null
                return TextEdit(older.start, older.removed + newer.removed, "", typed = true)
            }
            return null
        }
    }
}
