package dev.niccc2007.filet.docs

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links and picture addresses, read against the file they are in the way GitHub reads them. */
class DocRefsTest {

    private val readme = VPath("local", "/storage/emulated/0/code/app/docs/README.md")

    @Test
    fun `a relative address is read from the document's folder`() {
        assertEquals(Ref.Local(VPath("local", "/storage/emulated/0/code/app/docs/shot.png")), DocRefs.picture("shot.png", readme))
        assertEquals(Ref.Local(VPath("local", "/storage/emulated/0/code/app/docs/img/a.png")), DocRefs.picture("./img/a.png", readme))
        assertEquals(Ref.Local(VPath("local", "/storage/emulated/0/code/app/logo.svg")), DocRefs.picture("../logo.svg", readme))
    }

    @Test
    fun `an address from the top is tried from the repository first`() {
        val r = DocRefs.picture("/docs/a.png", readme) as Ref.Local
        assertEquals("/docs/a.png", r.fromRoot)
        assertEquals(VPath("local", "/docs/a.png"), r.path)
    }

    @Test
    fun `a query, a fragment and percent escapes are cut from a local path`() {
        assertEquals(
            Ref.Local(VPath("local", "/storage/emulated/0/code/app/docs/my shot.png")),
            DocRefs.picture("my%20shot.png?raw=true#x", readme),
        )
        // A plus is a plus in a path.
        assertEquals(Ref.Local(VPath("local", "/storage/emulated/0/code/app/docs/c++.png")), DocRefs.picture("c++.png", readme))
    }

    @Test
    fun `web addresses are web, and protocol-relative ones are https`() {
        assertEquals(Ref.Web("https://x.io/b.svg"), DocRefs.picture("https://x.io/b.svg", readme))
        assertEquals(Ref.Web("https://cdn.x.io/b.png"), DocRefs.picture("//cdn.x.io/b.png", readme))
    }

    @Test
    fun `links know anchors and mail, and pictures do not follow either`() {
        assertEquals(Ref.Anchor("install"), DocRefs.link("#install", readme))
        assertEquals(Ref.Mail("me@example.com"), DocRefs.link("mailto:me@example.com?subject=hi", readme))
        assertEquals(Ref.None, DocRefs.picture("#install", readme))
        assertEquals(Ref.None, DocRefs.picture("mailto:me@example.com", readme))
    }

    @Test
    fun `an address that would run something goes nowhere`() {
        for (bad in listOf("javascript:alert(1)", "JavaScript:alert(1)", "intent://x#Intent;end", "data:text/html,<b>", "content://x/y")) {
            assertEquals(bad, Ref.None, DocRefs.link(bad, readme))
            assertEquals(bad, Ref.None, DocRefs.picture(bad, readme))
        }
    }

    @Test
    fun `a relative address with no document to read it from goes nowhere`() {
        assertEquals(Ref.None, DocRefs.link("other.md", null))
        assertEquals(Ref.Web("https://x.io"), DocRefs.link("https://x.io", null))
    }

    @Test
    fun `a traversal cannot climb above the volume`() {
        val r = DocRefs.link("../../../../../../../../etc/passwd", readme) as Ref.Local
        assertEquals(VPath("local", "/etc/passwd"), r.path)
    }

    @Test
    fun `a picture that is not shown is named by its alt text, its file name or its host`() {
        assertEquals("Build", DocRefs.nameOf(Picture(0, "https://x.io/badge.svg", alt = "Build")))
        assertEquals("badge.svg", DocRefs.nameOf(Picture(0, "https://x.io/badge.svg?style=flat")))
        // An address with no file name in it is named by where it is from.
        assertEquals("x.io", DocRefs.nameOf(Picture(0, "https://x.io/")))
    }

    @Test
    fun `a fence names its language however it is spelled`() {
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.KOTLIN, CodeLanguage.of("kt"))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.SHELL, CodeLanguage.of("bash title=\"x\""))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.PYTHON, CodeLanguage.of("{.python}"))
        assertNull(CodeLanguage.of("brainfuck"))
        assertEquals("bash", CodeLanguage.tag("bash title=\"x\""))
    }

    @Test
    fun `highlighting colours a keyword and leaves the text as it was`() {
        val code = "val x = 1"
        val out = CodeLanguage.highlight(code, dev.snipme.highlights.model.SyntaxLanguage.KOTLIN, dark = true)
        assertEquals(code, out.text)
        org.junit.Assert.assertTrue(out.spanStyles.isNotEmpty())
        assertEquals(code, CodeLanguage.highlight(code, null, dark = true).text)
    }
}
