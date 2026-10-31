package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VPath

/**
 * Which search scopes a pane can actually answer, and what the index banner says.
 *
 * ## The fault this was written for
 *
 * Searching from Home with **Whole device** selected returned nothing at all, under a banner
 * reading "Still indexing". Two separate mistakes stacked into one dead feature:
 *
 *  - `searchPlan(HOME)` is `NONE`, so `runSearch` returned an empty result before building a
 *    request. The comment beside it says a search box on Home "cannot do anything, which is rule
 *    R1" - and then the search bar, the four scope chips and the field chips were all rendered on
 *    Home anyway. The control existed; only the answer was missing. That is the R1 violation the
 *    comment was trying to prevent, arrived at from the other direction.
 *  - The chips came from `SearchScope.entries`, every pane, every time. On Home there is no
 *    folder to stand in, so **This folder** and **Subfolders** cannot mean anything - and the bar
 *    opened with This folder ticked, which is a scope with no origin.
 *
 * ## What changed after that
 *
 * The scopes became a ladder of named drives - see [ReachLadder] and SEARCH.md §13. The two
 * functions this object used to carry for the question, `scopesFor` and `resolve`, live there
 * now; what is left here is the index banner and the body switch, which are about search but
 * not about reach.
 *
 * Nothing downstream was broken. The index holds the files, it serves queries while a crawl is
 * running, and [dev.niccc2007.filet.index.HitState] already carries Available and Confirming.
 * Home simply could not reach any of it.
 */
object SearchReach {

    /**
     * The line shown while a crawl is running.
     *
     * ## Why this is a function and not a string
     *
     * It read `"Still indexing — ${status.scanned} files"`, and `scanned` counts **this pass**,
     * not the index. So it started near zero on every launch and climbed, and read as the index
     * size - which made a restart look like the index had shrunk from 31k to 21k. It had not: the
     * database held 42,535 rows throughout.
     *
     * `IndexStatus.knownAtStart` was added for precisely this reason - a pass beginning at zero
     * is indistinguishable from an index that has been emptied, unless the previous total is
     * beside it. The remedy existed and this banner was never changed to use it.
     *
     * So the line now names both numbers and says which is which. The index total goes **first**,
     * because it is the one that must never look like it is going backwards.
     */
    fun indexingLine(status: IndexStatus, steeredTo: String? = null): String {
        val held = maxOf(status.files, status.knownAtStart)
        val checked = status.scanned
        val what = if (steeredTo != null) "Indexing \"$steeredTo\"" else "Indexing"
        return "$what · $held indexed · $checked checked this pass"
    }

    /**
     * Whether a pane is showing search results instead of its own contents.
     *
     * Bug identified, and it is the one that kept Home returning nothing after the plan and the
     * request were both fixed: the body was chosen by the pane's KIND alone
     * (`kind == PaneKind.FOLDER`). That was the same question for as long as only a folder pane
     * could search. The moment Home could, the two came apart - the search ran, the hits landed
     * in state, and the body still drew the Home overview, so a correct answer was never drawn.
     *
     * Only for a pane whose search reads the filesystem. A FILTER pane narrows rows it is already
     * drawing, so its own body stays right; swapping it would replace Settings with a file list.
     */
    fun showsResults(kind: PaneKind, searchActive: Boolean): Boolean =
        searchActive && searchPlan(kind) == SearchMode.FILESYSTEM

    /**
     * The line under the chips that says what is still working on this answer. SEARCH.md §14.8.
     *
     * The defect it answers: a search showed one row, its spinner stopped, and seconds later more
     * rows arrived - with nothing on screen in between to say anything was still going. Each part
     * here names one thing that can still change the list: the checker, and each share being
     * read. A share and not a local drive, because a local walker finishes before anybody could
     * read its line, and naming it would only make the line flicker.
     *
     * @param origin which sources answered, from `originLine`.
     */
    fun statusLine(
        origin: String,
        running: Boolean,
        checking: Int,
        walked: Map<VPath, Int>,
        drives: List<ReachDrive>,
    ): String = buildList {
        if (origin.isNotEmpty()) add(origin)
        if (checking > 0) add("checking $checking")
        if (running) {
            for ((root, folders) in walked) {
                val drive = drives.firstOrNull { it.root == root } ?: continue
                if (!drive.remote) continue
                add("reading ${drive.label}, ${"%,d".format(folders)} folders")
            }
        }
    }.joinToString("  ·  ")
}

/**
 * One drive, as the ladder and the volume sheet see it.
 *
 * A projection of `VolumeInfo` rather than that type itself, so the ladder is arithmetic over
 * four fields and its test needs no `Vfs`, no provider and no device.
 */
data class ReachDrive(
    val key: VolumeKey,
    val label: String,
    /** Reached over the network. The only kind that can be slow, or absent, or both. */
    val remote: Boolean,
    /** A superuser volume - `/data`, `/system`. Reachable, but never from a rung. */
    val system: Boolean,
) {
    val root: VPath get() = key.root
}

/** One step of the ladder: what the chip says, and what it searches. */
data class Rung(val label: String, val reach: Reach)

