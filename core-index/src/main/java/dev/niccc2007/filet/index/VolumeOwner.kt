package dev.niccc2007.filet.index

/**
 * Which volume a path belongs to. SEARCH.md §13.8.
 *
 * It used to be `WHERE ? LIKE root || '%'`, a bare string prefix: with roots `smb:///n1` and
 * `smb:///n12`, every row under the second was attributed to the first - wrong drive, wrong
 * label, wrong reach filter. Not firing while connection ids are all nine characters long, and
 * exactly what a per-share index would surface. `LIKE` also read `_` and `%` in a root as
 * wildcards and folded case, so it was a looser match than it looked in two more ways.
 *
 * A root owns a path when the path IS the root, or continues it at a separator. A root that
 * already ends in one (a scheme root, `local:///`) owns everything under it. The longest owner
 * wins, so a volume mounted inside another still gets its own rows.
 */
object VolumeOwner {

    fun owns(root: String, path: String): Boolean = when {
        path == root -> true
        !path.startsWith(root) -> false
        root.endsWith('/') -> true
        else -> path[root.length] == '/'
    }

    /** The id of the deepest volume owning [path], or null when none does. */
    fun of(volumes: List<Pair<Long, String>>, path: String): Long? =
        volumes.filter { (_, root) -> owns(root, path) }.maxByOrNull { (_, root) -> root.length }?.first
}
