package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a pane can reach the search engine, and whether the banner tells the truth.
 *
 * Both of these were reported as one thing - "search everywhere returns nothing and the index
 * keeps shrinking" - and they are two unrelated faults, neither of them in the index. Measured on
 * the real database at the time: 42,535 rows, an FTS match answering in 1.8 ms.
 */
class SearchReachTest {

    private val internal = ReachDrive(
        key = VolumeKey(VPath.of("local", "/storage/emulated/0")),
        label = "Internal storage",
        remote = false,
        system = false,
    )
    private val card = ReachDrive(
        key = VolumeKey(VPath.of("local", "/storage/1A2B-3C4D")),
        label = "SD card",
        remote = false,
        system = false,
    )
    private val system = ReachDrive(
        key = VolumeKey(VPath.of("root", "/")),
        label = "System",
        remote = false,
        system = true,
    )
    private val nas = ReachDrive(
        key = VolumeKey(VPath.of("smb", "/n1a2b3c")),
        label = "Workshop NAS",
        remote = true,
        system = false,
    )

    private val everything = listOf(internal, card, system, nas)
    private val someFolder = VPath.of("local", "/storage/emulated/0/Download")
    private val onTheNas = VPath.of("smb", "/n1a2b3c/Projects")

    // ── the ladder ──

    @Test
    fun `a folder pane climbs from the folder to everywhere`() {
        assertEquals(
            listOf(
                "This folder",
                "Everything under here",
                "Internal storage",
                "This phone",
                "Everywhere",
            ),
            ReachLadder.rungsFor(someFolder, everything).map { it.label },
        )
    }

    @Test
    fun `the drive rung is named after the drive, not called this drive`() {
        // The whole fault in one assertion. "Whole device" named a set nobody could point at;
        // a rung that renders the volume's own label is a set that can be pointed at.
        val rungs = ReachLadder.rungsFor(someFolder, everything)
        assertTrue(rungs.any { it.label == "Internal storage" })
        assertEquals(
            Reach.Drives(setOf(internal.key)),
            rungs.first { it.label == "Internal storage" }.reach,
        )
    }

    @Test
    fun `on a phone with one drive and no shares the ladder is three rungs`() {
        // Strictness, which is not decoration: with only internal storage, "this drive", "this
        // phone" and "everywhere" are the same set, and offering three chips for one answer is
        // the dead-control rule three times over.
        assertEquals(
            listOf("This folder", "Everything under here", "Internal storage"),
            ReachLadder.rungsFor(someFolder, listOf(internal)).map { it.label },
        )
    }

    @Test
    fun `everywhere is offered only when there is something out there`() {
        assertFalse(
            ReachLadder.rungsFor(someFolder, listOf(internal, card)).any { it.label == "Everywhere" },
        )
        assertTrue(
            ReachLadder.rungsFor(someFolder, listOf(internal, card, nas)).any { it.label == "Everywhere" },
        )
    }

    @Test
    fun `this phone does not exist inside a share`() {
        // "This phone" from a position on a NAS is either a lie or a jump, and both are worse
        // than the rung being absent.
        val rungs = ReachLadder.rungsFor(onTheNas, everything).map { it.label }
        assertEquals(listOf("This folder", "Everything under here", "Workshop NAS", "Everywhere"), rungs)
    }

    @Test
    fun `home offers only the rungs that mean something without a folder`() {
        // The reported state: the bar opened on Home with "This folder" ticked, and there is no
        // folder. Both tree-shaped rungs are gone rather than shown and dead.
        val rungs = ReachLadder.rungsFor(null, everything)
        assertEquals(listOf("This phone", "Everywhere"), rungs.map { it.label })
    }

    @Test
    fun `every rung is strictly wider than the one above it`() {
        // The ladder's defining property, over every device shape that matters.
        val shapes = listOf(
            listOf(internal),
            listOf(internal, card),
            listOf(internal, system),
            listOf(internal, nas),
            everything,
            emptyList(),
        )
        for (drives in shapes) {
            for (cwd in listOf(someFolder, onTheNas, null)) {
                val rungs = ReachLadder.rungsFor(cwd, drives)
                // The tree rungs come first and in this order - a subtree contains its folder,
                // and both sit inside whichever drive rung follows. From there on every rung is
                // a set of drives, and those are compared as sets.
                val tree = rungs.takeWhile { it.reach.isTreeShaped }.map { it.reach }
                assertEquals(
                    "$drives / $cwd",
                    if (cwd == null) emptyList() else listOf(Reach.Folder, Reach.Subtree),
                    tree,
                )
                val drivesOnly = rungs.drop(tree.size)
                val sets = drivesOnly.map { keysOf(it.reach, drives) }
                for (i in 1 until sets.size) {
                    val wider = sets[i]
                    val inner = sets[i - 1]
                    assertTrue(
                        "$drives / $cwd: ${drivesOnly[i].label} is not wider than ${drivesOnly[i - 1].label}",
                        wider.containsAll(inner) && wider.size > inner.size,
                    )
                }
            }
        }
    }

