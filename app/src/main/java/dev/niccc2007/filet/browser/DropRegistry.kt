package dev.niccc2007.filet.browser

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where every drop target currently sits on screen.
 *
 * Compose's own `dragAndDropTarget` wants a `ClipData` transfer and gives a coarse
 * enter/exit callback per target. A file manager needs the opposite: a cheap hit test over
 * hundreds of rows, the topmost match, and a ghost that follows the finger. So targets
 * publish their bounds here from `onGloballyPositioned`, and one hit test resolves them.
 *
 * Keyed by a stable string so a recomposition replaces an entry instead of duplicating it,
 * and entries are removed on dispose so a scrolled-away row cannot keep accepting drops.
 */
@Stable
class DropRegistry {

    private data class Entry(val rect: Rect, val target: DropTarget, val depth: Int)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * @param depth higher wins when rectangles overlap. A folder row sits inside a pane, so
     *   it must be depth 1 against the pane's 0, or every drop lands on the pane.
     */
    fun put(key: String, rect: Rect, target: DropTarget, depth: Int) {
        entries[key] = Entry(rect, target, depth)
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun hitTest(point: Offset): DropTarget? =
        entries.values
            .filter { it.rect.contains(point) }
            .maxByOrNull { it.depth }
            ?.target

    fun clear() = entries.clear()
}
