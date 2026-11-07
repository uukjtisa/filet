package dev.niccc2007.filet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharesTest {

    @Test fun `every spelling of a volume root is now accepted`() {
        // Reversed deliberately. A whole drive is shareable, writable like any other share.
        //
        // The listing failure the old refusal was built on was never WebDAV handling drive
        // roots badly: it was entries inside `C:\` that cannot be statted even from an
        // administrator shell, so the listing died part way through. That is fixed where the
        // fault is, in `LocalProvider.toNodeOrNull`.
        //
        // Every spelling here resolves to a root, including the `..` forms, so this still pins
        // that the parser resolves before it judges.
        for (p in listOf(
            "C:\\", "C:", "c:/", "/", "\\\\server\\share", "\\\\server\\share\\", "\\\\server",
            "\\\\?\\C:\\", "\\\\?\\UNC\\server\\share", "D:\\Docs\\..", "/home/..", "/home/./..",
            "D:\\a\\b\\..\\..",
        )) assertNull(p, ShareGuard.refusal(p))
    }

    @Test fun `a root is still recognised as a root`() {
        // `isRoot` keeps its meaning even though a root is no longer refused: a caller that
        // wants to label a share "all of D:" rather than "D:\" still has to know.
        for (p in listOf("C:\\", "c:/", "/", "D:\\Docs\\..")) assertTrue(p, ShareGuard.isRoot(p))
        for (p in listOf("D:\\Docs", "/home/me")) assertFalse(p, ShareGuard.isRoot(p))
    }

    @Test fun `relative and blank paths are refused`() {
        for (p in listOf("", "   ", "Docs", "C:Docs", "./x")) assertNotNull(p, ShareGuard.refusal(p))
    }

    @Test fun `ordinary folders are accepted`() {
        for (p in listOf("D:\\Documents", "D:/Pictures/", "/home/me/Pictures", "\\\\server\\share\\folder", "C:\\a\\..\\b")) {
            assertNull(p, ShareGuard.refusal(p))
        }
    }

    @Test fun `add takes a root and still refuses a duplicate`() {
        assertTrue(Shares.add(emptyList(), "D:\\", null, true) is Shares.Added.Ok)
        val one = (Shares.add(emptyList(), "D:\\Docs", null, false) as Shares.Added.Ok).shares
        assertTrue(Shares.add(one, "d:/docs/", null, true) is Shares.Added.Refused)
    }

    @Test fun `names default to the folder and tags are unique lowercase ASCII`() {
        val a = Shares.add(emptyList(), "D:\\My Photos", null, false) as Shares.Added.Ok
        assertEquals("My Photos", a.share.name)
        assertEquals("myphotos", a.share.tag)
        val b = Shares.add(a.shares, "E:\\My Photos", null, true) as Shares.Added.Ok
        assertEquals("My Photos 2", b.share.name)
        assertEquals("myphotos2", b.share.tag)
        val c = Shares.add(b.shares, "E:\\\u0424\u043e\u0442\u043e", null, false) as Shares.Added.Ok
        assertEquals("share", c.share.tag)
    }

    @Test fun `a tag survives a rename`() {
        val s = (Shares.add(emptyList(), "D:\\Docs", null, false) as Shares.Added.Ok).share
        assertEquals(listOf(s.copy(name = "Work")), Shares.withTags(listOf(s.copy(name = "Work"))))
    }

    @Test fun `a share missing its tag gets one that does not collide`() {
        val out = Shares.withTags(listOf(Share("D:/a", "docs", false, "docs"), Share("D:/b", "docs", false)))
        assertEquals("docs", out[0].tag)
        assertEquals("docs2", out[1].tag)
    }

    // ── moving a share to another folder ──

    @Test fun `a moved share keeps its name, tag, code and write setting`() {
        val docs = Share("D:/Docs", "Docs", true, "docs", code = "Pinned9")
        val pics = Share("D:/Pics", "Pics", false, "pics")
        val r = Shares.move(listOf(docs, pics), "d:\\docs\\", "E:/Archive/Docs")
        r as Shares.Added.Ok
        assertEquals(Share("E:/Archive/Docs", "Docs", true, "docs", code = "Pinned9"), r.share)
        assertEquals(listOf(r.share, pics), r.shares)
    }

    @Test fun `a move onto a folder already shared, or onto nothing, is refused`() {
        val docs = Share("D:/Docs", "Docs", false, "docs")
        val pics = Share("D:/Pics", "Pics", false, "pics")
        val clash = Shares.move(listOf(docs, pics), "D:/Docs", "D:\\Pics")
        assertTrue((clash as Shares.Added.Refused).reason.contains("Pics"))
        assertTrue(Shares.move(listOf(docs), "D:/Gone", "E:/x") is Shares.Added.Refused)
        assertTrue(Shares.move(listOf(docs), "D:/Docs", "") is Shares.Added.Refused)
    }

    @Test fun `moving a share to its own folder changes nothing`() {
        val docs = Share("D:/Docs", "Docs", false, "docs")
        val r = Shares.move(listOf(docs), "D:/Docs", "d:\\Docs") as Shares.Added.Ok
        assertEquals(listOf(docs), r.shares)
    }
}