    @Test
    fun `every drive a rung names is a drive that is actually there`() {
        // Pointability, as a property. A rung naming a volume that is not mounted is the old
        // "whatever the crawl reached" set with better manners.
        val available = everything.map { it.key }.toSet()
        for (rung in ReachLadder.rungsFor(someFolder, everything)) {
            val named = rung.reach.volumes() ?: continue
            assertTrue("${rung.label} names a volume that is not here", available.containsAll(named))
        }
    }

    /** The drives a drive-shaped rung covers. Everywhere is every drive there is. */
    private fun keysOf(reach: Reach, drives: List<ReachDrive>): Set<VolumeKey> =
        reach.volumes() ?: drives.map { it.key }.toSet()

    // ── resolving a reach that cannot be honoured ──

    @Test
    fun `a tree-shaped reach with no folder becomes the phone`() {
        // THE REPORTED BUG. This combination used to reach SearchRequest unchanged, match no
        // source, and return an empty list with no explanation - under a banner saying the index
        // was busy, which read as "still loading" rather than "this cannot work".
        val phone = Reach.Drives(setOf(internal.key, card.key))
        assertEquals(phone, ReachLadder.resolve(Reach.Folder, null, everything))
        assertEquals(phone, ReachLadder.resolve(Reach.Subtree, null, everything))
    }

    @Test
    fun `with no local drives to name at all it falls back to everywhere`() {
        // Nothing better to coerce to, and a reach that retrieves nothing would be read as the
        // search having failed.
        assertEquals(Reach.Everywhere, ReachLadder.resolve(Reach.Folder, null, emptyList()))
        assertEquals(Reach.Everywhere, ReachLadder.resolve(Reach.Folder, null, listOf(nas)))
    }

    @Test
    fun `a tree-shaped reach with a folder is left alone`() {
        assertEquals(Reach.Folder, ReachLadder.resolve(Reach.Folder, someFolder, everything))
        assertEquals(Reach.Subtree, ReachLadder.resolve(Reach.Subtree, someFolder, everything))
    }

    @Test
    fun `a reach naming a card that has been pulled narrows rather than going quiet`() {
        // SEARCH.md §13.3. Retrieving from a volume id that no longer resolves answers "no
        // matches", which blames the query for a card that is not in the phone any more.
        val both = Reach.Drives(setOf(internal.key, card.key))
        assertEquals(
            Reach.Drives(setOf(internal.key)),
            ReachLadder.resolve(both, someFolder, listOf(internal, nas)),
        )
        // And when every drive it named has gone, it says so by being empty rather than by
        // silently widening to something that answers.
        val gone = ReachLadder.resolve(Reach.Drives(setOf(card.key)), someFolder, listOf(internal))
        assertTrue(gone.isEmpty)
    }

    @Test
    fun `everywhere never needs resolving`() {
        for (cwd in listOf(someFolder, null)) {
            assertEquals(Reach.Everywhere, ReachLadder.resolve(Reach.Everywhere, cwd, everything))
        }
    }

    // ── which drives count as the phone ──

    @Test
    fun `the phone is the local drives, and the root mounts are not among them`() {
        // Root is reachable by being ticked in the volume sheet, never by a rung: somebody
        // searching this phone is looking for their own files, and a system path ranked beside
        // a photo is the rung's entire value spent.
        assertEquals(setOf(internal.key, card.key), ReachLadder.phoneKeys(everything))
    }

    @Test
    fun `a live walk at the widest reach is not pointed at the system volumes`() {
        // The other half of the same guarantee: the index never holds /data, and the walk is
        // not sent there either.
        assertEquals(
            listOf(internal.key.root, card.key.root, nas.key.root),
            ReachLadder.everywhereRoots(everything),
        )
    }

    @Test
    fun `the drive a pane stands on is the longest root that contains it`() {
        // Volumes nest - a granted folder can sit inside the internal volume - and matching the
        // shorter root would caption a pane with the wrong drive and search a wider one than
        // the chip claimed.
        val granted = ReachDrive(
            key = VolumeKey(VPath.of("local", "/storage/emulated/0/Shared")),
            label = "Shared",
            remote = false,
            system = false,
        )
        val deep = VPath.of("local", "/storage/emulated/0/Shared/Work")
        assertEquals(granted, ReachLadder.driveAt(deep, listOf(internal, granted)))
        assertEquals(internal, ReachLadder.driveAt(someFolder, listOf(internal, granted)))
    }

