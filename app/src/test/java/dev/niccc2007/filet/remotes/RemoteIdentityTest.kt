package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetConnectionJson
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.vfs.provider.net.RemoteIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * SEARCH.md §13.7, one test per row of its table - including every row that must do NOTHING,
 * because those are the ones a careless save handler would drop rows for.
 */
class RemoteIdentityTest {

    private val smb = NetConnection(
        id = "n1a2b3c4d", protocol = NetProtocol.SMB, label = "NAS", host = "192.168.1.20",
        port = 445, user = "me", password = "pw", share = "photos",
    )
    private val dav = smb.copy(protocol = NetProtocol.WEBDAV, port = 80, share = "/dav/base")
    private val sftp = smb.copy(protocol = NetProtocol.SFTP, port = 22, share = "")

    // ── the rows that change nothing ──

    @Test fun `renaming the label changes nothing`() =
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(label = "Attic NAS")))

    @Test fun `routing edits change nothing - address, alternates, last good, device id`() {
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(host = "10.0.0.5")))
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(altHosts = listOf("nas.local"))))
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(lastGood = "10.0.0.5")))
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(deviceId = "dev-42")))
    }

    @Test fun `credentials and connection settings change nothing`() {
        for (edited in listOf(
            smb.copy(password = "new"), smb.copy(privateKey = "-----KEY-----"),
            smb.copy(keyPassphrase = "x"), smb.copy(port = 4450), smb.copy(timeoutSeconds = 60),
            smb.copy(passive = false), smb.copy(readOnly = true), smb.copy(anonymous = true),
            smb.copy(user = "other"), smb.copy(domain = "WORK"), smb.copy(useTls = true),
            smb.copy(lastUsedAt = 123L),
        )) assertFalse(edited.toString(), RemoteIdentity.meaningChanged(smb, edited))
    }

    @Test fun `the start path is a landing folder, not a prefix`() =
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(startPath = "/2024")))

    @Test fun `the index opt-in never changes what a row means`() =
        assertFalse(RemoteIdentity.meaningChanged(smb, smb.copy(indexed = true)))

    // ── the rows that drop everything ──

    @Test fun `an SMB share name edit drops every row`() =
        assertTrue(RemoteIdentity.meaningChanged(smb, smb.copy(share = "music")))

    @Test fun `a WebDAV base path edit drops every row`() =
        assertTrue(RemoteIdentity.meaningChanged(dav, dav.copy(share = "/dav/other")))

    @Test fun `the share field means nothing to SFTP or FTP, so editing it there drops nothing`() {
        assertFalse(RemoteIdentity.meaningChanged(sftp, sftp.copy(share = "stray")))
        val ftp = sftp.copy(protocol = NetProtocol.FTP)
        assertFalse(RemoteIdentity.meaningChanged(ftp, ftp.copy(share = "stray")))
    }

    @Test fun `changing the protocol drops every row`() =
        assertTrue(RemoteIdentity.meaningChanged(smb, smb.copy(protocol = NetProtocol.SFTP)))

    @Test fun `switching indexing off loses the rows, switching it on loses nothing`() {
        val on = smb.copy(indexed = true)
        assertTrue(RemoteIdentity.rowsLost(on, smb))
        assertFalse(RemoteIdentity.rowsLost(smb, on))
    }

    // ── deletion and the startup sweep ──

    @Test fun `a deleted share is an orphan, a saved one is not, a local volume never is`() {
        val uuid = RemoteIdentity.volumeUuid(smb)
        assertFalse(RemoteIdentity.isOrphan(uuid, listOf(smb)))
        assertTrue(RemoteIdentity.isOrphan(uuid, emptyList()))
        assertFalse(RemoteIdentity.isOrphan("local:///storage/emulated/0", emptyList()))
    }

    @Test fun `the same server added again has a new id, so it starts empty`() {
        val again = smb.copy(id = "n1a2b3c4e")
        assertTrue(RemoteIdentity.isOrphan(RemoteIdentity.volumeUuid(smb), listOf(again)))
    }

    // ── the uuid the crawl gate matches on ──

    @Test fun `the volume uuid is the provider's own root, normalised`() {
        assertEquals(VPath.of("smb", "/n1a2b3c4d").toString(), RemoteIdentity.volumeUuid(smb))
        assertEquals(VPath.of("dav", "/n1a2b3c4d").toString(), RemoteIdentity.volumeUuid(dav))
    }

    @Test fun `only opted-in shares reach the crawl`() {
        val off = smb.copy(id = "n9")
        assertEquals(setOf(RemoteIdentity.volumeUuid(smb)), RemoteIdentity.indexedUuids(listOf(smb.copy(indexed = true), off)))
        assertEquals(emptySet<String>(), RemoteIdentity.indexedUuids(listOf(smb, off)))
    }

    // ── every field has decided ──

    @Test fun `every field of NetConnection has a rule, and no rule names a field that is gone`() {
        // The enforcement Kotlin cannot give at compile time: a new field with no rule fails
        // here instead of defaulting to "harmless".
        val declared = NetConnection::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(declared, RemoteIdentity.RULES.map { it.field }.toSet())
    }

    // ── the opt-in survives the store ──

    @Test fun `indexed round-trips through the stored form, and old entries read as off`() {
        val seal: (String) -> String = { it }
        val back = NetConnectionJson.decode(NetConnectionJson.encode(smb.copy(indexed = true), seal), seal)
        assertEquals(smb.copy(indexed = true), back)
        val old = NetConnectionJson.encode(smb, seal).apply { remove("indexed") }
        assertEquals(false, NetConnectionJson.decode(old, seal)?.indexed)
    }

    @Test fun `every field survives a round trip`() {
        val full = smb.copy(
            altHosts = listOf("nas.local"), lastGood = "10.0.0.5", deviceId = "d", domain = "W",
            useTls = true, anonymous = true, startPath = "/x", readOnly = true, passive = false,
            timeoutSeconds = 30, privateKey = "k", keyPassphrase = "p", lastUsedAt = 9L, indexed = true,
        )
        val seal: (String) -> String = { it }
        assertEquals(full, NetConnectionJson.decode(NetConnectionJson.encode(full, seal), seal))
    }
}
