package dev.niccc2007.filet.remotes

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsMountTest {
    @Test
    fun `a pinned code maps a drive that survives a restart`() {
        assertEquals(
            "net use * \\\\192.168.5.22@8321\\DavWWWRoot\\a\\tres33 /persistent:yes",
            WindowsMount.command("192.168.5.22", 8321, "tres33", pinned = true),
        )
    }

    @Test
    fun `a code that changes each start is not made persistent`() {
        assertEquals(
            "net use * \\\\10.0.0.4@8080\\DavWWWRoot\\a\\k3x9",
            WindowsMount.command("10.0.0.4", 8080, "k3x9", pinned = false),
        )
    }
}
