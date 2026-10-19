package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.Attached
import dev.niccc2007.filet.desktop.mount.DavTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MountModelTest {
    private val phone = Peer("Phone", "192.168.100.6", 11111, false, "/a/", "Whole phone", true)
    private val tablet = Peer("Tablet", "192.168.100.14", 8321, false, "/a/")

    @Test
    fun `targetOf builds the share address from a peer and a code`() {
        assertEquals(DavTarget("192.168.100.6", 11111, false, "/a/", "tres3"), MountModel.targetOf(phone, " tres3 "))
        assertEquals("/a/", MountModel.targetOf(phone.copy(base = ""), "x")!!.base)
        assertNull(MountModel.targetOf(phone, ""))
        assertNull(MountModel.targetOf(phone, "a/b"))
    }

    @Test
    fun `mount is allowed only for a present peer with a valid code`() =
        assertNull(MountModel.mountBlocker(phone, listOf(phone, tablet), "tres3", null, letterFree = true, busy = false))

    @Test
    fun `a peer that left the network cannot be mounted even with a code typed`() =
        assertEquals("This device has left the network.",
            MountModel.mountBlocker(phone, listOf(tablet), "tres3", null, letterFree = true, busy = false))

    @Test
    fun `a peer that came back under the same key is mountable again`() =
        assertNull(MountModel.mountBlocker(phone, listOf(phone.copy(scope = "changed")), "tres3", null, true, false))

    @Test
    fun `platform problem, no letter, busy and no code each block`() {
        val peers = listOf(phone)
        assertEquals("no webclient", MountModel.mountBlocker(phone, peers, "c", "no webclient", true, false))
        assertEquals("Every drive letter from D: to Z: is in use. Disconnect one first.",
            MountModel.mountBlocker(phone, peers, "c", null, letterFree = false, busy = false))
        assertEquals("Mounting...", MountModel.mountBlocker(phone, peers, "c", null, true, busy = true))
        assertEquals("Type the access code shown on the device.", MountModel.mountBlocker(phone, peers, "", null, true, false))
    }

    @Test
    fun `label prefers the peer remembered at mount time over others on the same port`() {
        val target = DavTarget(phone.host, phone.port, false, "/a/", "abcd")
        val sibling = phone.copy(name = "Phone Music")
        val m = Attached(target, "Z:")
        assertEquals("Phone Music", MountModel.labelFor(m, listOf(phone, sibling), mapOf(sibling.key to target)))
    }

    @Test
    fun `label falls back to a peer on the host and port, then to the address`() {
        val m = Attached(DavTarget(phone.host, phone.port, false, "/a/", "abcd"), "Z:")
        assertEquals("Phone", MountModel.labelFor(m, listOf(phone), emptyMap()))
        assertEquals("192.168.100.6:11111", MountModel.labelFor(m, emptyList(), emptyMap()))
    }

    @Test
    fun `mountedAs needs both the memory and the OS`() {
        val target = DavTarget(phone.host, phone.port, false, "/a/", "abcd")
        val m = Attached(target, "Z:")
        assertEquals(m, MountModel.mountedAs(phone, listOf(m), mapOf(phone.key to target)))
        // Disconnected in Explorer: the OS no longer lists it, so the row stops saying mounted.
        assertNull(MountModel.mountedAs(phone, emptyList(), mapOf(phone.key to target)))
        assertNull(MountModel.mountedAs(tablet, listOf(m), mapOf(phone.key to target)))
    }
}
