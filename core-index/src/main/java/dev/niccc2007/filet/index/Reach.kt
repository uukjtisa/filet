package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath

/**
 * How far a search covers. SEARCH.md §13.
 *
 * ## What this replaces, and why it is not an enum
 *
 * `SearchScope` was one enum carrying two unrelated questions and one thing that was not a
 * question at all: `FOLDER` and `SUBFOLDERS` asked how far down the tree, `DEVICE` asked which
 * drives, and `PROVENANCE` asked which *field* - which is `from:` in the query grammar and was
 * never a reach. Three axes in four members.
 *
 * The cost was concentrated in the middle of it. **`DEVICE` named a set nobody could point
 * at:** internal storage, or every local volume including the card and the root mounts, or
 * everything including the network shares. In practice it meant whichever of those the crawl
 * happened to have rows for, which is not stable between two launches. A scope whose extent is
 * unknowable gets worked around rather than used - the query is retyped at a narrower setting
 * until the answer looks plausible, which is what somebody does once they have learned a
 * control lies to them.
 *
 * So reach is a value. [Drives] is the member an enum could not express: two cards but not the
 * third, or one share and nothing else. That case is not exotic - from Home there is no folder
 * to stand in, so the ladder's "this drive" rung is unavailable while the wish to search
 * exactly one drive is unchanged.
 */
sealed interface Reach {

    /** This directory, not its subdirectories. One `readdir`, and the index declines it. */
    data object Folder : Reach

    /** This directory and everything beneath it. */
    data object Subtree : Reach

    /**
     * These drives, whole.
     *
     * An empty set is legal and means every named drive has gone - a card pulled, a share
     * deleted. It retrieves nothing, deliberately and visibly: see [prune].
     */
    data class Drives(val keys: Set<VolumeKey>) : Reach

    /** Every drive there is, including the shares. The only reach that touches the network. */
    data object Everywhere : Reach

    /** True when this reach is meaningless without somewhere to stand. */
    val needsOrigin: Boolean
        get() = when (this) {
            Folder, Subtree -> true
            is Drives, Everywhere -> false
        }

    /** True when retrieval is bounded to a prefix of [SearchRequest.origin] rather than a volume. */
    val isTreeShaped: Boolean get() = needsOrigin

    /**
     * The volumes to retrieve from, or null for all of them.
     *
     * Null rather than "every key" on purpose: `Everywhere` must not be rewritten into a list
     * of the volumes that happened to be mounted when the query was built, because a share that
     * connects while the results are on screen is then permanently outside a reach that claims
     * to be everywhere.
     */
    fun volumes(): Set<VolumeKey>? = (this as? Drives)?.keys

    /**
     * Where a live walk starts.
     *
     * @param origin where the pane is standing.
     * @param all every drive root currently available, for [Everywhere].
     */
    fun walkRoots(origin: VPath?, all: List<VPath>): List<VPath> = when (this) {
        Folder, Subtree -> listOfNotNull(origin)
        is Drives -> keys.map { it.root }
        // Falling back to the origin matters: a walk with no roots emits nothing, which is
        // indistinguishable from a search that found nothing.
        Everywhere -> all.ifEmpty { listOfNotNull(origin) }
    }

    /** True when the walk descends. Only [Folder] does not. */
    val deep: Boolean get() = this != Folder

    /** True when answering this reach has to talk to something over the network. */
    fun touchesNetwork(remote: Set<VolumeKey>): Boolean = when (this) {
        Folder, Subtree -> false
        is Drives -> keys.any { it in remote }
        // Everywhere is defined as including the shares, so it reaches the network exactly
        // when there is a share to reach.
        Everywhere -> remote.isNotEmpty()
    }

    /**
     * This reach with vanished drives removed.
     *
     * Mandatory on read, and the reason [Drives] tolerates an empty set. A reach naming a card
     * that has been pulled must **narrow visibly** - the chip loses the drive and the note says
     * so - rather than quietly retrieving from a volume id that no longer resolves and
     * reporting no matches. The second behaviour is the stale-answer failure in another costume:
     * the answer is wrong and nothing on screen says why.
     */
    fun prune(available: Set<VolumeKey>): Reach = when (this) {
        is Drives -> Drives(keys.intersect(available))
        else -> this
    }

    /** True when this reach cannot retrieve anything, so the empty result needs explaining. */
    val isEmpty: Boolean get() = this is Drives && keys.isEmpty()
}

/**
 * One drive, as the index keys it.
 *
 * The volume's **root path** is the identity, because that is already what `volume.uuid` holds
 * and because of what the root path of a share is made of: `smb:///<connectionId>/`, carrying
 * the saved remote's id and not its host. That is deliberate upstream - `NetConnection.id`
 * appears in the path precisely so renaming a server or changing its address does not
 * invalidate every bookmark pointing into it - and the index inherits the property for free.
 * Renaming a share, moving it to a hotspot address, or rotating its password changes nothing
 * here. See SEARCH.md §13.7 for the two edits that do.
 */
data class VolumeKey(val root: VPath) {

    /** The `volume.uuid` this key maps to. */
    val uuid: String get() = root.toString()

    /** True for a drive reached over the network, which is the only kind that can be slow or absent. */
    val isRemote: Boolean get() = root.scheme in REMOTE_SCHEMES

    companion object {
        /**
         * The schemes served by a network provider.
         *
         * Listed rather than asked of the provider because this has to be answerable without a
         * `Vfs` - the reach ladder, the indexing gate and the battery rules in §13.9 all need to
         * know whether a drive is on the far side of Wi-Fi before anything is mounted or dialled.
         * `check-reach.mjs` fails the build when a provider declares `remote = true` with a
         * scheme that is missing here.
         */
        val REMOTE_SCHEMES = setOf("smb", "sftp", "ftp", "dav", "peer")

        fun of(root: VPath) = VolumeKey(root)
    }
}
