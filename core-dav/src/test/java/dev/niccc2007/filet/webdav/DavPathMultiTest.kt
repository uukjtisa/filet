package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolving against several live codes.
 *
 * `DavPathTest` covers what one share means and none of it changed. This covers the part that is
 * new: which share answered, and that adding shares did not loosen a single refusal.
 */
class DavPathMultiTest {

    private val codes = listOf("AAA1", "BBB2", "CCC3")

    @Test
    fun `the matching code comes back with the path`() {
        val r = DavPath.resolve("/a/BBB2/photos/x.jpg", codes)
        assertTrue(r is DavPath.Resolved.Ok)
        r as DavPath.Resolved.Ok
        assertEquals("photos/x.jpg", r.rel)
        assertEquals("BBB2", r.code)
    }

    @Test
    fun `each code reaches its own share root`() {
        for (c in codes) {
            val r = DavPath.resolve("/a/$c/", codes)
            assertEquals(DavPath.Resolved.Ok("", c), r)
        }
    }

    @Test
    fun `a code that is not live is forbidden`() {
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/DDD4/x", codes))
    }

    @Test
    fun `an empty live set forbids everything`() {
        // What a socket with nothing behind it should say, and what a teardown wants.
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/AAA1/x", emptyList()))
    }

    @Test
    fun `traversal is still refused under every live code`() {
        for (c in codes) {
            assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/a/$c/../secret", codes))
            assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/a/$c/%2e%2e/secret", codes))
            assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/a/$c/a%5c..%5cb", codes))
            assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/a/$c/%2f%2e%2e%2fsecret", codes))
            // And the other half of decoding exactly once: %252e%252e becomes the literal, odd,
            // but legal filename %2e%2e. Refusing it here would mean a second decode had happened.
            assertEquals(
                DavPath.Resolved.Ok("%2e%2e/x", c),
                DavPath.resolve("/a/$c/%252e%252e/x", codes),
            )
        }
    }

    @Test
    fun `a request outside the mount is still refused`() {
        assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/AAA1/x", codes))
        assertEquals(DavPath.Resolved.Refused, DavPath.resolve("/b/AAA1/x", codes))
    }

    @Test
    fun `one code cannot borrow another's path`() {
        // The share is chosen by the code alone, so this only has to keep saying which one.
        val a = DavPath.resolve("/a/AAA1/shared/note.txt", codes) as DavPath.Resolved.Ok
        val b = DavPath.resolve("/a/CCC3/shared/note.txt", codes) as DavPath.Resolved.Ok
        assertEquals(a.rel, b.rel)
        assertEquals("AAA1", a.code)
        assertEquals("CCC3", b.code)
    }

    @Test
    fun `the single code form still answers as it did, now naming itself`() {
        assertEquals(DavPath.Resolved.Ok("x/y", "KTR9"), DavPath.resolve("/a/KTR9/x/y", "KTR9"))
        assertEquals(DavPath.Resolved.Forbidden, DavPath.resolve("/a/NOPE/x", "KTR9"))
    }

    @Test
    fun `the Windows marker is still stripped with several codes up`() {
        assertEquals(DavPath.Resolved.Ok("", "BBB2"), DavPath.resolve("/DavWWWRoot/a/BBB2/", codes))
    }
}
