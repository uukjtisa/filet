package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DavShareTest {

    private fun share(
        id: Long,
        label: String = "S$id",
        code: String? = null,
        auto: Boolean = false,
        idle: Int = 30,
    ) = DavShare(id = id, label = label, code = code, autoStart = auto, idleMinutes = idle)

    // ---- auto-start needs a pinned code ---------------------------------------------------

    @Test
    fun `auto start is allowed when the code is pinned`() {
        assertTrue(DavShares.canAutoStart(share(1, code = "KTR9", auto = true)))
    }

    @Test
    fun `auto start is refused without a pinned code`() {
        // The whole point of the switch is a URL the desktop can keep. A random code mints a new
        // one every launch, so honouring the switch would break the thing it is for.
        assertFalse(DavShares.canAutoStart(share(1, code = null, auto = true)))
        assertFalse(DavShares.canAutoStart(share(1, code = "", auto = true)))
        assertFalse(DavShares.canAutoStart(share(1, code = "   ", auto = true)))
    }

    @Test
    fun `autoStarting drops the ones it cannot honour and keeps creation order`() {
        val shares = listOf(
            share(3, code = "AAA1", auto = true),
            share(1, code = null, auto = true),
            share(2, code = "BBB2", auto = true),
            share(4, code = "CCC3", auto = false),
        )
        assertEquals(listOf(2L, 3L), DavShares.autoStarting(shares).map { it.id })
    }

    @Test
    fun `nothing auto starts in the default list`() {
        // A fresh install must not open a socket on first launch.
        assertTrue(DavShares.autoStarting(listOf(DavShares.default())).isEmpty())
    }

    // ---- code collisions ------------------------------------------------------------------

    @Test
    fun `clashing codes report every member of the group, not just the later one`() {
        val shares = listOf(
            share(1, code = "SAME"),
            share(2, code = "SAME"),
            share(3, code = "OTHR"),
        )
        assertEquals(setOf(1L, 2L), DavShares.codeClashes(shares))
    }

    @Test
    fun `unpinned shares never clash with each other`() {
        // Two shares on fresh codes get different ones at start time, so nulls are not a clash.
        val shares = listOf(share(1, code = null), share(2, code = null), share(3, code = ""))
        assertTrue(DavShares.codeClashes(shares).isEmpty())
    }

    @Test
    fun `three on one code all report`() {
        val shares = (1L..3L).map { share(it, code = "DUP") }
        assertEquals(setOf(1L, 2L, 3L), DavShares.codeClashes(shares))
    }

    @Test
    fun `availability ignores the share being edited`() {
        val shares = listOf(share(1, code = "KTR9"), share(2, code = "ZZZ1"))
        assertFalse(DavShares.codeAvailable(shares, "KTR9"))
        assertTrue(DavShares.codeAvailable(shares, "KTR9", exceptId = 1L))
        assertFalse(DavShares.codeAvailable(shares, "KTR9", exceptId = 2L))
    }

    @Test
    fun `freeCode appends rather than rerolling`() {
        val shares = listOf(share(1, code = "KTR9"), share(2, code = "KTR92"))
        assertEquals("KTR9", DavShares.freeCode(emptyList(), "KTR9"))
        assertEquals("KTR93", DavShares.freeCode(shares, "KTR9"))
    }

    @Test
    fun `freeCode leaves a blank alone`() {
        assertEquals("", DavShares.freeCode(listOf(share(1, code = "A")), ""))
    }

    // ---- selection ------------------------------------------------------------------------

    @Test
    fun `select returns the share whose live code matches`() {
        val live = listOf(DavShares.LiveCode(7L, "KTR9"), DavShares.LiveCode(9L, "ZZZ1"))
        assertEquals(7L, DavShares.select("KTR9", live))
        assertEquals(9L, DavShares.select("ZZZ1", live))
    }

    @Test
    fun `select refuses an unknown or near-miss code`() {
        val live = listOf(DavShares.LiveCode(7L, "KTR9"))
        assertNull(DavShares.select("KTR8", live))
        assertNull(DavShares.select("ktr9", live))
        assertNull(DavShares.select("KTR9 ", live))
        assertNull(DavShares.select("", live))
    }

    @Test
    fun `select over nothing live matches nothing`() {
        assertNull(DavShares.select("KTR9", emptyList()))
    }

    // ---- labels and ids -------------------------------------------------------------------

    @Test
    fun `freeLabel numbers a duplicate`() {
        val shares = listOf(share(1, label = "Photos"), share(2, label = "Photos 2"))
        assertEquals("Music", DavShares.freeLabel(shares, "Music"))
        assertEquals("Photos 3", DavShares.freeLabel(shares, "Photos"))
    }

    @Test
    fun `newId is monotonic past a gap`() {
        assertEquals(1L, DavShares.newId(emptyList()))
        assertEquals(6L, DavShares.newId(listOf(share(5), share(2))))
    }

    // ---- list edits -----------------------------------------------------------------------

    @Test
    fun `upsert edits in place and keeps position`() {
        val shares = listOf(share(1), share(2), share(3))
        val out = DavShares.upsert(shares, share(2, label = "renamed"))
        assertEquals(listOf(1L, 2L, 3L), out.map { it.id })
        assertEquals("renamed", out[1].label)
    }

    @Test
    fun `upsert appends an unknown id`() {
        val out = DavShares.upsert(listOf(share(1)), share(4))
        assertEquals(listOf(1L, 4L), out.map { it.id })
    }

    @Test
    fun `remove keeps the last share`() {
        // Hosting with an empty list is a button that starts a server with nothing behind it.
        val one = listOf(share(1))
        assertEquals(one, DavShares.remove(one, 1L))
        assertEquals(listOf(2L), DavShares.remove(listOf(share(1), share(2)), 1L).map { it.id })
    }

    @Test
    fun `remove ignores an id that is not there`() {
        val shares = listOf(share(1), share(2))
        assertEquals(shares, DavShares.remove(shares, 99L))
    }

    // ---- never idles ----------------------------------------------------------------------

    @Test
    fun `a non-positive idle window means never`() {
        assertTrue(share(1, idle = 0).neverIdles)
        assertTrue(share(1, idle = -5).neverIdles)
        assertFalse(share(1, idle = 1).neverIdles)
        assertFalse(share(1, idle = 30).neverIdles)
    }

    // ---- what needs a restart ---------------------------------------------------------------

    private val base = DavShare(
        id = 1, label = "S", scope = DavScope.SHARED_FOLDER, customRoot = null,
        code = "KTR9", writable = false, views = true, idleMinutes = 30, autoStart = false,
    )

    @Test
    fun `changing the root or the code needs a restart`() {
        // These three ARE the endpoint. A desktop stores http://phone:port/a/<code> and resolves
        // paths under it; moving them under a live mount looks like every file vanishing.
        assertTrue(DavShares.needsRestart(base, base.copy(scope = DavScope.WHOLE_PHONE)))
        assertTrue(DavShares.needsRestart(base, base.copy(customRoot = "local:///storage/emulated/0/DCIM")))
        assertTrue(DavShares.needsRestart(base, base.copy(code = "ZZZ1")))
        assertTrue(DavShares.needsRestart(base, base.copy(code = null)))
    }

    @Test
    fun `policy does not need a restart`() {
        // The one that mattered: "allow changes" was saved, the running share kept its old value,
        // and the only hint was a toast about restarting. It looked saved and ignored.
        assertFalse(DavShares.needsRestart(base, base.copy(writable = true)))
        assertFalse(DavShares.needsRestart(base, base.copy(views = false)))
        assertFalse(DavShares.needsRestart(base, base.copy(idleMinutes = 0)))
        assertFalse(DavShares.needsRestart(base, base.copy(autoStart = true)))
        assertFalse(DavShares.needsRestart(base, base.copy(label = "renamed")))
    }

    @Test
    fun `no change needs no restart`() {
        assertFalse(DavShares.needsRestart(base, base))
    }

    @Test
    fun `a policy change alongside an endpoint change still needs a restart`() {
        assertTrue(DavShares.needsRestart(base, base.copy(writable = true, code = "NEW1")))
    }

    // ---- endpoint -------------------------------------------------------------------------

    @Test
    fun `endpoint is the string a desktop stores`() {
        assertEquals("http://192.168.1.4:8321/a/KTR9", DavShares.endpoint("192.168.1.4", 8321, "KTR9"))
    }

    // ---- codec ----------------------------------------------------------------------------

    @Test
    fun `a list survives a round trip`() {
        val shares = listOf(
            DavShare(1L, "Everything", DavScope.WHOLE_PHONE, null, "KTR9", false, true, 0, true),
            DavShare(2L, "One folder", DavScope.SHARED_FOLDER, "/storage/emulated/0/DCIM", null, false, false, 120, false),
        )
        val back = DavShares.decode(DavShares.encode(shares))
        assertEquals(shares, back)
    }

    @Test
    fun `writing survives a round trip`() {
        // Deliberately reversed. It used to load as false always, on the grounds that turning a
        // phone into a writable drive is a decision for the session in front of you - which held
        // while a session was something started by hand moments earlier. Shares are now stored and
        // can start themselves with the app, so forcing it off made the editor's toggle a dead
        // switch, and writing appeared not to work at all.
        val on = listOf(share(1, code = "KTR9").copy(writable = true))
        assertTrue(DavShares.decode(DavShares.encode(on)).single().writable)
    }

    @Test
    fun `a new share does not allow writing`() {
        // The safety that matters: nothing becomes writable without somebody saying so.
        assertFalse(DavShares.default().writable)
        assertFalse(DavShare(id = 9, label = "x").writable)
        assertFalse(DavShares.decode("[{\"id\":1}]").single().writable)
    }

    @Test
    fun `garbage decodes to the default rather than to nothing`() {
        for (raw in listOf(null, "", "   ", "not json", "{}", "[]", "[{\"label\":\"no id\"}]")) {
            val out = DavShares.decode(raw)
            assertEquals("raw=$raw", listOf(DavShares.default()), out)
        }
    }

    @Test
    fun `a share missing keys loads with safe values`() {
        val out = DavShares.decode("[{\"id\":4}]").single()
        assertEquals(4L, out.id)
        assertEquals(DavScope.SHARED_FOLDER, out.scope)
        assertNull(out.code)
        assertNull(out.customRoot)
        assertFalse(out.writable)
        assertFalse(out.autoStart)
        assertTrue(out.views)
        assertEquals(30, out.idleMinutes)
    }

    @Test
    fun `an unknown scope name falls back to the shared folder`() {
        val out = DavShares.decode("[{\"id\":1,\"scope\":\"WHOLE_GALAXY\"}]").single()
        assertEquals(DavScope.SHARED_FOLDER, out.scope)
    }

    @Test
    fun `a blank label becomes something drawable`() {
        assertEquals("Share", DavShares.decode("[{\"id\":1,\"label\":\"\"}]").single().label)
    }

    // ---- rootLabel ------------------------------------------------------------------------

    @Test
    fun `rootLabel names the folder for a custom root and the preset otherwise`() {
        assertEquals("DCIM", share(1).copy(customRoot = "/storage/emulated/0/DCIM").rootLabel)
        assertEquals(DavScope.WHOLE_PHONE.label, share(1).copy(scope = DavScope.WHOLE_PHONE).rootLabel)
    }
}
