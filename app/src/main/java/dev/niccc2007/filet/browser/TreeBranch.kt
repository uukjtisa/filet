package dev.niccc2007.filet.browser

import dev.niccc2007.filet.apk.BundleRoute
import dev.niccc2007.filet.vfs.MountBoundary
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.Archives

/**
 * What a tree row opens into, and how the tree's levels join across an archive.
 *
 * An archive is a folder everywhere, so in a tree a zip has a chevron, takes the focus, and
 * opens in place with its contents streaming in like any big folder. Its branch is keyed by the
 * archive's MOUNT - `zip:///…/a.zip!/` - rather than by the file: that is the path its contents
 * are listed from and the one their own paths hang off, so reading the branch is an ordinary
 * listing and the level above any row is [MountBoundary.up], which already knows that the top of
 * an archive sits in the folder the archive file is in.
 *
 * Only an archive a tap would mount opens in place. An APK or an app bundle opens the inspector,
 * as a tap on it does; a file not on local storage has to be staged before it can be read, which
 * is a cost a chevron must not hide.
 */
object TreeBranch {

    /** The branch [node] opens into: a folder's own path, an archive's mount, or null for a file. */
    fun keyOf(node: VNode): VPath? = when {
        node.isDir -> node.path
        opensInPlace(node) -> ArchiveProvider.mount(node.path.path)
        else -> null
    }

    fun opensInPlace(node: VNode): Boolean =
        !node.isDir &&
            node.path.scheme == HOST &&
            BundleRoute.tap(node.name, false) != BundleRoute.Tap.INSPECT &&
            Archives.canList(node.name)

    /**
     * Whether [p] is [root] or below it in a tree, an archive's contents counting as below the
     * folder the archive file is in. [VPath.contains] alone stops at the scheme, so a branch open
     * inside a zip read as outside the drive it is on.
     */
    fun under(root: VPath, p: VPath): Boolean {
        if (root.contains(p)) return true
        val host = MountBoundary.split(p)?.first ?: return false
        return root.contains(VPath(HOST, host))
    }

    /** The file system an archive opened in place lives on. */
    private const val HOST = "local"
}
