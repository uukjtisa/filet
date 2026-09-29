package dev.niccc2007.filet.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {

    private val here = VPath.of("local", "/storage/emulated/0/Download/report.pdf")
    private val there = VPath.of("dav", "/7/Download/report.pdf")

    private val everything = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME,
        Capability.DELETE, Capability.CREATE_DIR,
    )
    private val readOnly = setOf(Capability.READ)

    @Test
    fun `a backend that declares the capability is not stopped`() {
        for (a in FileAction.entries) {
            assertNull("$a", ActionGate.check(a, here, everything, remote = false))
        }
    }

    @Test
    fun `reading is allowed everywhere that declares READ`() {
        assertNull(ActionGate.check(FileAction.READ, here, readOnly, remote = false))
        assertNull(ActionGate.check(FileAction.LIST, here, readOnly, remote = false))
    }

    @Test
    fun `a read-only backend refuses every write`() {
        val writes = listOf(
            FileAction.WRITE, FileAction.CREATE_FILE, FileAction.CREATE_DIR,
            FileAction.RENAME, FileAction.DELETE,
        )
        for (a in writes) {
            val d = ActionGate.check(a, here, readOnly, remote = false)
            assertEquals("$a", DenyReason.NOT_SUPPORTED_HERE, d?.reason)
        }
    }

    @Test
    fun `read-only by design reads differently from read-only by policy`() {
        // An archive can only ever be read, so telling somebody it is "read-only" sends them
        // looking for the switch that turns it off. A volume that CAN be written to and is not
        // being written to is a different sentence.
        val archive = ActionGate.check(FileAction.WRITE, here, readOnly, remote = false)
        val policy = ActionGate.check(
            FileAction.WRITE, here, setOf(Capability.READ, Capability.DELETE), remote = false,
        )
        assertEquals(DenyReason.NOT_SUPPORTED_HERE, archive?.reason)
        assertEquals(DenyReason.READ_ONLY, policy?.reason)
        assertNotEquals(archive?.headline, policy?.headline)
    }

    @Test
    fun `each action asks for its own capability`() {
        assertEquals(Capability.DELETE, ActionGate.required(FileAction.DELETE))
        assertEquals(Capability.RENAME, ActionGate.required(FileAction.RENAME))
        assertEquals(Capability.CREATE_DIR, ActionGate.required(FileAction.CREATE_DIR))
        assertEquals(Capability.WRITE, ActionGate.required(FileAction.WRITE))
        assertEquals(Capability.READ, ActionGate.required(FileAction.LIST))
        // A backend can create files and refuse folders, so the two are not the same question.
        assertNotEquals(
            ActionGate.required(FileAction.CREATE_FILE),
            ActionGate.required(FileAction.CREATE_DIR),
        )
    }

    @Test
    fun `a share says where the switch is, a local volume does not`() {
        val mine = ActionGate.check(FileAction.WRITE, here, setOf(Capability.READ, Capability.DELETE), false)!!
        val theirs = ActionGate.check(FileAction.WRITE, there, setOf(Capability.READ, Capability.DELETE), true)!!
        assertEquals(DenyReason.READ_ONLY, mine.reason)
        assertEquals(DenyReason.READ_ONLY, theirs.reason)
        assertNotEquals(mine.headline, theirs.headline)
        assertTrue("a share should name the switch", theirs.detail.contains("Allow changes"))
    }

    @Test
    fun `only a permission failure becomes a refusal`() {
        // A broken connection dressed up as a permission problem sends somebody to change a
        // setting that was never the cause.
        val notDenials = listOf(
            VfsException.NotFound(there),
            VfsException.AlreadyExists(there),
            VfsException.Io(there, java.io.IOException("connection reset")),
            VfsException.Unsupported("no append"),
            IllegalStateException("HTTP 500"),
        )
        for (t in notDenials) {
            assertNull(t::class.simpleName, ActionGate.fromFailure(FileAction.WRITE, there, t, true))
        }
        val denied = ActionGate.fromFailure(
            FileAction.WRITE, there, VfsException.AccessDenied(there), remote = true,
        )
        assertEquals(DenyReason.REFUSED_BY_HOST, denied?.reason)
    }

    @Test
    fun `rejected credentials are not the same answer as a refusal`() {
        // One can be corrected by signing in again; the other cannot be corrected at all from
        // this side. Reporting them the same way sends half the readers to the wrong place.
        val unauth = ActionGate.fromFailure(
            FileAction.WRITE, there, VfsException.AccessDenied(there, unauthenticated = true), true,
        )
        assertEquals(DenyReason.NOT_SIGNED_IN, unauth?.reason)
        assertTrue(unauth!!.detail.contains("Remotes"))
    }

    @Test
    fun `every refusal has words for all three places it can be shown`() {
        for (reason in DenyReason.entries) {
            for (remote in listOf(true, false)) {
                val d = Denial(FileAction.DELETE, there, reason, remote)
                assertTrue("$reason headline", d.headline.isNotBlank())
                assertTrue("$reason detail", d.detail.isNotBlank())
                assertTrue("$reason short", d.short.isNotBlank())
                // The headline is a sentence in a dialogue, the short form goes in a list of
                // failures beside a filename. Neither can be the other.
                assertTrue("$reason headline ends a sentence", d.headline.endsWith("."))
                assertTrue("$reason short is a fragment", !d.short.endsWith("."))
            }
        }
    }

    @Test
    fun `every action names itself in a sentence`() {
        for (a in FileAction.entries) {
            assertTrue("$a", a.verb.isNotBlank())
            assertTrue("$a should read after 'You cannot'", a.verb.first().isLowerCase())
        }
    }
}
