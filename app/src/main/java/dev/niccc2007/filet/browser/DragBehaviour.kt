package dev.niccc2007.filet.browser

/**
 * What dragging between two panes does.
 *
 * ## Why this is a setting and not a guess
 *
 * It used to be inferred: same volume meant move, across volumes meant copy. That is the rule
 * every desktop file manager uses and it is defensible - but it is also invisible, and the two
 * outcomes are not equally recoverable. A copy that should have been a move leaves a duplicate to
 * delete. A move that should have been a copy has already taken the file off the source, and
 * across a network that source may be a device nobody is holding.
 *
 * So the default asks, and the answer can be remembered.
 */
enum class DragBehaviour {
    /** Always move, whatever the volumes say. */
    CUT,

    /** Always copy. */
    COPY,

    /** Ask each time, with a remember option. */
    ASK,

    ;

    val label: String
        get() = when (this) {
            CUT -> "Move"
            COPY -> "Copy"
            ASK -> "Ask each time"
        }

    val detail: String
        get() = when (this) {
            CUT -> "Dragging takes the file out of the source folder."
            COPY -> "Dragging leaves the original where it is."
            ASK -> "A small prompt on every drag between panes."
        }
}

/** What a drop should actually do, once the setting and the drop are both known. */
enum class DropAction { MOVE, COPY, ASK }

object DragRules {

    /**
     * Resolve a drop.
     *
     * [inferredMove] is the old rule's answer - true when both sides are the same volume - and it
     * is used only as the DEFAULT SELECTION in the prompt, never as the action itself. A setting
     * that says ASK must ask; silently applying the inference would make the setting a lie.
     */
    fun resolve(behaviour: DragBehaviour): DropAction = when (behaviour) {
        DragBehaviour.CUT -> DropAction.MOVE
        DragBehaviour.COPY -> DropAction.COPY
        DragBehaviour.ASK -> DropAction.ASK
    }

    /**
     * Which button the prompt should have selected when it opens.
     *
     * The inference is a good guess and a bad decision, so this is where it belongs: it moves the
     * cursor, it does not move the file.
     */
    fun suggested(inferredMove: Boolean): DropAction =
        if (inferredMove) DropAction.MOVE else DropAction.COPY

    /**
     * The behaviour to store when somebody ticks "always do this".
     *
     * Never ASK: the whole point of the tick is to stop being asked.
     */
    fun remembered(chosen: DropAction): DragBehaviour = when (chosen) {
        DropAction.MOVE -> DragBehaviour.CUT
        DropAction.COPY -> DragBehaviour.COPY
        // Cannot be reached from the prompt, and returning ASK would store a setting that
        // re-opens the prompt forever.
        DropAction.ASK -> DragBehaviour.ASK
    }

    /** The word for what is about to happen, for the prompt's own title. */
    fun verb(action: DropAction): String = when (action) {
        DropAction.MOVE -> "Move"
        DropAction.COPY -> "Copy"
        DropAction.ASK -> "Move or copy"
    }
}
