package dev.niccc2007.filet.browser

import dev.niccc2007.filet.ui.Labels
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What you can do with a selection, as data.
 *
 * One bar holding the actions worth one tap, with More opening the rest. There used to be a
 * setting choosing between a bar and a menu; see [SelectionBarConfig] for why that was the
 * wrong question and what replaced it.
 *
 * Two renderings of the same thing is exactly how the two drift apart: an action added to the
 * menu and forgotten in the bar, or blocked in one and live in the other. So the list is built
 * once, here, with no Compose in it, and both renderings are just paint over it. Adding an
 * action means adding one entry and it appears in both.
 *
 * The list is also where it is decided which rows exist at all for a selection - see
 * [selectionActions] for the rule. A row that is shown but blocked carries the sentence it
 * answers with, because a phone has no hover and a greyed row with no reason is a guess.
 */
data class SelectionAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Why this cannot run right now, or null when it can. */
    val blocked: String? = null,
    /** Destructive, so it is drawn apart and in the warning colour. */
    val danger: Boolean = false,
    /** Actions are grouped; a divider is drawn where the group changes. */
    val group: Group = Group.FILE,
    val run: () -> Unit,
) {
    enum class Group { FILE, EDIT, PLACE }
}

/**
 * What a selection is made of, for deciding which actions mean anything.
 *
 * Counted once from the nodes and passed in, so every gate below is decided synchronously and
 * from the same facts: a row that appeared a moment after the menu opened, because its answer
 * needed a disk read, would be worse than a row that is greyed.
 */
data class SelectionShape(
    val count: Int,
    /** How many are folders. */
    val folders: Int = 0,
    /** How many the player can open: audio and video. */
    val playable: Int = 0,
    /** How many a gallery or music app could index: pictures, video and audio. */
    val media: Int = 0,
    /**
     * How many are AUDIO specifically.
     *
     * Distinct from [playable], which counts video too. A beat map is measured from a spectrum
     * and means nothing for a film, so offering it over a selection of videos would be a row
     * that runs and produces nothing anybody asked for.
     */
    val audio: Int = 0,
) {
    /** How many are files rather than folders. */
    val files: Int get() = count - folders

    companion object {
        fun of(nodes: List<dev.niccc2007.filet.vfs.VNode>): SelectionShape {
            var folders = 0
            var playable = 0
            var media = 0
            var audio = 0
            for (n in nodes) {
                val kind = FileKind.of(n)
                if (kind == FileKind.FOLDER) folders++
                if (kind == FileKind.AUDIO) audio++
                if (kind == FileKind.AUDIO || kind == FileKind.VIDEO) playable++
                if (kind == FileKind.AUDIO || kind == FileKind.VIDEO || kind == FileKind.IMAGE) media++
            }
            return SelectionShape(nodes.size, folders, playable, media, audio)
        }

        /** [count] plain files, none of them media. What a caller with no nodes in hand means. */
        fun files(count: Int) = SelectionShape(count)
    }
}

/**
 * Every selection action that means something for this selection, in order.
 *
 * ## The rule: absent when meaningless, blocked only when temporarily impossible
 *
 * A row that can never act on what is selected is not shown. "Add to playlist" on a PDF,
 * "Rename" on five files and "Extract" on a photo are not unavailable - they mean nothing, and a
 * greyed row is a control somebody presses to learn it does nothing. So each row has a gate
 * deciding whether it exists, and only a reason that a different moment could fix (a read-only
 * volume) leaves a row present and blocked.
 *
 * This replaced the earlier rule, "disabled, never hidden", which kept the menu one length for
 * every selection. That bought a stable list at the price of a playlist and a queue row on every
 * document, every archive and every folder in the app, each one answering a press with a
 * sentence about why it would not work. A list that changes length with the selection is the
 * cost of a list where every row does something.
 *
 * ## Mixed selections
 *
 * A row that applies to SOME of the selection is shown and says how many it will act on - "Add
 * to playlist · 11 of 12" for eleven songs and a screenshot. Hiding it there would make a
 * mostly-music selection lose its music actions to one stray file; showing it unqualified would
 * claim the screenshot goes in too.
 *
 * @param readOnly why the volume cannot be written to, or null.
 * @param archive true when the selection is exactly one archive Filet can read.
 * @param otherPane true when the view is split, so there is another pane to extract into.
 * @param picking true while a pick is live. Delete is dropped: somebody who came to CHOOSE a file
 *   should not have a destructive action one tap from the one they meant.
 * @param announceable the selection is on this device's own storage, so the media index can hold it.
 * @param elsewhere the one selected item lives somewhere other than the folder on screen - a
 *   search hit, a category, New files - so Open location is a step somewhere rather than a
 *   step to where it already is.
 */
