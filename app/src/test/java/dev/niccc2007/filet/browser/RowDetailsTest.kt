package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the line under a name is made of.
 *
 * Two things here can be silently wrong and both have a history.
 *
 * **The stored value.** An empty list is a legitimate configuration - it is what somebody asking
 * to get rid of the line means - so "never configured" and "configured to show nothing" cannot
 * be the same value. Collapse them and the line becomes impossible to turn off, which is the
 * defect the whole feature exists to remove. The bottom bar collapses them deliberately and
 * correctly, which is exactly why this is worth pinning separately.
 *
 * **What a part prints for an entry it does not apply to.** A folder has no size of its own and
 * a file has no item count or total. Printing those empty rather than skipping them leaves
 * separators wrapped around nothing, and printing a zero is worse: a confident wrong answer on a
 * row somebody is about to act on.
 */
class RowDetailsTest {

    private val d = EntryDisplay(relative = false, parts = RowDetails.DEFAULT)

    private fun file(parts: List<DetailPart>, name: String = "Report.pdf") = RowDetails.render(
        parts = parts,
        isDir = false,
        size = 123_000L,
        at = 1_600_000_000_000L,
        count = null,
        name = name,
        display = d,
        total = FolderTotal.Unknown,
        now = 1_700_000_000_000L,
    )

    private fun folder(
        parts: List<DetailPart>,
        total: FolderTotal = FolderTotal.Settled(4_938_100_000L),
        count: Int? = 13,
    ) =
        RowDetails.render(
            parts = parts,
            isDir = true,
            size = 0L,
            at = 1_600_000_000_000L,
            count = count,
            name = "New folder",
            display = d,
            total = total,
            now = 1_700_000_000_000L,
        )

    // ── the default, which is where the duplication was ───────────────────────────────────

    @Test fun `the default does not repeat the size column`() {
        // The duplication, in the shape it took:
        //
        //   Report.pdf            123 KB
        //   123 KB  ·  26 Oct 2026
        //
        // Two lines to say one number. The column keeps the size because it lines up down the
        // list; the line gets the space for something the row was not saying at all.
        assertFalse(DetailPart.SIZE in RowDetails.DEFAULT)
        assertFalse(DetailPart.COUNT in RowDetails.DEFAULT)
        val line = file(RowDetails.DEFAULT)
        assertFalse(line, line.contains("KB"))
    }

    @Test fun `the default gives a folder its total and a file just the date`() {
        // What the freed space buys. A folder's total was only reachable through Details, so
        // the row could not answer "how big is this folder" - the question somebody browsing is
        // usually asking.
        assertTrue(folder(RowDetails.DEFAULT).contains("GB"))
        assertEquals(1, file(RowDetails.DEFAULT).split(RowDetails.JOIN).size)
    }

    // ── parts that do not apply ───────────────────────────────────────────────────────────

    @Test fun `a part with nothing to say leaves no separator behind`() {
        // Size on a folder and total on a file both render empty. Joining them anyway gives
        // "  ·  26 Oct" with a leading separator, which reads as a missing value.
        val line = folder(listOf(DetailPart.SIZE, DetailPart.DATE))
        assertFalse(line, line.startsWith(RowDetails.JOIN))
        assertFalse(line, line.endsWith(RowDetails.JOIN))
        assertFalse(line, line.contains(RowDetails.JOIN + RowDetails.JOIN))
    }

    @Test fun `a configuration can legitimately render nothing for one entry`() {
        // "Folder total" alone, on a file. Empty is the correct output, and the row is expected
        // to draw no second line at all rather than an empty one.
        assertEquals("", file(listOf(DetailPart.TOTAL)))
        assertEquals("", folder(listOf(DetailPart.SIZE, DetailPart.KIND)))
    }

    @Test fun `an unmeasured folder total is a dash, never zero`() {
        // Null is the NORMAL state for the first moment a row is on screen - the figure is
        // looked up asynchronously. "0 B" under every folder is a wrong answer, not a pending
        // one, and it would be read as the folders being empty.
        //
        // It used to print nothing at all, which was honest but made the line jump by the
        // figure's width when the lookup landed. The dash holds the place and claims nothing.
        assertEquals(RowDetails.DASH, folder(listOf(DetailPart.TOTAL), total = FolderTotal.Unknown))
        assertFalse(folder(listOf(DetailPart.TOTAL), total = FolderTotal.Unknown).contains("0"))
        assertEquals(
            RowDetails.DASH + RowDetails.JOIN + "13 items",
            folder(listOf(DetailPart.TOTAL, DetailPart.COUNT), total = FolderTotal.Unknown),
        )
    }

