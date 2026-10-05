package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only place a network request becomes a path on this phone.
 *
 * Most of these are traversal attempts. They are written as request lines because that is what
 * arrives on the socket - not as calls to an internal helper with the escaping already undone,
 * which would test the half that was never in doubt.
 */
class DavPathTest {

    private val code = "4k9x"

    private fun ok(target: String) = DavPath.resolve(target, code) as? DavPath.Resolved.Ok

    private fun assertRefused(target: String) {
        val r = DavPath.resolve(target, code)
        assertTrue("$target should be refused, got $r", r is DavPath.Resolved.Refused)
    }

    // ── the ordinary cases ─────────────────────────────────────────────────────────────

    @Test
    fun `the mount root resolves to the share root`() {
        assertEquals("", ok("/a/4k9x")?.rel)
        assertEquals("", ok("/a/4k9x/")?.rel)
    }

    @Test
    fun `a nested path keeps its segments`() {
        assertEquals("Photos/2026/bench.jpg", ok("/a/4k9x/Photos/2026/bench.jpg")?.rel)
    }

    @Test
    fun `windows DavWWWRoot marker is stripped`() {
        // Explorer literally sends this while it works out whether the host speaks WebDAV.
        assertEquals("Photos", ok("/DavWWWRoot/a/4k9x/Photos")?.rel)
        assertEquals("", ok("/DavWWWRoot/a/4k9x/")?.rel)
    }

    @Test
    fun `a query string is not part of the path`() {
        assertEquals("a.txt", ok("/a/4k9x/a.txt?x=1")?.rel)
    }

    @Test
    fun `spaces and unicode survive one decode`() {
        assertEquals("My Files/été.txt", ok("/a/4k9x/My%20Files/%C3%A9t%C3%A9.txt")?.rel)
    }

    @Test
    fun `a plus in a filename stays a plus`() {
        // URLDecoder turns + into a space, which is right for a form and wrong for a path:
        // the file would be served under a name that does not exist and PUT back under it.
        assertEquals("a+b.txt", ok("/a/4k9x/a+b.txt")?.rel)
    }

    // ── traversal, in every encoding ───────────────────────────────────────────────────

    @Test
    fun `dot dot is refused`() {
        assertRefused("/a/4k9x/../secrets")
        assertRefused("/a/4k9x/Photos/../../secrets")
    }

    @Test
    fun `percent-encoded dot dot is refused`() {
        assertRefused("/a/4k9x/%2e%2e/secrets")
        assertRefused("/a/4k9x/%2E%2E/secrets")
    }

    @Test
    fun `double-encoded dot dot is refused because it is never decoded twice`() {
        // %252e%252e decodes ONCE to %2e%2e, which is a legal - if odd - filename. It must not
        // decode again into "..". This is the bug a second decode pass introduces.
        val r = ok("/a/4k9x/%252e%252e/x")
        assertEquals("%2e%2e/x", r?.rel)
    }

    @Test
    fun `an encoded separator cannot smuggle a segment`() {
        assertRefused("/a/4k9x/%2f%2e%2e%2fsecrets")
        assertRefused("/a/4k9x/a%5c..%5cb")
    }

    @Test
    fun `a single dot is refused`() {
        assertRefused("/a/4k9x/./x")
    }

    @Test
    fun `a null byte is refused`() {
        assertRefused("/a/4k9x/a%00.txt")
    }

    @Test
    fun `a malformed escape is refused, not guessed at`() {
        assertRefused("/a/4k9x/%zz")
        assertNull(DavPath.decodeOnce("%zz"))
    }

    @Test
    fun `a path outside the mount is refused`() {
        assertRefused("/etc/passwd")
        assertRefused("/a")
        assertRefused("/")
    }

    // ── the code ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a wrong code is forbidden, not refused`() {
        // Different answers on purpose: one means "you are not allowed", the other means "that
        // request was malformed", and collapsing them makes the log useless.
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/wrong/x", code))
    }

    @Test
    fun `a prefix of the code is not the code`() {
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/4k9/x", code))
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/4k9xx/x", code))
    }

    @Test
    fun `the comparison does not exit early`() {
        assertTrue(DavPath.constantTimeEquals("abcd", "abcd"))
        assertFalse(DavPath.constantTimeEquals("abcd", "abce"))
        assertFalse(DavPath.constantTimeEquals("abcd", "abcde"))
        assertFalse(DavPath.constantTimeEquals("", "a"))
        assertTrue(DavPath.constantTimeEquals("", ""))
    }

    // ── hrefs going back out ───────────────────────────────────────────────────────────

    @Test
    fun `a collection href ends in a slash`() {
        // Explorer decides it is looking at a folder from this before it reads resourcetype,
        // and renders a folder as a zero-byte file when it is missing.
        assertTrue(DavPath.href(code, "Photos", isDir = true).endsWith("/"))
        assertFalse(DavPath.href(code, "Photos/a.txt", isDir = false).endsWith("/"))
    }

    @Test
    fun `the root href is the mount itself`() {
        assertEquals("/a/4k9x/", DavPath.href(code, "", isDir = true))
    }

    @Test
    fun `an href re-encodes spaces as percent twenty, never as a plus`() {
        val h = DavPath.href(code, "My Files/a b.txt", isDir = false)
        assertEquals("/a/4k9x/My%20Files/a%20b.txt", h)
        assertFalse("a plus here is read as a literal plus by Explorer", h.contains("+"))
    }

    @Test
    fun `separators between segments are not encoded away`() {
        assertEquals("/a/4k9x/a/b/c.txt", DavPath.href(code, "a/b/c.txt", isDir = false))
    }

    @Test
    fun `an href round-trips back to the same path`() {
        for (rel in listOf("a b.txt", "My Files/été.txt", "a+b.txt", "d/e/f.bin")) {
            val h = DavPath.href(code, rel, isDir = false)
            assertEquals("round trip of $rel", rel, ok(h)?.rel)
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────────────

    @Test
    fun `name and parent agree with each other`() {
        assertEquals("c.txt", DavPath.nameOf("a/b/c.txt"))
        assertEquals("a/b", DavPath.parentOf("a/b/c.txt"))
        assertEquals("", DavPath.parentOf("a.txt"))
        assertNull(DavPath.parentOf(""))
    }
}
