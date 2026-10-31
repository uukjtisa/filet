package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The column on the right: one thing, or nothing, per side.
 *
 * Three things here can be quietly wrong, and each one looks like the feature working until a
 * particular configuration meets it.
 *
 * **The stored value.** "Never configured" and "configured to show nothing" are different
 * answers, the same as for the line under the name. Collapse them and the column cannot be
 * turned off - it comes back as the default the next time the app starts.
 *
 * **The options.** A side offered a part that cannot answer for it is a chip that leaves the
 * column empty on every row it applies to, which reads as the setting being broken.
 *
 * **The migration.** Somebody who had turned the old column off must not be handed one back
 * because two switches became two slots.
 */
class SideColumnTest {

    private val metricsWide = PaneMetrics.forWidth(400, dev.niccc2007.filet.data.ViewStep.LIST)
    private val metricsMid = PaneMetrics.forWidth(250, dev.niccc2007.filet.data.ViewStep.LIST)
    private val metricsNarrow = PaneMetrics.forWidth(150, dev.niccc2007.filet.data.ViewStep.LIST)

    // ── what gets stored ──────────────────────────────────────────────────────────────────

    @Test fun `never configured gets the default and configured-to-nothing gets nothing`() {
        assertEquals(DetailPart.SIZE, SideColumn.normalise(null, SideColumn.FILE_DEFAULT, isDir = false))
        assertNull(SideColumn.normalise("", SideColumn.FILE_DEFAULT, isDir = false))
        assertNull(SideColumn.normalise("  ", SideColumn.FOLDER_DEFAULT, isDir = true))
    }

    @Test fun `what is chosen survives a round trip, nothing included`() {
        for (isDir in listOf(false, true)) {
            for (part in SideColumn.optionsFor(isDir)) {
                val back = SideColumn.normalise(SideColumn.encode(part), DetailPart.DATE, isDir)
                assertEquals("$part as isDir=$isDir", part, back)
            }
        }
    }

    @Test fun `an id this build does not know falls back rather than blanking`() {
        // A later version's part. The default is a better answer than a column nobody chose.
        assertEquals(DetailPart.COUNT, SideColumn.normalise("permissions", DetailPart.COUNT, isDir = true))
        assertEquals(DetailPart.DATE, SideColumn.normalise(" date ", DetailPart.COUNT, isDir = true))
    }

    @Test fun `a part this side cannot answer falls back`() {
        // Item count stored for the FILE side. Kept, it would be an empty cell on every file and
        // a chooser with no option lit, because the chooser does not offer it.
        assertEquals(DetailPart.SIZE, SideColumn.normalise("count", DetailPart.SIZE, isDir = false))
        assertEquals(DetailPart.COUNT, SideColumn.normalise("kind", DetailPart.COUNT, isDir = true))
    }

    // ── what the chooser offers ───────────────────────────────────────────────────────────

    @Test fun `each side is offered nothing first, then only what it can answer`() {
        assertEquals(
            listOf(null, DetailPart.SIZE, DetailPart.DATE, DetailPart.KIND),
            SideColumn.optionsFor(isDir = false),
        )
        assertEquals(
            listOf(null, DetailPart.TOTAL, DetailPart.COUNT, DetailPart.DATE),
            SideColumn.optionsFor(isDir = true),
        )
    }

    @Test fun `every offered part prints something for an entry of that kind`() {
        // The dead-switch rule as a test: no chip in Settings leads to an empty column on every
        // row it governs. A folder total with nothing measured prints the dash, which counts -
        // it is a place held for a figure, not a column left blank.
        val d = EntryDisplay(relative = false)
        for (isDir in listOf(false, true)) {
            for (part in SideColumn.optionsFor(isDir).filterNotNull()) {
                val text = RowDetails.one(part, isDir, 2048L, 1_600_000_000_000L, 3, "Report.pdf", d, FolderTotal.Unknown)
                assertTrue("$part prints nothing with isDir=$isDir", text.isNotEmpty())
            }
        }
    }

    @Test fun `the defaults are what the old size column showed`() {
        // Bytes beside a file, a count beside a folder - what measure=true did, so somebody who
        // never touched the setting sees no change at all.
        assertEquals(DetailPart.SIZE, SideColumn.FILE_DEFAULT)
        assertEquals(DetailPart.COUNT, SideColumn.FOLDER_DEFAULT)
        assertTrue(SideColumn.FILE_DEFAULT!!.appliesTo(isDir = false))
        assertTrue(SideColumn.FOLDER_DEFAULT!!.appliesTo(isDir = true))
    }

    // ── the migration off the two switches ────────────────────────────────────────────────

    @Test fun `the old column off stays off`() {
        // THE case. A reset to defaults is indistinguishable from the feature being broken for
        // the one person who had deliberately configured it.
        assertNull(SideColumn.migrate(hadMeasure = false, hadDateColumn = false, fallback = DetailPart.SIZE))
        assertNull(SideColumn.migrate(hadMeasure = false, hadDateColumn = false, fallback = DetailPart.COUNT))
    }

    @Test fun `the old size column becomes each side's default`() {
        assertEquals(DetailPart.SIZE, SideColumn.migrate(true, false, SideColumn.FILE_DEFAULT))
        assertEquals(DetailPart.COUNT, SideColumn.migrate(true, false, SideColumn.FOLDER_DEFAULT))
    }

    @Test fun `a date column alone becomes the date`() {
        assertEquals(DetailPart.DATE, SideColumn.migrate(false, true, SideColumn.FILE_DEFAULT))
        assertEquals(DetailPart.DATE, SideColumn.migrate(false, true, SideColumn.FOLDER_DEFAULT))
    }

    @Test fun `both columns on keeps the measure and drops the date`() {
        // Two columns on the right is no longer expressible. The date goes because the line
        // under the name already carries it, which was the duplication.
        assertEquals(DetailPart.SIZE, SideColumn.migrate(true, true, SideColumn.FILE_DEFAULT))
    }

    // ── width ─────────────────────────────────────────────────────────────────────────────

    @Test fun `nothing takes no width and everything else takes some`() {
        assertEquals(0, SideColumn.widthDp(null, isDir = false))
        for (part in DetailPart.entries) {
            assertTrue("$part has no width", SideColumn.widthDp(part, isDir = true) > 0)
            assertTrue("$part has no width", SideColumn.widthDp(part, isDir = false) > 0)
        }
    }

    @Test fun `a date needs the wider pane and the rest ride the size gate`() {
        // Width is a constraint and the setting is a choice; both have to agree.
        assertTrue(SideColumn.fits(DetailPart.DATE, metricsWide))
        assertFalse(SideColumn.fits(DetailPart.DATE, metricsMid))
        assertTrue(SideColumn.fits(DetailPart.SIZE, metricsMid))
        assertFalse(SideColumn.fits(DetailPart.SIZE, metricsNarrow))
        assertFalse(SideColumn.fits(null, metricsWide))
    }

    @Test fun `a row reads the slot for its own kind`() {
        val d = EntryDisplay(sideFile = DetailPart.KIND, sideFolder = DetailPart.TOTAL)
        assertEquals(DetailPart.KIND, SideColumn.slotFor(d, isDir = false))
        assertEquals(DetailPart.TOTAL, SideColumn.slotFor(d, isDir = true))
    }
}