fun selectionActions(
    count: Int,
    readOnly: String?,
    icons: SelectionIcons,
    on: SelectionCallbacks,
    archive: Boolean = false,
    otherPane: Boolean = false,
    picking: Boolean = false,
    installable: Boolean = false,
    hasMetadata: Boolean = false,
    announceable: Boolean = false,
    shape: SelectionShape = SelectionShape.files(count),
    elsewhere: Boolean = false,
): List<SelectionAction> {
    val single = shape.count == 1
    val oneFile = single && shape.files == 1

    /** "Label · 3 of 5" when a row acts on only part of the selection. */
    fun partOf(label: String, n: Int): String = if (n < shape.count) "$label · $n of ${shape.count}" else label

    return buildList {
        // Open location heads the list: it is about where the thing is, so it belongs with what
        // acts on the thing and not under "Put somewhere", where it sat and read as wrong.
        // Only where it goes somewhere: from a folder, the item is already in its folder.
        if (single && elsewhere) {
            on.reveal?.let { run ->
                add(SelectionAction("reveal", Labels.OPEN_LOCATION, icons.folder, run = run))
            }
        }
        // Copy and Move apply to anything that can be selected.
        add(SelectionAction("copy", "Copy", icons.copy, run = on.copy))
        add(SelectionAction("move", "Move", icons.cut, blocked = readOnly, run = on.move))
        // Send hands FILES to another app; a folder cannot be handed over and is skipped.
        if (shape.files > 0) {
            add(SelectionAction("send", partOf("Send", shape.files), icons.share, run = on.send))
        }
        // Install: one package. A `.xapk` opens the archive browser when tapped, so this row is
        // the only route to installing one.
        if (single && installable) {
            add(SelectionAction("install", "Install", icons.apk, run = on.install))
        }
        // Metadata: one file in a format the writer can read.
        if (single && hasMetadata) {
            add(SelectionAction("metadata", "Metadata", icons.cog, run = on.metadata))
        }
        // Add to gallery: pictures, video and audio on this phone's own storage. Filet announces
        // what it writes; this is for files that arrived some other way and that no gallery or
        // music app can see. A document or a file on a share has no place in the media index.
        if (announceable && shape.media > 0) {
            add(
                SelectionAction(
                    "gallery", partOf("Add to gallery", shape.media), icons.star,
                    group = SelectionAction.Group.PLACE, run = on.announce,
                ),
            )
        }
        // Compress takes files and folders alike.
        add(
            SelectionAction(
                "compress", "Compress", icons.zip,
                blocked = readOnly, group = SelectionAction.Group.EDIT, run = on.compress,
            ),
        )
        // One archive, one Extract.
        //
        // There were two rows, "Extract here" and "Extract to…", differing only in where the
        // extraction pointed. The ghosts follow the folder now, so standing still is "here"
        // and walking is "to" - the second row described a difference that no longer exists,
        // and two rows for one action is two things to read before choosing.
        if (archive) {
            // NOT blocked by this folder being read-only, and that is deliberate.
            //
            // "Extract here" was blocked and "Extract to..." was not, because the second one
            // put the files somewhere else. Merging them naively would have taken that with
            // it: browsing an archive inside a read-only volume - which is exactly when
            // extracting elsewhere is wanted - would have left the only row greyed out.
            //
            // So the row always starts, and the DESTINATION decides. The card refuses, names
            // the volume, and goes live the moment you walk somewhere writable. A refusal that
            // can be answered by moving belongs where the moving happens.
            add(
                SelectionAction(
                    "extract", "Extract", icons.zip,
                    group = SelectionAction.Group.EDIT, run = on.extractHere,
                ),
            )
            if (otherPane) {
                add(
                    SelectionAction(
                        "extractother", "Extract to the other pane", icons.share,
                        group = SelectionAction.Group.EDIT, run = on.extractToOtherPane,
                    ),
                )
            }
        }
        // Rename: one item, file or folder. Blocked rather than absent on a read-only volume,
        // because that is the place, not the selection, and moving the file elsewhere fixes it.
        if (single) {
            add(
                SelectionAction(
                    "rename", "Rename", icons.rename,
                    blocked = readOnly, group = SelectionAction.Group.EDIT, run = on.rename,
                ),
            )
        }
        // Open with: one FILE. A folder opens as a folder; there is nothing to choose.
        if (oneFile) {
            add(
                SelectionAction(
                    "openwith", "Open with", icons.open,
                    group = SelectionAction.Group.EDIT, run = on.openWith,
                ),
            )
        }
        // Playlist and queue: the tracks in the selection, and nothing else.
        if (shape.playable > 0) {
            add(
                SelectionAction(
                    "playlist", partOf("Add to playlist", shape.playable), icons.audio,
                    group = SelectionAction.Group.EDIT, run = on.addToPlaylist,
                ),
            )
            add(
                SelectionAction(
                    "queue", partOf("Add to queue", shape.playable), icons.rows,
                    group = SelectionAction.Group.EDIT, run = on.enqueue,
                ),
            )
        }
        // Measuring the beat, for audio alone.
        //
        // Here rather than only inside the player, which is where it used to be reachable and
        // only for the track already open: the useful moment is before anything is playing,
        // over a folder of tracks, so the pulse is ready when they are opened rather than
        // measured one at a time while somebody waits. It runs away from any screen - see
        // `BeatMaker` - so this row starts it and nothing has to stay open.
        if (shape.audio > 0) {
            add(
                SelectionAction(
                    "beatmap", partOf("Make beat maps", shape.audio), icons.audio,
                    group = SelectionAction.Group.EDIT, run = on.makeBeatMaps,
                ),
            )
        }
        // Details describe one item, file or folder.
        if (single) {
            add(
                SelectionAction(
                    "details", "Details", icons.info,
                    group = SelectionAction.Group.EDIT, run = on.details,
                ),
            )
        }
        // Any number of files and folders, one path a line - what is wanted on the way to pasting
        // them into a script.
        on.copyPath?.let { run ->
            add(SelectionAction("copypath", "Copy as path", icons.copy, group = SelectionAction.Group.EDIT, run = run))
        }
        // Here and not in the top bar's menu, which carried it, with Copy as path, Select all and
        // the create rows, all through a selection. Bug identified here: the selection had two
        // menus offering overlapping halves of one list, and the top one also offered New file
        // in the middle of choosing files. The selection's actions are this list, wherever it
        // is drawn.
        on.invert?.let { run ->
            add(SelectionAction("invert", "Invert selection", icons.invert, group = SelectionAction.Group.EDIT, run = run))
        }
        // Bookmark and Nearby take any number of files and folders.
        add(
            SelectionAction(
                "bookmark", "Bookmark", icons.star,
                group = SelectionAction.Group.PLACE, run = on.bookmark,
            ),
        )
        add(
            SelectionAction(
                "nearby", "Nearby", icons.wifi,
                group = SelectionAction.Group.PLACE, run = on.nearby,
            ),
        )
        // A home-screen shortcut points at one thing.
        if (single) {
            add(
                SelectionAction(
                    "shortcut", "Shortcut", icons.home,
                    group = SelectionAction.Group.PLACE, run = on.shortcut,
                ),
            )
        }
        // Last and on its own, because it cannot be undone - which is also why it is absent
        // entirely while picking rather than merely blocked.
        if (!picking) {
            add(
                SelectionAction(
                    "delete", "Delete", icons.delete,
                    blocked = readOnly, danger = true,
                    group = SelectionAction.Group.PLACE, run = on.delete,
                ),
            )
        }
    }
}

