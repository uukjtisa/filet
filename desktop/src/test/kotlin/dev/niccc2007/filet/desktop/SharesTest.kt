package dev.niccc2007.filet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharesTest {

    @Test fun `every spelling of a volume root is refused`() {
        for (p in listOf(
            "C:\\", "C:", "c:/", "/", "\\\\server\\share", "\\\\server\\share\\", "\\\\server",
            "\\\\?\\C:\\", "\\\\?\\UNC\\server\\share", "D:\\Docs\\..", "/home/..", "/home/./..",
            "D:\\a\\b\\..\\..",
        )) assertNotNull(p, ShareGuard.refusal(p))
    }

    @Test fun `relative and blank paths are refused`() {
        for (p in listOf("", "   ", "Docs", "C:Docs", "./x")) assertNotNull(p, ShareGuard.refusal(p))
    }

    @Test fun `ordinary folders are accepted`() {
        for (p in listOf("D:\\Documents", "D:/Pictures/", "/home/me/Pictures", "\\\\server\\share\\folder", "C:\\a\\..\\b")) {
            assertNull(p, ShareGuard.refusal(p))
        }
    }

    @Test fun `add refuses a root and a duplicate`() {
        assertTrue(Shares.add(emptyList(), "D:\\", null, false) is Shares.Added.Refused)
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
}
