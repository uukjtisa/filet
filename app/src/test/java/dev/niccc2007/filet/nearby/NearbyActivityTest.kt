package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the sharing light says.
 *
 * Written as "these people are connected, doing these things" rather than as internal state,
 * because that is the only form the question is ever asked in.
 */
class NearbyActivityTest {

    private fun client(addr: String, doing: ClientActivity = ClientActivity.BROWSING) =
        NearbyClient(address = addr, agent = "Firefox/142.0", since = 0, lastSeen = 0, activity = doing)

    // ── the light ──────────────────────────────────────────────────────────────────────

    @Test
    fun `not sharing is off, whatever is in the list`() {
        assertEquals(
            NearbyActivity.Light.OFF,
            NearbyActivity.lightFor(false, listOf(client("a", ClientActivity.DOWNLOADING))),
        )
    }

    @Test
    fun `sharing with nobody there is waiting, not watched`() {
        assertEquals(NearbyActivity.Light.WAITING, NearbyActivity.lightFor(true, emptyList()))
    }

    @Test
    fun `somebody connected and reading is watched`() {
        assertEquals(
            NearbyActivity.Light.WATCHED,
            NearbyActivity.lightFor(true, listOf(client("a"), client("b"))),
        )
    }

    @Test
    fun `one transfer among many idle clients still reads as busy`() {
        // The failure this guards: averaging, or looking only at the first client, reports a
        // phone that is actively serving a file as idle.
        val clients = listOf(
            client("a"), client("b"), client("c", ClientActivity.DOWNLOADING), client("d"),
        )
        assertEquals(NearbyActivity.Light.SENDING, NearbyActivity.lightFor(true, clients))
    }

    @Test
    fun `an upload outranks a download happening at the same time`() {
        val clients = listOf(
            client("a", ClientActivity.DOWNLOADING), client("b", ClientActivity.UPLOADING),
        )
        assertEquals(NearbyActivity.Light.RECEIVING, NearbyActivity.lightFor(true, clients))
    }

    @Test
    fun `order in the list does not change the answer`() {
        val a = client("a", ClientActivity.UPLOADING)
        val b = client("b", ClientActivity.DOWNLOADING)
        assertEquals(
            NearbyActivity.lightFor(true, listOf(a, b)),
            NearbyActivity.lightFor(true, listOf(b, a)),
        )
    }

    // ── the summary line ───────────────────────────────────────────────────────────────

    @Test
    fun `the summary counts files and devices, and gets one right`() {
        assertEquals(
            "1 file · nobody connected yet",
            NearbyActivity.summary(true, 1, emptyList()),
        )
        assertEquals(
            "3 files · 1 device connected",
            NearbyActivity.summary(true, 3, listOf(client("a"))),
        )
        assertEquals(
            "3 files · 2 devices connected",
            NearbyActivity.summary(true, 3, listOf(client("a"), client("b"))),
        )
    }

    @Test
    fun `a stopped server says so rather than counting`() {
        assertEquals("Nothing is being shared", NearbyActivity.summary(false, 9, emptyList()))
    }

    // ── the transfer note ──────────────────────────────────────────────────────────────

    @Test
    fun `nothing moving means no note at all`() {
        assertNull(NearbyActivity.transferNote(listOf(client("a"), client("b"))))
        assertNull(NearbyActivity.transferNote(emptyList()))
    }

    @Test
    fun `the note counts each direction and pluralises each independently`() {
        assertEquals(
            "1 download in progress",
            NearbyActivity.transferNote(listOf(client("a", ClientActivity.DOWNLOADING))),
        )
        assertEquals(
            "2 downloads in progress",
            NearbyActivity.transferNote(
                listOf(client("a", ClientActivity.DOWNLOADING), client("b", ClientActivity.DOWNLOADING))
            ),
        )
        assertEquals(
            "1 upload in progress",
            NearbyActivity.transferNote(listOf(client("a", ClientActivity.UPLOADING))),
        )
    }

    @Test
    fun `both directions at once are both named`() {
        val clients = listOf(
            client("a", ClientActivity.UPLOADING),
            client("b", ClientActivity.DOWNLOADING),
            client("c", ClientActivity.DOWNLOADING),
            client("d"),
        )
        assertEquals("1 upload and 2 downloads in progress", NearbyActivity.transferNote(clients))
    }
}
