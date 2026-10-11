package dev.niccc2007.filet.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {

    private fun remote(password: String = "", key: String = "") = JSONObject()
        .put("id", "abc")
        .put("label", "Desk PC")
        .put("host", "192.168.1.9")
        .put("port", 18080)
        .put("password", password)
        .put("privateKey", key)
        .put("keyPassphrase", "")

    private fun values() = mapOf(
        "ui.theme" to "dark",
        "ui.accent" to "ember",
        "sort.key" to "DATE",
        "handlers.v1" to """{"mp4":"MEDIA"}""",
        "tracked.folders" to """[{"path":"/storage/emulated/0/DCIM","deep":true}]""",
        "dav.code" to "SECRETCODE",
        "tabs.v1" to "[]",
        "recents" to "[]",
    )

    // ── what must never travel ──

    @Test
    fun `no section carries a key from the never list`() {
        for (section in SettingsTransfer.Section.entries) {
            for (key in section.keys) {
                assertFalse(
                    "${section.id} exports $key, which NEVER says must not travel",
                    key in SettingsTransfer.NEVER,
                )
            }
        }
    }

    @Test
    fun `the device code and the open tabs are not in the file even when everything is picked`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, SettingsTransfer.Section.entries.toSet(), values(), emptyList(), false,
        )
        assertFalse("the WebDAV code travelled", out.contains("SECRETCODE"))
        assertFalse("open tabs travelled", out.contains("tabs.v1"))
        assertFalse("recents travelled", out.contains("\"recents\""))
    }

    @Test
    fun `an unpicked section contributes nothing`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, setOf(SettingsTransfer.Section.LOOK), values(), emptyList(), false,
        )
        assertTrue(out.contains("ui.theme"))
        assertFalse("behaviour leaked into a look-only export", out.contains("sort.key"))
        assertFalse("openers leaked into a look-only export", out.contains("handlers.v1"))
    }

    // ── secrets ──

    @Test
    fun `remotes are redacted by default and the file says it holds no passwords`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, setOf(SettingsTransfer.Section.REMOTES), emptyMap(),
            listOf(remote(password = "hunter2", key = "PRIVATEKEYDATA")), withSecrets = false,
        )
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("PRIVATEKEYDATA"))
        assertTrue("the address still travelled", out.contains("192.168.1.9"))
        assertFalse(JSONObject(out).getBoolean("containsPasswords"))
    }

    @Test
    fun `asking for secrets carries them and the file admits it`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, setOf(SettingsTransfer.Section.REMOTES), emptyMap(),
            listOf(remote(password = "hunter2")), withSecrets = true,
        )
        assertTrue(out.contains("hunter2"))
        assertTrue(JSONObject(out).getBoolean("containsPasswords"))
    }

    @Test
    fun `a remote with no password does not make the file claim it holds one`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, setOf(SettingsTransfer.Section.REMOTES), emptyMap(),
            listOf(remote()), withSecrets = true,
        )
        assertFalse(JSONObject(out).getBoolean("containsPasswords"))
    }

    @Test
    fun `redacting empties every secret field and leaves the rest alone`() {
        val line = SettingsTransfer.redact(remote(password = "p", key = "k"))
        assertTrue(line.hadSecret)
        assertEquals("", line.json.getString("password"))
        assertEquals("", line.json.getString("privateKey"))
        assertEquals("Desk PC", line.json.getString("label"))
        assertEquals(18080, line.json.getInt("port"))
    }

    // ── portability ──

    @Test
    fun `a SAF grant is not portable and a real path is`() {
        assertTrue(SettingsTransfer.isPortable("/storage/emulated/0/DCIM"))
        assertTrue(SettingsTransfer.isPortable("local:///storage/emulated/0/DCIM"))
        assertFalse(SettingsTransfer.isPortable("content://com.android.externalstorage/tree/x"))
        assertFalse(SettingsTransfer.isPortable("saf:/content%3A%2F%2Fcom.android.x/tree"))
    }

    @Test
    fun `the unportable count is reported rather than the entries being dropped`() {
        val stored = """
            [{"path":"/storage/emulated/0/DCIM","deep":true},
             {"path":"content://com.android.externalstorage/tree/a","deep":false},
             {"path":"content://com.android.externalstorage/tree/b","deep":false}]
        """.trimIndent()
        assertEquals(2, SettingsTransfer.unportableCount(stored))
        assertEquals(0, SettingsTransfer.unportableCount(null))
        assertEquals(0, SettingsTransfer.unportableCount("not json"))
    }

    // ── reading ──

    @Test
    fun `a round trip restores exactly what was picked`() {
        val picked = setOf(SettingsTransfer.Section.LOOK, SettingsTransfer.Section.TRACKED)
        val out = SettingsTransfer.build("0.1.14", 1234L, picked, values(), emptyList(), false)
        val read = SettingsTransfer.read(out)
        assertTrue(read is SettingsTransfer.Reading.Ok)
        read as SettingsTransfer.Reading.Ok
        assertEquals("0.1.14", read.app)
        assertEquals(1234L, read.exportedAt)
        assertEquals(setOf(SettingsTransfer.Section.LOOK, SettingsTransfer.Section.TRACKED), read.counts.keys)
        assertEquals("dark", read.valuesOf(SettingsTransfer.Section.LOOK)["ui.theme"])
        assertEquals(1, read.counts[SettingsTransfer.Section.TRACKED])
    }

    @Test
    fun `every type survives the trip as the type it went in as`() {
        // The reason the values carry a letter: SharedPreferences is typed per key, and JSON
        // cannot tell an Int from a Long. A port exported as 18080 and restored with putInt
        // throws on the next getLong, somewhere else entirely.
        val mixed = mapOf(
            "ui.theme" to "dark",
            "browse.hidden" to true,
            "dav.port" to 18080,
            "update.notesFraction" to 0.42f,
        )
        val picked = setOf(
            SettingsTransfer.Section.LOOK,
            SettingsTransfer.Section.BEHAVIOUR,
            SettingsTransfer.Section.SHARING,
        )
        val out = SettingsTransfer.build("0.1.14", 0L, picked, mixed, emptyList(), false)
        val read = SettingsTransfer.read(out) as SettingsTransfer.Reading.Ok
        assertEquals("dark", read.valuesOf(SettingsTransfer.Section.LOOK)["ui.theme"])
        assertEquals(true, read.valuesOf(SettingsTransfer.Section.BEHAVIOUR)["browse.hidden"])
        assertEquals(18080, read.valuesOf(SettingsTransfer.Section.SHARING)["dav.port"])
        assertEquals(0.42f, read.valuesOf(SettingsTransfer.Section.BEHAVIOUR)["update.notesFraction"])
    }

    @Test
    fun `encode refuses a type no section exports rather than stringifying it`() {
        // A string set written back as a string is a ClassCastException on the next read.
        assertEquals(null, SettingsTransfer.encode(setOf("a", "b")))
        assertEquals(null, SettingsTransfer.decode("bogus"))
        assertEquals(null, SettingsTransfer.decode("z:1"))
        assertEquals(null, SettingsTransfer.decode(""))
        assertEquals(null, SettingsTransfer.decode("i:notanumber"))
        // A colon inside a string value is not a delimiter.
        assertEquals("a:b:c", SettingsTransfer.decode("s:a:b:c"))
    }

    @Test
    fun `remotes come back out`() {
        val out = SettingsTransfer.build(
            "0.1.14", 0L, setOf(SettingsTransfer.Section.REMOTES), emptyMap(),
            listOf(remote(), remote()), false,
        )
        val read = SettingsTransfer.read(out) as SettingsTransfer.Reading.Ok
        assertEquals(2, read.counts[SettingsTransfer.Section.REMOTES])
        assertEquals(2, read.remotes().size)
        assertEquals("Desk PC", read.remotes()[0].getString("label"))
    }

    @Test
    fun `a file from a newer Filet is refused with a reason rather than half applied`() {
        val out = JSONObject(
            SettingsTransfer.build("9.9", 0L, setOf(SettingsTransfer.Section.LOOK), values(), emptyList(), false),
        ).put("version", SettingsTransfer.FORMAT + 1).toString()
        val read = SettingsTransfer.read(out)
        assertTrue(read is SettingsTransfer.Reading.Refused)
        assertTrue((read as SettingsTransfer.Reading.Refused).why.contains("newer Filet"))
    }

    @Test
    fun `something that is not a settings file is refused`() {
        assertTrue(SettingsTransfer.read("") is SettingsTransfer.Reading.Refused)
        assertTrue(SettingsTransfer.read("{}") is SettingsTransfer.Reading.Refused)
        assertTrue(SettingsTransfer.read("<html>") is SettingsTransfer.Reading.Refused)
        assertTrue(
            SettingsTransfer.read("""{"format":"filet.settings","version":1}""")
                is SettingsTransfer.Reading.Refused,
        )
    }

    @Test
    fun `keys this build does not know are listed and not applied, never refused`() {
        // What a file from a FUTURE Filet looks like at the same format version: a section it
        // knows, carrying a key it does not. Refusing the whole import over one key is the
        // behaviour that stops people using the feature.
        val out = """
            {"format":"filet.settings","version":1,"app":"0.2.0","exportedAt":5,
             "sections":{"look":{"ui.theme":"s:dark","ui.sparkles":"s:on"},
                         "telepathy":{"x":1}}}
        """.trimIndent()
        val read = SettingsTransfer.read(out) as SettingsTransfer.Reading.Ok
        assertEquals(1, read.counts[SettingsTransfer.Section.LOOK])
        assertEquals("dark", read.valuesOf(SettingsTransfer.Section.LOOK)["ui.theme"])
        assertTrue(read.unknownKeys.contains("ui.sparkles"))
        assertTrue(read.unknownKeys.contains("telepathy"))
    }

    @Test
    fun `an older file missing keys needs nothing - an absent preference is its default`() {
        val out = """
            {"format":"filet.settings","version":1,"app":"0.1.0","exportedAt":5,
             "sections":{"look":{"ui.theme":"s:light"}}}
        """.trimIndent()
        val read = SettingsTransfer.read(out) as SettingsTransfer.Reading.Ok
        val got = read.valuesOf(SettingsTransfer.Section.LOOK)
        assertEquals(1, got.size)
        assertEquals("light", got["ui.theme"])
    }

    // ── the sections themselves ──

    @Test
    fun `every section has a unique id and remotes is the only one with no keys`() {
        val ids = SettingsTransfer.Section.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val keyless = SettingsTransfer.Section.entries.filter { it.keys.isEmpty() }
        assertEquals(listOf(SettingsTransfer.Section.REMOTES), keyless)
    }

    @Test
    fun `no key is claimed by two sections`() {
        val seen = HashSet<String>()
        for (s in SettingsTransfer.Section.entries) {
            for (k in s.keys) {
                assertTrue("$k is in two sections", seen.add(k))
            }
        }
    }

    @Test
    fun `sharing is the one section that is off by default`() {
        val off = SettingsTransfer.Section.entries.filterNot { it.onByDefault }
        assertEquals(listOf(SettingsTransfer.Section.SHARING), off)
    }
}