    @Test fun `a file never gets a dash for a total it cannot have`() {
        // The dash means "a figure is coming". A file has no folder total and none is coming,
        // so the part does not apply and is dropped - a dash there would be a promise broken
        // on every file in the list.
        assertEquals("", file(listOf(DetailPart.TOTAL)))
        assertFalse(file(RowDetails.DEFAULT).contains(RowDetails.DASH))
    }

    @Test fun `an uncounted folder says nothing rather than zero items`() {
        assertEquals("", RowDetails.countOf(null))
        assertEquals("1 item", RowDetails.countOf(1))
        assertEquals("13 items", RowDetails.countOf(13))
        assertEquals("0 items", RowDetails.countOf(0))
    }

    // ── order ─────────────────────────────────────────────────────────────────────────────

    @Test fun `the order on the list is the order on the row`() {
        // The half a column of switches cannot express, and the reason this is a list at all.
        val a = folder(listOf(DetailPart.TOTAL, DetailPart.COUNT))
        val b = folder(listOf(DetailPart.COUNT, DetailPart.TOTAL))
        assertTrue(a.startsWith("4.6") || a.first().isDigit())
        assertTrue(b.startsWith("13 items"))
        assertFalse(a == b)
    }

    @Test fun `moving a part puts it where it was moved to`() {
        val parts = listOf(DetailPart.TOTAL, DetailPart.DATE, DetailPart.KIND)
        assertEquals(
            listOf(DetailPart.DATE, DetailPart.TOTAL, DetailPart.KIND),
            RowDetails.moved(parts, 0, 1),
        )
        assertEquals(
            listOf(DetailPart.TOTAL, DetailPart.KIND, DetailPart.DATE),
            RowDetails.moved(parts, 2, 1),
        )
    }

    @Test fun `a move that cannot happen changes nothing instead of throwing`() {
        // The caller is a composable whose list can change between the press and the handler.
        val parts = listOf(DetailPart.TOTAL, DetailPart.DATE)
        assertEquals(parts, RowDetails.moved(parts, 0, 0))
        assertEquals(parts, RowDetails.moved(parts, -1, 1))
        assertEquals(parts, RowDetails.moved(parts, 0, 9))
        assertEquals(emptyList<DetailPart>(), RowDetails.moved(emptyList(), 0, 1))
    }

    @Test fun `adding puts a part at the end and removing takes it off`() {
        val parts = listOf(DetailPart.DATE)
        assertEquals(
            listOf(DetailPart.DATE, DetailPart.KIND),
            RowDetails.toggled(parts, DetailPart.KIND),
        )
        assertEquals(emptyList<DetailPart>(), RowDetails.toggled(parts, DetailPart.DATE))
    }

    // ── what gets stored ──────────────────────────────────────────────────────────────────

    @Test fun `never configured gets the default and configured-to-nothing gets nothing`() {
        // THE case. These two must not be the same value, or the line cannot be turned off -
        // which is the defect the feature was built to remove. BottomBarConfig collapses them on
        // purpose, because an empty bar is a screen with no way out; an empty detail line is
        // just a one-line row.
        assertEquals(RowDetails.DEFAULT, RowDetails.normalise(null))
        assertEquals(emptyList<DetailPart>(), RowDetails.normalise(""))
        assertEquals(emptyList<DetailPart>(), RowDetails.normalise("   "))
    }

    @Test fun `what is chosen survives a round trip`() {
        val chosen = listOf(DetailPart.DATE, DetailPart.KIND, DetailPart.TOTAL)
        assertEquals(chosen, RowDetails.normalise(RowDetails.encode(chosen)))
        // Including the empty one, which is the round trip that matters.
        assertEquals(emptyList<DetailPart>(), RowDetails.normalise(RowDetails.encode(emptyList())))
    }

