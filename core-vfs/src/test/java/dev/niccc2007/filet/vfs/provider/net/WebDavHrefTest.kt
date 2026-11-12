package dev.niccc2007.filet.vfs.provider.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** A network drive's listing names files from hrefs; a plus there is a plus, not a space. */
class WebDavHrefTest {

    @Test fun `a bare plus stays a plus`() {
        assertEquals("/share/a+b.txt", hrefPath("/share/a+b.txt"))
    }

    @Test fun `percent sequences are decoded`() {
        assertEquals("/share/my notes #1.txt", hrefPath("/share/my%20notes%20%231.txt"))
        assertEquals("/share/a+b.txt", hrefPath("/share/a%2Bb.txt"))
    }

    @Test fun `a malformed sequence leaves the href as it came`() {
        assertEquals("/share/100%.txt", hrefPath("/share/100%.txt"))
    }
}
