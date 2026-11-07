package dev.niccc2007.filet.desktop

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    private val fixed = Config(account = Account("bcd234fgh5"), deviceId = "dev-1")

    @Test fun `config round-trips through the file format`() {
        val c = Config(
            shares = listOf(
                Share("D:\\Documents", "Documents", false, "documents"),
                Share("/home/me/Pics", "Pics", true, "pics"),
            ),
            account = Account("bcd234fgh5"),
            port = 9000,
            autostart = true,
            deviceId = "dev-1",
        )
        val back = ConfigCodec.decode(ConfigCodec.encode(c)) { fixed }
        assertEquals(c, back.config)
        assertFalse(back.repaired)
    }

    @Test fun `a hand-set code and a chosen address survive a restart`() {
        val c = fixed.copy(
            shares = listOf(Share("D:\\Docs", "Docs", false, "docs", code = "kkk777")),
            host = "192.168.100.17",
        )
        val back = ConfigCodec.decode(ConfigCodec.encode(c)) { fixed }
        assertEquals(c, back.config)
        assertFalse(back.repaired)
    }

    @Test fun `saved phones survive a restart, letter and rename included`() {
        val c = fixed.copy(phones = listOf(
            KnownPhone("dev-9/Phone", "Phone", "Work phone", "192.168.100.6", 11111, "Tres3", deviceId = "dev-9", letter = 'Y'),
            KnownPhone("192.168.100.14:8321/Tablet", "Tablet", "Tablet", "192.168.100.14", 8321, "b2"),
        ))
        val back = ConfigCodec.decode(ConfigCodec.encode(c)) { fixed }
        assertEquals(c, back.config)
        assertFalse(back.repaired)
    }

    @Test fun `a saved phone that cannot name a share is dropped, and an old file has none`() {
        val raw = "{\"password\":\"bcd234fgh5\",\"deviceId\":\"x\",\"phones\":[" +
            "{\"host\":\"10.0.0.2\",\"port\":8321,\"code\":\"has-dash\"}," +
            "{\"host\":\"\",\"port\":8321,\"code\":\"ok1\"}," +
            "{\"host\":\"10.0.0.3\",\"port\":8321,\"code\":\"ok2\",\"letter\":\"q\"}]}"
        val d = ConfigCodec.decode(raw) { fixed }
        val kept = d.config.phones.single()
        assertEquals("10.0.0.3:8321", kept.label)
        assertEquals('Q', kept.letter)
        assertTrue(d.repaired)
        assertEquals(emptyList<KnownPhone>(), ConfigCodec.decode("{\"password\":\"bcd234fgh5\",\"deviceId\":\"x\"}") { fixed }.config.phones)
    }

    @Test fun `an invalid hand-set code in the file falls back to the account's`() {
        val raw = "{\"password\":\"bcd234fgh5\",\"deviceId\":\"x\",\"shares\":[" +
            "{\"path\":\"D:\\\\Docs\",\"name\":\"Docs\",\"tag\":\"docs\",\"code\":\"has-dash\"}]}"
        val d = ConfigCodec.decode(raw) { fixed }
        assertEquals("", d.config.shares.single().code)
        assertTrue(d.repaired)
    }

    @Test fun `a missing or garbage file yields the fresh config and asks to be saved`() {
        for (raw in listOf(null, "", "{not json")) {
            val d = ConfigCodec.decode(raw) { fixed }
            assertEquals(fixed, d.config)
            assertTrue(d.repaired)
        }
    }

    @Test fun `a drive root written into the file by hand is kept`() {
        val raw = "{\"password\":\"bcd234fgh5\",\"deviceId\":\"x\",\"shares\":[" +
            "{\"path\":\"C:\\\\\",\"name\":\"C\"},{\"path\":\"D:\\\\Docs\",\"name\":\"Docs\",\"tag\":\"docs\"}]}"
        val d = ConfigCodec.decode(raw) { fixed }
        // A drive root is a legitimate share now, so it is kept rather than dropped. It still
        // counts as repaired, because the hand-written entry had no tag and decoding gives it
        // one - the endpoint has to be stable across restarts.
        assertEquals(listOf("C:\\", "D:\\Docs"), d.config.shares.map { it.path })
        assertTrue(d.repaired)
    }

    @Test fun `an untrustworthy password is replaced`() {
        val d = ConfigCodec.decode("{\"password\":\"has-dash\",\"deviceId\":\"x\"}") { fixed }
        assertEquals("bcd234fgh5", d.config.account.password)
        assertTrue(d.repaired)
    }

    @Test fun `config location per OS`() {
        val env = mapOf("LOCALAPPDATA" to "C:/Users/u/AppData/Local", "XDG_CONFIG_HOME" to "/x/cfg")
        assertEquals(
            Paths.get("C:/Users/u/AppData/Local", "Filet Desktop"),
            ConfigStore.configDir("Windows 11", env::get, "C:/Users/u"),
        )
        assertEquals(
            Paths.get("/Users/u", "Library", "Application Support", "Filet Desktop"),
            ConfigStore.configDir("Mac OS X", env::get, "/Users/u"),
        )
        assertEquals(Paths.get("/x/cfg", "filet-desktop"), ConfigStore.configDir("Linux", env::get, "/home/u"))
        assertEquals(Paths.get("/home/u/.config", "filet-desktop"), ConfigStore.configDir("Linux", { null }, "/home/u"))
    }

    @Test fun `save then load on disk preserves everything and leaves no temp file`() {
        val dir = Files.createTempDirectory("filet-cfg")
        val store = ConfigStore(dir.resolve("config.json"))
        val c = fixed.copy(shares = listOf(Share("D:\\Docs", "Docs", true, "docs")), port = 9100, autostart = true)
        store.save(c)
        assertEquals(c, store.load())
        assertFalse(Files.exists(dir.resolve("config.json.part")))
    }
}
