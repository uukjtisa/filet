package dev.niccc2007.filet.docs

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * How tall a table or a code block is drawn. Pure.
 *
 * A block opens at its full height, so the page's own scroll reads all of it. It used to open
 * held to a short window that scrolled inside itself, which on a phone is a box of a few rows
 * with a second scroll nested in the first: the page and the block fight over every drag, and a
 * long table reads as a fragment until it is found that it scrolls. Collapse folds it to that
 * short window on purpose, for the block that is in the way; a hand-set size from the Resize
 * chip wins over both.
 */
internal object BlockFold {

    /**
     * The most the block may be tall, in dp, or null for no limit at all - its full height.
     * [hand] is a height set from the Resize chip, [short] the folded height.
     */
    fun cap(hand: Float?, collapsed: Boolean, short: Float): Float? = hand ?: if (collapsed) short else null

    /** The block is drawn shorter than its full height, so the chip offers Expand. */
    fun folded(collapsed: Boolean, hand: Float?): Boolean = collapsed || hand != null

    /**
     * The Collapse chip has something to do: the block is taller than [short] when drawn whole
     * ([full], 0 before it has been measured), or it is already folded or sized by hand. A
     * block shorter than the folded height would collapse to exactly what it is.
     */
    fun offered(full: Float, short: Float, collapsed: Boolean, hand: Float?): Boolean =
        folded(collapsed, hand) || full > short
}

/**
 * What one block of a document was left as, for as long as the document is open.
 *
 * Bug identified here: the reader's page is a lazy list, which keeps only the blocks on screen
 * composed. Each block held its own settings in `remember`, and `remember` is thrown away with
 * the block when it scrolls off and starts again when it comes back. So a table sized by hand
 * snapped back to its old height the moment it was scrolled out of view and back, and so did
 * every other choice made on a block: a table's Fit or Comfortable layout, a code block's Wrap
 * and Hide, how far a block had been scrolled inside, a slide's opened speaker notes, and the
 * shape a picture had decoded to (so the text below jumped when it decoded again). They live
 * here instead, outside the list, keyed by the block's place in the document. See [DocMemory].
 */
@Stable
internal class BlockMemory {
    /** The hand-set size from the Resize chip. */
    val size = BlockSize()

    /** Folded to the short height by the Collapse chip. */
    var collapsed by mutableStateOf(false)

    /** The block's height drawn whole, in dp; 0 until it has been. What Collapse is offered on. */
    var fullH by mutableFloatStateOf(0f)

    /** A table's own layout, over the reading setting's; null follows the setting. */
    var table by mutableStateOf<TableMode?>(null)

    /** A code block's Wrap and Hide. */
    var wrap by mutableStateOf(false)
    var hidden by mutableStateOf(false)

    /** A slide's speaker notes opened. */
    var notesOpen by mutableStateOf(false)

    /** A picture block's shape once decoded, width over height; 0 until it has been. */
    var aspect by mutableFloatStateOf(0f)

    /** A picture block that could not be decoded, so it is not tried again on every return. */
    var broken by mutableStateOf(false)

    /** How far the block was scrolled inside: down, when it is folded, and across. */
    val down = ScrollState(0)
    val across = ScrollState(0)

    /** The Collapse chip: whatever is shorter than whole opens whole, and whole folds. */
    fun fold() {
        if (BlockFold.folded(collapsed, size.height)) {
            collapsed = false
            size.height = null
        } else {
            collapsed = true
        }
    }
}

/**
 * Every block's [BlockMemory] and every inline picture's [PictureSlot] for one open document.
 * Held by [DocPageState] and started again when the document itself changes, since a block's
 * index means a different block in a document that was edited and read again.
 */
internal class DocMemory {
    private val blocks = HashMap<Int, BlockMemory>()
    private val pictures = HashMap<Picture, PictureSlot>()

    fun block(index: Int): BlockMemory = blocks.getOrPut(index) { BlockMemory() }

    /**
     * A picture's slot: whether Load was tapped on it, the size it loaded at, or that it failed.
     * Not the decoded picture itself - a document of photographs would otherwise keep every one
     * it had shown in memory until it was closed; the picture loader's own cache brings a recent
     * one straight back, and the kept size holds its place while it does.
     */
    fun slot(p: Picture): PictureSlot = pictures.getOrPut(p) { PictureSlot() }
}

/** The open document's memory, for the runs of text inside its blocks. */
internal val LocalDocMemory = compositionLocalOf<DocMemory?> { null }

/** The memory of the block being drawn; null outside a reader's page. */
internal val LocalBlockMemory = compositionLocalOf<BlockMemory?> { null }

/**
 * The block's memory from its page, or one of its own where it is drawn outside a reader's page
 * (a single code block standing in for a file's source), which lives as long as the block does.
 */
@Composable
internal fun rememberBlockMemory(block: Any): BlockMemory = LocalBlockMemory.current ?: remember(block) { BlockMemory() }