    @Test
    fun `a path on no known drive stands on none of them`() {
        assertEquals(null, ReachLadder.driveAt(VPath.of("zip", "/x/y"), everything))
        assertEquals(null, ReachLadder.driveAt(null, everything))
    }

    @Test
    fun `a volume root itself counts as being on that volume`() {
        assertEquals(internal, ReachLadder.driveAt(internal.key.root, everything))
    }

    // ── the banner ──

    @Test
    fun `the index total is named and comes first`() {
        // The "shrinking index" report. `scanned` counts THIS PASS, so it starts near zero every
        // launch; printed alone and called "files" it read as the index size going backwards.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 21_000, running = true),
        )
        assertTrue(line, line.contains("42535"))
        assertTrue(line, line.contains("21000"))
        assertTrue("the total must come first", line.indexOf("42535") < line.indexOf("21000"))
    }

    @Test
    fun `a pass that has only just started still shows the full index`() {
        // The exact moment the report was about: a restart, a pass at the very beginning.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 12, running = true),
        )
        assertTrue(line, line.contains("42535"))
    }

    @Test
    fun `the total falls back to what was known at the start`() {
        // `files` is recomputed at the end of a pass, so mid-pass it can lag. Taking the larger of
        // the two keeps the number from dipping while a crawl runs, which is the whole point.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 0, knownAtStart = 42_535, scanned = 900, running = true),
        )
        assertTrue(line, line.contains("42535"))
    }

    @Test
    fun `a steered pass names the query it is working on`() {
        val line = SearchReach.indexingLine(
            IndexStatus(files = 100, knownAtStart = 100, scanned = 4, running = true),
            steeredTo = "minecraft",
        )
        assertTrue(line, line.contains("minecraft"))
    }

    @Test
    fun `the line stays inside the prose budget`() {
        // check-prose caps a control surface at 96 characters, and this one is built at runtime
        // so the checker cannot see it. Six-figure counts are the worst case.
        val line = SearchReach.indexingLine(
            IndexStatus(files = 999_999, knownAtStart = 999_999, scanned = 999_999, running = true),
        )
        assertTrue("${line.length}: $line", line.length <= 96)
    }

    @Test
    fun `printing only the scan is what produced the report`() {
        // The negative control, written as the code that was there.
        val old = { st: IndexStatus -> "Still indexing — ${st.scanned} files" }
        val before = IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 31_000)
        val after = IndexStatus(files = 42_535, knownAtStart = 42_535, scanned = 21_000)
        assertTrue(old(before).contains("31000"))
        assertTrue(old(after).contains("21000"))
        // Both of the new lines name the same unchanged total, so nothing appears to shrink.
        assertTrue(SearchReach.indexingLine(before).contains("42535"))
        assertTrue(SearchReach.indexingLine(after).contains("42535"))
    }

    // -- which body a pane draws --

    @Test
    fun `home draws results while a search is active`() {
        // THE LAST PIECE. The plan and the request were both fixed and Home still showed
        // nothing, because the body was chosen by pane KIND alone - the search ran, the hits
        // landed, and the overview was drawn over them.
        assertTrue(SearchReach.showsResults(PaneKind.HOME, searchActive = true))
    }

    @Test
    fun `home draws its overview when nothing is being searched`() {
        assertFalse(SearchReach.showsResults(PaneKind.HOME, searchActive = false))
    }

    @Test
    fun `a folder pane draws results while searching`() {
        assertTrue(SearchReach.showsResults(PaneKind.FOLDER, searchActive = true))
    }

    @Test
    fun `a filter pane keeps its own body even while searching`() {
        // Settings narrows the rows it is already drawing. Swapping its body for a result list
        // would replace the settings screen with a file listing.
        for (kind in listOf(PaneKind.SETTINGS, PaneKind.BOOKMARKS, PaneKind.RECENT, PaneKind.NEARBY)) {
            assertFalse(kind.name, SearchReach.showsResults(kind, searchActive = true))
        }
    }

    @Test
    fun `keying on the pane kind is what hid the results`() {
        // The negative control, written as the condition that was there.
        val oldRule = { kind: PaneKind, _: Boolean -> kind == PaneKind.FOLDER }
        assertFalse("the old rule drew the overview over the hits", oldRule(PaneKind.HOME, true))
        assertTrue(SearchReach.showsResults(PaneKind.HOME, searchActive = true))
    }
}
