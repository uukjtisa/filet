package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteMetaTest {
    private val now = 10_000_000_000L
    private val hour = 3_600_000L

    private fun share(
        protocol: NetProtocol = NetProtocol.SMB,
        tls: Boolean = false,
        lastUsedAt: Long = 0L,
        share: String = "media",
    ) = NetConnection(
        id = "a",
        protocol = protocol,
        label = "PC",
        host = "192.168.1.20",
        port = 445,
        user = "",
        password = "",
        share = share,
        useTls = tls,
        lastUsedAt = lastUsedAt,
    )

    private fun texts(c: NetConnection, verdict: RemoteProbe.Verdict? = null, busy: Boolean = false) =
        RemoteMeta.pieces(c, verdict, busy, now).map { it.text }

    @Test
    fun `kind then address, and nothing in between when nothing is known`() {
        assertEquals(listOf("SMB", "192.168.1.20/media"), texts(share()))
    }

    @Test
    fun `the open stamp is called opened`() {
        assertEquals(listOf("SMB", "opened 3h ago", "192.168.1.20/media"), texts(share(lastUsedAt = now - 3 * hour)))
    }

    @Test
    fun `a test outranks the stamp, and dialling outranks a test`() {
        val c = share(lastUsedAt = now - hour)
        val failed = RemoteProbe.Verdict(false, "refused", "Sign-in turned down")
        assertEquals("refused", texts(c, failed)[1])
        assertEquals(RemoteMeta.Tone.BAD, RemoteMeta.pieces(c, failed, false, now)[1].tone)
        assertEquals("reachable", texts(c, RemoteProbe.WORKING)[1])
        assertEquals("dialling", texts(c, failed, busy = true)[1])
    }

    @Test
    fun `plain FTP and WebDAV are flagged, TLS and SFTP are not`() {
        assertEquals(listOf("FTP", "unencrypted", "192.168.1.20"), texts(share(NetProtocol.FTP, share = "")))
        assertEquals("unencrypted", texts(share(NetProtocol.WEBDAV))[1])
        assertEquals(listOf("WebDAV", "192.168.1.20/media"), texts(share(NetProtocol.WEBDAV, tls = true)))
        assertEquals(listOf("SFTP", "192.168.1.20/media"), texts(share(NetProtocol.SFTP)))
    }

    @Test
    fun `time words`() {
        assertEquals("", agoWords(0L, now))
        assertEquals("", agoWords(now + hour, now))
        assertEquals("just now", agoWords(now - 60_000L, now))
        assertEquals("yesterday", agoWords(now - 30 * hour, now))
    }
}
