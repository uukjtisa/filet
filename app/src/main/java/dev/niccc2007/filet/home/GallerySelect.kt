package dev.niccc2007.filet.home

/**
 * The gallery's Select button: choosing files with nothing chosen yet.
 *
 * ## Why a button, when a hold already selects
 *
 * In the gallery a hold lifts the tile into its peek - the clips of a video, the picture larger
 * - and that is the hold worth keeping. Selecting was the peek's Select, or a hold-and-slide, and
 * neither says it is there: a gallery with no visible way to choose looks like one that cannot.
 * So the header carries Select, as Photos on iOS does. Pressed, it puts the gallery into choosing
 * with nothing chosen: a tap picks or drops a tile, a hold-and-slide picks a run, and Cancel or
 * Back leaves.
 *
 * ## Choosing is the screen's, not the pane's
 *
 * Everywhere else a selection running means something is selected, and the selection bar, Back
 * and the actions all read it that way. Choosing with nothing picked is a state only this screen
 * has, so it lives here and asks nothing of the rest: at zero picked there is no selection, so
 * the selection bar is not drawn and offers no action that needs a file.
 *
 * ## Where the button is, and its limit
 *
 * In the gallery's header, beside the List and Gallery pair, because that is the gallery's own
 * bar. The header scrolls with the tiles, as the pair always has, so a long way down Select is
 * out of sight until the gallery is scrolled back up. While choosing that matters less than it
 * looks: once anything is picked the selection bar carries the count and its own clear, and
 * Back leaves choosing from anywhere in the grid.
 */
internal object GallerySelect {

    /** Whether a tap on a tile picks or drops it rather than opening it. */
    fun tapPicks(choosing: Boolean, picked: Int): Boolean = choosing || picked > 0

    /**
     * Whether a hold starts a run at the held tile instead of lifting it into its peek: only
     * while choosing with nothing picked. Otherwise the gallery's hold rules stand - the peek,
     * or with a selection running, extending it.
     */
    fun holdStartsRun(choosing: Boolean, picked: Int): Boolean = choosing && picked == 0

    /** Whether the header offers Cancel rather than Select. */
    fun cancelShown(choosing: Boolean, picked: Int): Boolean = choosing || picked > 0

    /** The words beside Cancel. */
    fun headline(picked: Int): String = when (picked) {
        0 -> "Select files"
        1 -> "1 selected"
        else -> "$picked selected"
    }
}
