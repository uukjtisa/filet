package dev.niccc2007.filet.docs

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary a local HTML page lives inside.
 *
 * Scripts run in the viewer, so the only thing between a hostile `.html` in Downloads and the
 * rest of storage is [HtmlOrigin.resolve] and [HtmlOrigin.navigation]. Every test here is a
 * request a page can make on its own, and each asserts where it lands.
 */
class HtmlViewTest {

    private val folder = VPath("local", "/storage/emulated/0/Download/site")
    private val doc = folder.child("index.html")
    private val base = HtmlOrigin.entryUrl(doc).substringBeforeLast('/') + "/"

    @Test
    fun `the page is opened at its own name under its folder's origin`() {
        assertEquals(doc, HtmlOrigin.resolve(folder, HtmlOrigin.entryUrl(doc)))
        assertTrue(base.startsWith("https://d"))
        assertTrue(base.endsWith(".filet.invalid/"))
    }

    @Test
    fun `sibling and nested assets resolve inside the folder`() {
        assertEquals(folder.child("style.css"), HtmlOrigin.resolve(folder, base + "style.css"))
        assertEquals(folder.child("js").child("app.js"), HtmlOrigin.resolve(folder, base + "js/app.js"))
        // A cache-busting query and a fragment name the same file.
        assertEquals(folder.child("app.js"), HtmlOrigin.resolve(folder, base + "app.js?v=3#x"))
    }

    @Test
    fun `encoded names decode to the file they name`() {
        val odd = folder.child("my file é.css")
        assertEquals(odd, HtmlOrigin.resolve(folder, base + HtmlOrigin.encodeSegment("my file é.css")))
        assertEquals(folder.child("a+b.js"), HtmlOrigin.resolve(folder, base + "a+b.js"))
    }

    @Test
    fun `encoded traversal is refused after decoding`() {
        // The browser collapses a literal ../ before the request is made; these arrive intact.
        assertNull(HtmlOrigin.resolve(folder, base + "%2e%2e/secret.txt"))
        assertNull(HtmlOrigin.resolve(folder, base + "%2E%2E/%2E%2E/DCIM/a.jpg"))
        assertNull(HtmlOrigin.resolve(folder, base + "..%2fsecret.txt"))
        assertNull(HtmlOrigin.resolve(folder, base + "js%2f..%2f..%2fsecret.txt"))
        assertNull(HtmlOrigin.resolve(folder, base + "a%5c..%5cb"))
        assertNull(HtmlOrigin.resolve(folder, base + "a%00.js"))
        assertNull(HtmlOrigin.resolve(folder, base + "./x"))
    }

    @Test
    fun `malformed escapes and the bare folder resolve to nothing`() {
        assertNull(HtmlOrigin.resolve(folder, base + "%zz.js"))
        assertNull(HtmlOrigin.resolve(folder, base + "a%2"))
        assertNull(HtmlOrigin.resolve(folder, base))
    }

    @Test
    fun `another folder's origin is another origin`() {
        val other = VPath("local", "/storage/emulated/0/Download")
        assertNotEquals(HtmlOrigin.host(folder), HtmlOrigin.host(other))
        val otherUrl = HtmlOrigin.entryUrl(other.child("x.html"))
        assertNull(HtmlOrigin.resolve(folder, otherUrl))
        assertFalse(HtmlOrigin.isOurs(folder, otherUrl))
        // The parent folder's files are not reachable from the child's origin under any name.
        assertNull(HtmlOrigin.resolve(folder, "https://${HtmlOrigin.host(other)}/site/index.html"))
    }

    @Test
    fun `the host is a valid DNS label under the reserved domain`() {
        val host = HtmlOrigin.host(folder)
        val label = host.substringBefore('.')
        assertTrue(label.length in 1..63)
        assertTrue(label.all { it in 'a'..'z' || it in '0'..'9' })
        assertEquals(HtmlOrigin.DOMAIN, host.substringAfter('.'))
    }

    @Test
    fun `navigation stays for this folder, is offered for the web, and is refused otherwise`() {
        assertEquals(HtmlOrigin.Nav.LOAD, HtmlOrigin.navigation(folder, base + "page2.html"))
        assertEquals(HtmlOrigin.Nav.LOAD, HtmlOrigin.navigation(folder, "about:blank"))
        assertEquals(HtmlOrigin.Nav.HAND_OFF, HtmlOrigin.navigation(folder, "https://example.com/"))
        assertEquals(HtmlOrigin.Nav.HAND_OFF, HtmlOrigin.navigation(folder, "HTTP://example.com/"))
        assertEquals(HtmlOrigin.Nav.HAND_OFF, HtmlOrigin.navigation(folder, "mailto:a@b.c"))
        assertEquals(HtmlOrigin.Nav.REFUSE, HtmlOrigin.navigation(folder, "file:///sdcard/secret.txt"))
        assertEquals(HtmlOrigin.Nav.REFUSE, HtmlOrigin.navigation(folder, "content://media/external/images/1"))
        assertEquals(HtmlOrigin.Nav.REFUSE, HtmlOrigin.navigation(folder, "intent://scan#Intent;end"))
        assertEquals(HtmlOrigin.Nav.REFUSE, HtmlOrigin.navigation(folder, "javascript:alert(1)"))
        // Another local folder's origin is not this page's to load, nor a browser's.
        val other = HtmlOrigin.entryUrl(VPath("local", "/storage/emulated/0/x.html"))
        assertEquals(HtmlOrigin.Nav.REFUSE, HtmlOrigin.navigation(folder, other))
    }

