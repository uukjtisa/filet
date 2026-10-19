package dev.niccc2007.filet.browser


/** What a drop should actually do, once the setting and the drop are both known. */
enum class DropAction { MOVE, COPY, ASK }

object DragRules {


    /**
     * Which button the prompt should have selected when it opens.
     *
     * The inference is a good guess and a bad decision, so this is where it belongs: it moves the
     * cursor, it does not move the file.
     */
    fun suggested(inferredMove: Boolean): DropAction =
        if (inferredMove) DropAction.MOVE else DropAction.COPY


    /** The word for what is about to happen, for the prompt's own title. */
    fun verb(action: DropAction): String = when (action) {
        DropAction.MOVE -> "Move"
        DropAction.COPY -> "Copy"
        DropAction.ASK -> "Move or copy"
    }
}
