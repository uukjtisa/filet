package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.ReadingSpots.Leaving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading spot's rules: what leaving a file does to its spot, what the store keeps, and where
 * a spot leads in a file that may have changed. The store itself needs a device; everything it
 * decides goes through these.
 */
class ReadingSpotTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun spot(path: String, at: Long, kind: SpotKind = SpotKind.DOC, item: Int = 5) =
        ReadingSpot(path, kind, item = item, label = "x", at = at)

    @Test
    fun `leaving at the top keeps whatever was stored, even with the end on screen too`() {
        for (kind in SpotKind.entries) {
            assertEquals(kind.name, Leaving.STAY, ReadingSpots.leaving(kind, top = true, end = false))
            // A file short enough to fit on one screen was never anywhere else.
            assertEquals(kind.name, Leaving.STAY, ReadingSpots.leaving(kind, top = true, end = true))
        }
    }

    @Test
    fun `reading a document to its end forgets it, and a sheet or a text file keeps its end`() {
        assertEquals(Leaving.FORGET, ReadingSpots.leaving(SpotKind.DOC, top = false, end = true))
        assertEquals(Leaving.FORGET, ReadingSpots.leaving(SpotKind.PAGE, top = false, end = true))
        // A total at the foot of a sheet and a log's newest lines are where those get read.
        assertEquals(Leaving.SAVE, ReadingSpots.leaving(SpotKind.ROW, top = false, end = true))
        assertEquals(Leaving.SAVE, ReadingSpots.leaving(SpotKind.LINE, top = false, end = true))
        for (kind in SpotKind.entries) {
            assertEquals(kind.name, Leaving.SAVE, ReadingSpots.leaving(kind, top = false, end = false))
        }
    }

    @Test
    fun `the store keeps the newest spot per file and kind, nothing stale, and no more than its cap`() {
        val rows = listOf(
            spot("/a", now - 2 * day),
            spot("/a", now - 1 * day, item = 9),
            spot("/a", now - 3 * day, kind = SpotKind.LINE),
            spot("/old", now - 91 * day),
            spot("/edge", now - ReadingSpots.MAX_AGE_MS),
        )
        val kept = ReadingSpots.prune(rows, now)
        assertEquals(listOf("/a" to SpotKind.DOC, "/edge" to SpotKind.DOC, "/a" to SpotKind.LINE).sortedBy { it.toString() },
            kept.map { it.path to it.kind }.sortedBy { it.toString() })
        // The newer of the two for the same file is the one kept, and the order is newest first.
        assertEquals(9, kept.first { it.path == "/a" && it.kind == SpotKind.DOC }.item)
        assertEquals(kept.sortedByDescending { it.at }, kept)

        val many = (0 until ReadingSpots.KEEP + 30).map { spot("/f$it", now - it * 1000L) }
        val capped = ReadingSpots.prune(many, now)
        assertEquals(ReadingSpots.KEEP, capped.size)
        // The oldest are the ones that go.
        assertEquals("/f0", capped.first().path)
        assertEquals("/f${ReadingSpots.KEEP - 1}", capped.last().path)
    }

    @Test
    fun `an unchanged file goes back to the exact item and how far into it`() {
        val s = ReadingSpot("/r.md", SpotKind.DOC, item = 40, within = 0.35f, label = "Install", anchor = "install", stamp = 111)
        assertEquals(Goal(40, 0.35f), ReadingSpots.goal(s, stamp = 111, count = 100, anchors = mapOf("install" to 12)))
        // Not knowing when the file changed is not evidence that it did.
        assertEquals(Goal(40, 0.35f), ReadingSpots.goal(s, stamp = 0, count = 100, anchors = mapOf("install" to 12)))
    }

    @Test
    fun `a changed document goes to the same heading, and without one stays inside the file`() {
        val s = ReadingSpot("/r.md", SpotKind.DOC, item = 40, within = 0.35f, label = "Install", anchor = "install", stamp = 111)
        // The heading moved when paragraphs were added above it; the spot follows it.
        assertEquals(Goal(52, 0f), ReadingSpots.goal(s, stamp = 222, count = 100, anchors = mapOf("install" to 52)))
        // The heading is gone: the old item, still there.
        assertEquals(Goal(40, 0.35f), ReadingSpots.goal(s, stamp = 222, count = 100, anchors = emptyMap()))
        // And the file got shorter than the spot: its last item, from that item's top.
        assertEquals(Goal(29, 0f), ReadingSpots.goal(s, stamp = 222, count = 30))
        assertEquals(Goal(0, 0f), ReadingSpots.goal(s, stamp = 222, count = 0))
    }

    @Test
    fun `the stored rows read back as written, and a bad row costs only itself`() {
        val rows = listOf(
            ReadingSpot("local:///a.md", SpotKind.DOC, 7, 0.5f, 0, 0, "Usage", "usage", 123, now),
            ReadingSpot("local:///b.xlsx", SpotKind.ROW, 140, 0f, 2, 3, "Q3 · D141", "", 456, now - 5),
        )
        assertEquals(rows, ReadingSpots.decode(ReadingSpots.encode(rows)))

        val messy = """[{"p":"/ok","k":"p","i":3,"l":"page 4 of 9","w":5},
            {"p":"","k":"d","i":1},{"p":"/what","k":"zz","i":1},"not a row",
            {"p":"/neg","k":"l","i":-4,"f":7.5}]"""
        val read = ReadingSpots.decode(messy)
        assertEquals(listOf("/ok", "/neg"), read.map { it.path })
        assertEquals(SpotKind.PAGE, read[0].kind)
        // Out of range on the way in is held in range, not trusted.
        assertEquals(0, read[1].item)
        assertEquals(1f, read[1].within, 0f)

        assertTrue(ReadingSpots.decode("{ this is not json").isEmpty())
        assertTrue(ReadingSpots.decode(null).isEmpty())
    }
}
