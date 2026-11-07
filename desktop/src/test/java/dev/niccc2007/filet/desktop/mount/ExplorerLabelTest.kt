package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerLabelTest {

    @Test
    fun `the key is the mapping's UNC path with every backslash as a hash`() {
        val unc = DavTarget("192.168.100.6", 11111, false, "/a/", "Tres3").unc
        assertEquals("##192.168.100.6@11111#DavWWWRoot#a#Tres3", ExplorerLabel.keyFor(unc))
    }

    @Test
    fun `an https mapping keeps its SSL marker, and the key has no backslash left`() {
        val key = ExplorerLabel.keyFor(DavTarget("phone.lan", 443, true, "/a/", "c0de").unc)
        assertEquals("##phone.lan@SSL@443#DavWWWRoot#a#c0de", key)
        assertEquals(-1, key.indexOf('\\'))
    }
}
