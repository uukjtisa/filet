package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.VPath

/**
 * Which folders a fresh install tracks without being asked.
 *
 * ## Why this is seeded at all
 *
 * Tracked folders are the feature that answers "show me what just arrived on my phone", and a
 * report arrived asking for exactly that from somebody who had used the app and concluded it
 * could not do it. It can. The feature was simply invisible: the list started with `Download`
 * and nothing else, so Home had one card, and a person looking for their photos found no reason
 * to think the list was something they could add to.
 *
 * A feature nobody finds is indistinguishable from a feature nobody built. So the common
 * folders are tracked from the first launch: Home then opens with several cards, which both
 * answers the request directly and shows what the list IS - a set of places, with more
 * addable - without a tip, a tour or a dialogue.
 *
 * ## The cost, named rather than hidden
 *
 * The tracked list is also the inotify watch list, inotify costs one watch per **directory**,
 * and the per-process ceiling is around 8192. So `recursive` is not free and is not given to
 * everything: a camera roll, a pictures tree and a music library are the three where the files
 * genuinely live one level down (`DCIM/Camera`, `Pictures/Screenshots`, an album per artist),
 * and a downloads folder collects them too. `Documents` is flat in practice and is left flat.
 *
 * Even so, four recursive trees is the largest this list should ever get without measuring. A
 * music library of a thousand album folders is a thousand watches, and the budget is shared
 * with everything else the app watches.
 *
 * ## Why the planning is pure
 *
 * Three inputs decide it - the volumes, which candidates exist, and what is already tracked -
 * and getting any of them wrong is silent: a duplicate entry, a tracked folder that does not
 * exist and makes Home wait on it, or somebody's deliberately-removed folder coming back. So
 * it is a function with a test rather than a loop inside a startup block that runs once per
 * install and is never seen again.
 */
object TrackedSeeds {

    /**
     * The conventional Android media folders, with whether each is worth watching beneath.
     *
     * Names, not paths: they are resolved against every local volume, so an SD card gets its
     * own DCIM rather than the phone's being tracked twice.
     */
    val CANDIDATES: List<Pair<String, Boolean>> = listOf(
        "DCIM" to true,
        "Download" to true,
        "Pictures" to true,
        "Music" to true,
        "Documents" to false,
    )

    /**
     * Local volumes only, and `saf` is excluded on purpose.
     *
     * **This was wrong on the first run and the device said so.** A SAF root is a granted
     * SUBTREE, not a volume, so appending `DCIM` to it guesses at a structure nobody promised.
     * On a real phone the granted tree was `Android/media`, and the seeder duly tracked
     * `Android/media/DCIM`, `…/Pictures`, `…/Music` and `…/Documents` - four folders that do
     * not exist, two of them recursively.
     *
     * The existence check did not save it, which is the part worth keeping: asking the
     * document provider whether `Android/media/DCIM` was there came back yes for all four. So
     * the guard cannot be "check harder" - it has to be "do not ask the question", because the
     * conventional media folders live on the volume and a granted subtree is not one.
     *
     * `seedDefaults` keeps its own wider set. Narrowing that one would change behaviour nobody
     * reported a problem with, and `Download` under a granted tree at least names a folder
     * people really do grant.
     */
    val SEEDABLE_SCHEMES = setOf("local")

    /**
     * Every folder that could be seeded, for a caller to test for existence.
     *
     * Separate from [plan] because the existence check is IO and this is not. The caller asks
     * the VFS about these, then hands back the ones that answered yes.
     */
    fun candidatesFor(roots: List<VPath>): List<VPath> =
        roots.filter { it.scheme in SEEDABLE_SCHEMES }
            .flatMap { root -> CANDIDATES.map { (name, _) -> root.child(name) } }

    /**
     * What to add, given what exists and what is already there.
     *
     * @param present the subset of [candidatesFor] that actually exists on disk. A folder that
     *   is not there is not tracked: Home refreshes every tracked entry, and an entry that
     *   cannot be listed is a failure to absorb on every refresh for a folder nobody has.
     * @param already the paths already tracked, however they got there. Never touched - the
     *   list belongs to whoever has been editing it.
     * @param skip paths this pass must not add even when they are missing from [already].
     *   Used for `Download`, which earlier versions seeded: absent from the list on an existing
     *   install means it was removed on purpose, and re-adding it would overrule that. On a
     *   fresh install nothing is skipped, because nothing has been decided yet.
     */
    fun plan(
        roots: List<VPath>,
        present: Set<VPath>,
        already: List<VPath>,
        skip: Set<VPath> = emptySet(),
    ): List<TrackedFolder> {
        val have = already.toSet()
        val out = mutableListOf<TrackedFolder>()
        for (root in roots) {
            if (root.scheme !in SEEDABLE_SCHEMES) continue
            for ((name, recursive) in CANDIDATES) {
                val path = root.child(name)
                if (path !in present) continue
                if (path in have || path in skip) continue
                // Guards against the same volume arriving twice under two roots, which a
                // device with a mounted-over primary volume does.
                if (out.any { it.path == path }) continue
                out += TrackedFolder(path, recursive)
            }
        }
        return out
    }
}