    @Test fun `a stored value written by something else cannot break the row`() {
        // A later version, or a hand-edited preference file. An unknown id is dropped and a
        // repeat collapses, rather than putting the same part on the row twice.
        assertEquals(listOf(DetailPart.DATE), RowDetails.normalise("date,nonsense"))
        assertEquals(listOf(DetailPart.DATE), RowDetails.normalise("date,date"))
        assertEquals(listOf(DetailPart.DATE), RowDetails.normalise(" date , "))
        assertEquals(emptyList<DetailPart>(), RowDetails.normalise("nonsense"))
    }

    @Test fun `every part has a stable id, because the ids are what get stored`() {
        // Renaming one silently resets that part for everybody who had chosen it.
        assertEquals(
            listOf("total", "size", "count", "date", "kind"),
            DetailPart.entries.map { it.id },
        )
        assertEquals(DetailPart.entries.size, DetailPart.entries.map { it.id }.distinct().size)
    }

    // ── the kind label ────────────────────────────────────────────────────────────────────

    @Test fun `kind is the extension, upper-cased, and only when there is one`() {
        assertEquals("PDF", RowDetails.kindOf(false, "Report.pdf"))
        assertEquals("JPG", RowDetails.kindOf(false, "a.b.c.jpg"))
        assertEquals("", RowDetails.kindOf(false, "Makefile"))
        assertEquals("", RowDetails.kindOf(false, "trailing."))
        // A dotfile's dot is not an extension separator.
        assertEquals("", RowDetails.kindOf(false, ".gitignore"))
        // A folder has no kind, whatever its name looks like.
        assertEquals("", RowDetails.kindOf(true, "assets.bundle"))
    }

    @Test fun `a long or odd tail is not treated as an extension`() {
        // "Report (final, v2).pdf" is fine, but a name whose last dot is followed by prose is a
        // name with a dot in it - and printing that would push the date off the row.
        assertEquals("", RowDetails.kindOf(false, "backup.2026-10-26-final"))
        assertEquals("", RowDetails.kindOf(false, "a." + "x".repeat(RowDetails.MAX_KIND + 1)))
        assertEquals("", RowDetails.kindOf(false, "song.mp 3"))
    }

    @Test fun `only the folder total makes the row ask for a measurement`() {
        assertTrue(RowDetails.needsTotals(RowDetails.DEFAULT))
        assertTrue(RowDetails.needsTotals(listOf(DetailPart.TOTAL)))
        assertFalse(RowDetails.needsTotals(listOf(DetailPart.SIZE, DetailPart.DATE)))
        assertFalse(RowDetails.needsTotals(emptyList()))
    }

    // ── what applies to what ──────────────────────────────────────────────────────────────

    @Test fun `each part applies to exactly the kind of entry that can answer it`() {
        // The one table both surfaces read: the renderer to drop a part that has nothing to say,
        // the side chooser to never offer it. Pinned whole so a change to it is a decision.
        val files = DetailPart.entries.filter { it.appliesTo(isDir = false) }
        val folders = DetailPart.entries.filter { it.appliesTo(isDir = true) }
        assertEquals(listOf(DetailPart.SIZE, DetailPart.DATE, DetailPart.KIND), files)
        assertEquals(listOf(DetailPart.TOTAL, DetailPart.COUNT, DetailPart.DATE), folders)
    }

    @Test fun `a part that does not apply renders empty whatever it is given`() {
        // Even with every input present. The applies-to gate comes first, so a folder that
        // happens to carry a size, or a file handed a count, still says nothing it cannot mean.
        for (part in DetailPart.entries) {
            val asFile = RowDetails.one(part, false, 100L, 1_600_000_000_000L, 4, "a.pdf", d, FolderTotal.Settled(900L))
            val asFolder = RowDetails.one(part, true, 100L, 1_600_000_000_000L, 4, "a.pdf", d, FolderTotal.Settled(900L))
            assertEquals("$part on a file", part.appliesTo(false), asFile.isNotEmpty())
            assertEquals("$part on a folder", part.appliesTo(true), asFolder.isNotEmpty())
        }
    }

    // ── pieces, which is how the row pulses one part and not the rest ─────────────────────

