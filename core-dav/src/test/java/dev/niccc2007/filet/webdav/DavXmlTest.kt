package dev.niccc2007.filet.webdav

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

/**
 * The XML Explorer reads.
 *
 * The important assertions parse the output rather than matching substrings: the failure that
 * matters is "this document is not well formed", and a substring check passes cheerfully on a
 * document no parser will accept.
 */
class DavXmlTest {

    private fun parse(xml: String) {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.newDocumentBuilder().parse(InputSource(xml.reader()))
    }

    private fun file(name: String, size: Long = 10, mtime: Long = 1_758_636_300_000) =
        DavXml.Entry("/a/c/$name", isDir = false, size = size, mtime = mtime, name = name)

    private fun dir(name: String, mtime: Long = 1_758_636_300_000) =
        DavXml.Entry("/a/c/$name/", isDir = true, size = 0, mtime = mtime, name = name)

    // ── well-formedness, which is the whole ball game ──────────────────────────────────

    @Test
    fun `a plain listing parses`() {
        parse(DavXml.multiStatus(listOf(dir("Photos"), file("a.txt"))))
    }

    @Test
    fun `a file whose name is XML metacharacters does not break the listing`() {
        // One badly named file used to take the whole directory with it: Explorer answers an
        // unparseable 207 by showing an empty folder, not an error.
        val xml = DavXml.multiStatus(listOf(file("A & B<1>\"x\".txt"), file("ok.txt")))
        parse(xml)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;"))
        assertFalse("a raw ampersand survived", Regex("&(?!(amp|lt|gt|quot|apos);)").containsMatchIn(xml))
    }

    @Test
    fun `a control character in a name is dropped rather than escaped`() {
        // There is no valid XML representation of it; a numeric reference is rejected just as
        // hard as the raw byte, so emitting one would lose the directory anyway.
        val xml = DavXml.multiStatus(listOf(file("bad\u0001name.txt")))
        parse(xml)
        assertTrue(xml.contains("badname.txt"))
    }

    @Test
    fun `an empty listing is still a valid multistatus`() {
        parse(DavXml.multiStatus(emptyList()))
    }

    // ── the shape Explorer requires ────────────────────────────────────────────────────

    @Test
    fun `a collection carries no content length`() {
        // With one, Explorer renders the folder as a file of that size.
        val xml = DavXml.multiStatus(listOf(dir("Photos")))
        assertTrue(xml.contains("<D:collection/>"))
        assertFalse("a collection must not have getcontentlength", xml.contains("getcontentlength"))
    }

    @Test
    fun `a file carries a length and an empty resourcetype`() {
        val xml = DavXml.multiStatus(listOf(file("a.txt", size = 4096)))
        assertTrue(xml.contains("<D:getcontentlength>4096</D:getcontentlength>"))
        assertTrue(xml.contains("<D:resourcetype/>"))
    }

    @Test
    fun `a negative size is reported as zero, not as a negative number`() {
        // A VFS that does not know a size returns -1, and Explorer shows it as 4 exabytes.
        val xml = DavXml.multiStatus(listOf(file("a.txt", size = -1)))
        assertTrue(xml.contains("<D:getcontentlength>0</D:getcontentlength>"))
    }

    @Test
    fun `unanswerable properties come back as 404 in the same response`() {
        val xml = DavXml.multiStatus(listOf(file("a.txt")), unknown = listOf("quota-used-bytes"))
        parse(xml)
        assertTrue(xml.contains("HTTP/1.1 404 Not Found"))
        assertTrue(xml.contains("quota-used-bytes"))
    }

    @Test
    fun `every response has a lock entry so a write is even offered`() {
        val xml = DavXml.multiStatus(listOf(file("a.txt")))
        assertTrue(xml.contains("<D:supportedlock>"))
        assertTrue(xml.contains("<D:write/>"))
    }

    // ── the two date formats, which are not interchangeable ────────────────────────────

    @Test
    fun `getlastmodified is an RFC 1123 date in GMT`() {
        assertEquals("Tue, 23 Sep 2025 14:05:00 GMT", DavXml.httpDate(1_758_636_300_000))
    }

    @Test
    fun `creationdate is ISO 8601 in GMT`() {
        assertEquals("2025-09-23T14:05:00Z", DavXml.isoDate(1_758_636_300_000))
    }

    @Test
    fun `the two formats are not the same string`() {
        // Swapping them shows a blank or an epoch date in Explorer, which reads as a file
        // with no timestamp rather than as a protocol error.
        val at = 1_758_636_300_000
        assertFalse(DavXml.httpDate(at) == DavXml.isoDate(at))
    }

    @Test
    fun `the epoch does not render as an empty string`() {
        assertTrue(DavXml.httpDate(0).endsWith("GMT"))
        assertTrue(DavXml.isoDate(0).endsWith("Z"))
    }

    // ── request-body reading ───────────────────────────────────────────────────────────

    @Test
    fun `an empty propfind body means allprop`() {
        assertTrue(DavXml.isAllProp(""))
        assertTrue(DavXml.isAllProp("<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>"))
        assertFalse(
            DavXml.isAllProp(
                "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:displayname/></D:prop></D:propfind>"
            )
        )
    }

    @Test
    fun `properties this server does not have are reported, known ones are not`() {
        val body = "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:displayname/><D:getcontentlength/><Z:sillyprop/>" +
            "</D:prop></D:propfind>"
        val unknown = DavXml.unknownProps(body)
        assertTrue("sillyprop" in unknown)
        assertFalse("displayname" in unknown)
        assertFalse("getcontentlength" in unknown)
    }

    @Test
    fun `an empty body asks for nothing unknown`() {
        assertEquals(emptyList<String>(), DavXml.unknownProps(""))
    }

    @Test
    fun `an unknown property is only reported once however often it is asked for`() {
        val body = "<D:prop><Z:x/><Z:x/><Y:x/></D:prop>"
        assertEquals(listOf("x"), DavXml.unknownProps(body))
    }

    // ── content types ──────────────────────────────────────────────────────────────────

    @Test
    fun `content type comes from the extension and falls back safely`() {
        assertEquals("text/plain", DavXml.contentType("notes.txt"))
        assertEquals("image/jpeg", DavXml.contentType("a.JPG"))
        assertEquals("application/octet-stream", DavXml.contentType("noextension"))
        assertEquals("application/octet-stream", DavXml.contentType("a.unknownext"))
    }

    @Test
    fun `a lock response parses and carries its token`() {
        val xml = DavXml.lockResponse("opaquelocktoken:abc", "0", 600, "Filet")
        parse(xml)
        assertTrue(xml.contains("opaquelocktoken:abc"))
        assertTrue(xml.contains("Second-600"))
    }
}