/** The icons, passed in so this file stays free of the icon set and testable on the JVM. */
data class SelectionIcons(
    val copy: ImageVector,
    val cut: ImageVector,
    val share: ImageVector,
    val delete: ImageVector,
    val zip: ImageVector,
    val rename: ImageVector,
    val open: ImageVector,
    val info: ImageVector,
    val star: ImageVector,
    val wifi: ImageVector,
    val home: ImageVector,
    val apk: ImageVector,
    val cog: ImageVector,
    val audio: ImageVector,
    val rows: ImageVector,
    val folder: ImageVector = open,
    val invert: ImageVector = rows,
)

/** What each action does. Separate from the list so the list can be built in a test. */
data class SelectionCallbacks(
    val copy: () -> Unit,
    val move: () -> Unit,
    val send: () -> Unit,
    val delete: () -> Unit,
    val compress: () -> Unit,
    val rename: () -> Unit,
    val openWith: () -> Unit,
    val details: () -> Unit,
    val bookmark: () -> Unit,
    val nearby: () -> Unit,
    val shortcut: () -> Unit,
    val extractHere: () -> Unit = {},
    val extractToOtherPane: () -> Unit = {},
    val install: () -> Unit = {},
    val metadata: () -> Unit = {},
    val announce: () -> Unit = {},
    val addToPlaylist: () -> Unit = {},
    val makeBeatMaps: () -> Unit = {},
    val enqueue: () -> Unit = {},
    // Null where a surface does not offer the row at all, rather than an empty lambda that would
    // draw a row which does nothing.
    val copyPath: (() -> Unit)? = null,
    val invert: (() -> Unit)? = null,
    val reveal: (() -> Unit)? = null,
)