    @Test
    fun `only data, blob and about are answered without the interceptor`() {
        assertTrue(HtmlOrigin.isSelfContained("data:image/png;base64,AAAA"))
        assertTrue(HtmlOrigin.isSelfContained("blob:https://x/1"))
        assertFalse(HtmlOrigin.isSelfContained("https://example.com/a.js"))
        assertFalse(HtmlOrigin.isSelfContained("file:///sdcard/a.js"))
    }

    @Test
    fun `scripts are served with a script type, modules included`() {
        assertEquals("text/javascript", HtmlOrigin.mimeOf("app.js"))
        assertEquals("text/javascript", HtmlOrigin.mimeOf("mod.MJS"))
        assertEquals("text/css", HtmlOrigin.mimeOf("style.css"))
        assertEquals("font/woff2", HtmlOrigin.mimeOf("f.woff2"))
        assertEquals("application/wasm", HtmlOrigin.mimeOf("x.wasm"))
        assertEquals("application/octet-stream", HtmlOrigin.mimeOf("noext"))
        assertTrue(HtmlOrigin.isText("text/javascript"))
        assertTrue(HtmlOrigin.isText("image/svg+xml"))
        assertFalse(HtmlOrigin.isText("image/png"))
    }

    @Test
    fun `the policy keeps connections at home`() {
        assertTrue(HtmlOrigin.POLICY.contains("connect-src 'self' data: blob:;"))
        assertTrue(HtmlOrigin.POLICY.contains("form-action 'self'"))
        assertFalse(HtmlOrigin.POLICY.contains("*"))
        assertFalse(HtmlOrigin.POLICY.contains("http:"))
    }

    // ── the reader's viewport ──

    @Test
    fun `a page that locks zoom is unlocked, and keeps the rest of its viewport`() {
        val out = HtmlOrigin.forReader(
            "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\"></head>",
        )
        assertTrue(out, out.contains("content=\"width=device-width, initial-scale=1, user-scalable=yes\""))
        assertFalse(out.contains("maximum-scale"))
        assertFalse(out.contains("user-scalable=no"))
    }

    @Test
    fun `single quotes, odd case, semicolons and user-scalable=0 are all unlocked`() {
        val out = HtmlOrigin.forReader("<META Content='width=600;User-Scalable=0' NAME=viewport>")
        assertTrue(out, out.contains("content=\"width=600, user-scalable=yes\""))
        assertFalse(out.contains("User-Scalable=0"))
    }

    @Test
    fun `a page with no viewport is given the device width, inside head`() {
        val out = HtmlOrigin.forReader("<!DOCTYPE html><html><head><title>t</title></head><body>x</body></html>")
        assertTrue(out, out.startsWith("<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\">"))
    }

    @Test
    fun `the viewport never goes ahead of the doctype`() {
        val noHead = HtmlOrigin.forReader("<!doctype html><p>x")
        assertTrue(noHead, noHead.startsWith("<!doctype html><meta name=\"viewport\""))
        val bare = HtmlOrigin.forReader("<p>x")
        assertTrue(bare.startsWith("<meta name=\"viewport\""))
        // `<header>` is not `<head>`.
        val header = HtmlOrigin.forReader("<header>x</header>")
        assertTrue(header, header.startsWith("<meta name=\"viewport\""))
    }

    @Test
    fun `a viewport that already allows zoom only gains the explicit yes`() {
        val out = HtmlOrigin.forReader("<meta name=viewport content=\"width=device-width\">")
        assertEquals("<meta name=viewport content=\"width=device-width, user-scalable=yes\">", out)
    }

    @Test
    fun `a page's own address leads back to the page whatever its name, which is how the editor's text is found`() {
        // The editor's Render serves its unsaved text for the request that resolves to the file,
        // so a name that did not survive the trip would show the stored copy, or nothing.
        for (name in listOf("my page (2).html", "é + ü%.htm", "100% #1?.xhtml", "a;b=c.html")) {
            val page = folder.child(name)
            assertEquals(name, page, HtmlOrigin.resolve(folder, HtmlOrigin.entryUrl(page)))
        }
    }

    @Test
    fun `the editor offers a page for exactly the kinds the viewer opens, each served as a page`() {
        assertTrue(DocFormat.EXTENSIONS.containsAll(HTML_EXTENSIONS))
        for (ext in HTML_EXTENSIONS) {
            val mime = HtmlOrigin.mimeOf("x.$ext")
            assertTrue("$ext as $mime", mime == "text/html" || mime == "application/xhtml+xml")
        }
    }
}
