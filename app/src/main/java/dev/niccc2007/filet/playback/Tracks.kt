package dev.niccc2007.filet.playback

import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.vfs.VNode

/**
 * VFS nodes as a queue the player can hold.
 *
 * The conversion exists because [Track] deliberately does not carry a `VNode`. The player, the
 * notification and the `MediaSession` all read a queue, and two of those three run with the
 * app in the background where a filesystem node is a thing to re-stat rather than to trust.
 * What travels is an id and the fields that get drawn.
 *
 * The id is `VPath.toString()`, so the node is recoverable when something genuinely needs it -
 * which is how Share and Properties on the player screen still work.
 */
fun tracksOf(nodes: List<VNode>): List<Track> = nodes.map { node ->
    Track(
        id = node.path.toString(),
        name = node.name,
        medium = if (FileKind.of(node) == FileKind.VIDEO) Medium.VIDEO else Medium.AUDIO,
    )
}