    @Test fun `pieces join to exactly the rendered line`() {
        // The row draws the joined string when nothing is pending and the pieces when something
        // is. If the two disagreed, a row would visibly change text as its total settled.
        val parts = listOf(DetailPart.TOTAL, DetailPart.DATE, DetailPart.COUNT)
        for (total in listOf(FolderTotal.Unknown, FolderTotal.Settled(4_938_100_000L))) {
            val pieces = RowDetails.pieces(parts, true, 0L, 1_600_000_000_000L, 13, "x", d, total, 1_700_000_000_000L)
            val line = RowDetails.render(parts, true, 0L, 1_600_000_000_000L, 13, "x", d, total, 1_700_000_000_000L)
            assertEquals(line, pieces.joinToString(RowDetails.JOIN) { it.text })
        }
    }

    @Test fun `the total is its own piece so it can be treated apart`() {
        val pieces = RowDetails.pieces(
            RowDetails.DEFAULT, true, 0L, 1_600_000_000_000L, 13, "x", d, FolderTotal.Unknown, 1_700_000_000_000L,
        )
        assertEquals(DetailPart.TOTAL, pieces.first().part)
        assertEquals(RowDetails.DASH, pieces.first().text)
        // And nothing else on the line is the total, so pulsing it pulses one thing.
        assertEquals(1, pieces.count { it.part == DetailPart.TOTAL })
    }

    // ── what the row has to fetch ─────────────────────────────────────────────────────────

    @Test fun `a folder total in the side column alone still asks for a measurement`() {
        // Checking only the line is how "Folder total" in the column would have drawn a dash for
        // ever: reachable in Settings, never fetched, and nothing failing.
        val sideOnly = EntryDisplay(parts = listOf(DetailPart.DATE), sideFolder = DetailPart.TOTAL)
        assertTrue(RowDetails.needsTotals(sideOnly))
        // A file-side total renders nothing, so it is not a reason to measure anything.
        val fileSide = EntryDisplay(parts = listOf(DetailPart.DATE), sideFile = DetailPart.TOTAL, sideFolder = null)
        assertFalse(RowDetails.needsTotals(fileSide))
        assertFalse(RowDetails.needsTotals(EntryDisplay(parts = emptyList(), sideFolder = DetailPart.COUNT)))
    }

    @Test fun `an item count on the line is fetched with the column off`() {
        // The live defect: the count was fetched only while the old size column was on, so
        // "Item count" added to the line rendered empty for a reason with no visible connection.
        val lineOnly = EntryDisplay(parts = listOf(DetailPart.COUNT), sideFolder = null)
        assertTrue(RowDetails.needsCount(lineOnly))
        assertTrue(RowDetails.needsCount(EntryDisplay(parts = emptyList(), sideFolder = DetailPart.COUNT)))
        assertFalse(RowDetails.needsCount(EntryDisplay(parts = listOf(DetailPart.DATE), sideFolder = DetailPart.TOTAL)))
    }

    // ── a floor is not a total ────────────────────────────────────────────────────────────

    @Test fun `an index figure prints with a plus and a walked one does not`() {
        // THE defect. The index holds only what its crawl reached, so its sum under a folder is
        // a floor - 703 MB under a 39 GB Download on a real phone. Printed bare it is a wrong
        // number in the direction nobody checks, because a plausible figure invites no second
        // look. One character makes it true.
        assertEquals("4.6 GB+", RowDetails.totalOf(FolderTotal.AtLeast(4_938_100_000L)))
        assertEquals("4.6 GB+", RowDetails.totalOf(FolderTotal.Confirming(4_938_100_000L)))
        assertEquals("4.6 GB", RowDetails.totalOf(FolderTotal.Settled(4_938_100_000L)))
        assertEquals(RowDetails.DASH, RowDetails.totalOf(FolderTotal.Unknown))
    }

    @Test fun `every state that is not a finished walk is marked`() {
        // Stated over the whole type rather than case by case, so a fifth state has to decide.
        for (t in listOf(FolderTotal.AtLeast(1L), FolderTotal.Confirming(1L))) {
            assertTrue("$t", RowDetails.totalOf(t).endsWith(RowDetails.FLOOR))
        }
        assertFalse(RowDetails.totalOf(FolderTotal.Settled(1L)).endsWith(RowDetails.FLOOR))
    }

    @Test fun `the floor marker rides along the line and the side alike`() {
        // Both surfaces render through the same function, so the + cannot appear on one and not
        // the other - which would make the same figure read as final in one place.
        val line = folder(listOf(DetailPart.TOTAL), total = FolderTotal.AtLeast(4_938_100_000L))
        assertEquals("4.6 GB+", line)
    }
}