/**
 * The ladder of reaches a pane offers, from where it is standing. SEARCH.md §13.2.
 *
 * Every rung is **strictly larger than the one above it** and every rung **names a set that can
 * be pointed at**. Both properties are load-bearing, and the second is the one the old
 * `SearchScope.DEVICE` broke: "Whole device" meant whichever volumes the crawl happened to hold
 * rows for, which is not stable between two launches and is not something a person can reason
 * about before pressing it.
 *
 * Strictness is not decoration either. On a phone with no card and no shares, "this drive",
 * "this phone" and "everywhere" are the same set, and offering three chips that do one thing is
 * the dead-control rule (PLAN.md R1) three times over. Rungs that would not widen the search are
 * dropped, so the ladder is as long as the device makes it and no longer.
 */
object ReachLadder {

    /**
     * @param cwd where the pane is standing, or null on Home and the special screens.
     * @param drives every volume currently available.
     */
    fun rungsFor(cwd: VPath?, drives: List<ReachDrive>): List<Rung> = buildList {
        val here = driveAt(cwd, drives)
        if (cwd != null) {
            add(Rung("This folder", Reach.Folder))
            add(Rung("Everything under here", Reach.Subtree))
        }

        // Rung 3 names the drive by its own label. "This drive" in the abstract is the same
        // unpointable set the old wording was.
        val widest = ArrayList<Set<VolumeKey>>()
        if (here != null) {
            add(Rung(here.label, Reach.Drives(setOf(here.key))))
            widest += setOf(here.key)
        }

        // Rung 4 does not exist inside a share: "this phone" from a position on a NAS is either
        // a lie or a jump, and both are worse than the rung being absent.
        val phone = phoneKeys(drives)
        if (here?.remote != true && phone.isNotEmpty() && widest.none { it == phone }) {
            add(Rung("This phone", Reach.Drives(phone)))
            widest += phone
        }

        // Rung 5 is the only rung that touches the network, which is what lets the four above
        // it promise §5.5 latency without an asterisk. Offered only when there is something out
        // there to reach - with no share saved it would retrieve exactly what rung 4 does.
        if (drives.any { it.remote }) add(Rung("Everywhere", Reach.Everywhere))
    }

    /**
     * The reach to actually search with.
     *
     * A tree-shaped reach with nowhere to stand is not a narrower search, it is an impossible
     * one - and the pane used to answer it with silence rather than saying so. Coerced to the
     * phone, which is what somebody typing on Home means, and to [Reach.Everywhere] only when
     * there are no local drives to name, where there is nothing better to coerce to.
     *
     * Pruning happens here too, so one call covers both ways a reach can be unanswerable: it
     * had no origin, or it named a card that has since been pulled.
     */
    fun resolve(reach: Reach, cwd: VPath?, drives: List<ReachDrive>): Reach {
        if (reach.needsOrigin && cwd == null) return wholePhone(drives)
        return reach.prune(drives.map { it.key }.toSet())
    }

    /**
     * The widest reach that stays local: rung 4, by name.
     *
     * [Reach.Everywhere] when there are no local drives to name, because there is nothing
     * better to coerce to and a reach retrieving nothing would be read as the search failing.
     */
    fun wholePhone(drives: List<ReachDrive>): Reach {
        val phone = phoneKeys(drives)
        return if (phone.isEmpty()) Reach.Everywhere else Reach.Drives(phone)
    }

    /**
     * Where [Reach.Everywhere] sends a live walk: every drive except the superuser volumes.
     *
     * The guarantee behind SEARCH.md §13.9 rule 5, and it is enforced here rather than trusted:
     * `/data` is never crawled, so the index cannot return it, and this is the other half -
     * the walk is not pointed at it either. A root volume is reached only when it is ticked in
     * the volume sheet, which produces [Reach.Drives] naming it and walks it explicitly.
     */
    fun everywhereRoots(drives: List<ReachDrive>): List<VPath> =
        drives.filterNot { it.system }.map { it.root }

    /**
     * The drives "This phone" covers: everything local except the superuser volumes.
     *
     * The root mounts are deliberately out. Somebody searching this phone is looking for their
     * own files, and `/data/app/~hash~/base.apk` ranked beside a photo is the rung's entire
     * value spent. They are reachable by being ticked in the volume sheet, which is where a
     * deliberate search of the system belongs - see SEARCH.md §13.2.
     */
    fun phoneKeys(drives: List<ReachDrive>): Set<VolumeKey> =
        drives.filter { !it.remote && !it.system }.map { it.key }.toSet()

    /**
     * The drive [cwd] is standing on.
     *
     * Longest root first, because volumes nest: a granted folder can sit inside the internal
     * volume, and matching the shorter root would caption a pane with the wrong drive and search
     * a wider one than the chip claimed.
     */
    fun driveAt(cwd: VPath?, drives: List<ReachDrive>): ReachDrive? {
        if (cwd == null) return null
        return drives
            .filter { it.root.scheme == cwd.scheme && under(cwd.path, it.root.path) }
            .maxByOrNull { it.root.path.length }
    }

    private fun under(path: String, root: String): Boolean {
        if (path == root) return true
        val base = if (root.endsWith("/")) root else "$root/"
        return path.startsWith(base)
    }
}
