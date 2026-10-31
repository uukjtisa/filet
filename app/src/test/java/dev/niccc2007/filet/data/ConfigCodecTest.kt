package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `filet.json`: what Filet writes, and what it makes of what somebody else wrote. See [ConfigCodec]. */
class ConfigCodecTest {

    private val keys = ConfigKeys.FILE

    private fun ok(text: String) = ConfigCodec.parse(text, keys) as ConfigCodec.Parsed.Ok

    private val stored = mapOf<String, Any?>(
        "ui.theme" to "DARK",
        "browse.viewStep" to 3,
        "cache.remindPast" to 1_073_741_824L,
        "play.waveGap" to 0.35f,
        "browse.hidden" to true,
        "home.blockedFolders" to setOf("Android", "DCIM/.thumbnails"),
        "drives.names" to """{"primary":"Phone","1A2B-3C4D":"Card"}""",
        "entry.dateSeparator" to "",
    )

    @Test
    fun `every type comes back as the type it was stored as`() {
        val read = ok(ConfigCodec.render(stored, keys))
        assertTrue(read.problems.toString(), read.problems.isEmpty())
        assertEquals("DARK", read.values["ui.theme"])
        assertEquals(3, read.values["browse.viewStep"])
        assertEquals(1_073_741_824L, read.values["cache.remindPast"])
        assertEquals(0.35f, read.values["play.waveGap"])
        assertEquals(true, read.values["browse.hidden"])
        assertEquals(setOf("Android", "DCIM/.thumbnails"), read.values["home.blockedFolders"])
        assertEquals("", read.values["entry.dateSeparator"])
        val names = org.json.JSONObject(read.values["drives.names"] as String)
        assertEquals("Phone", names.getString("primary"))
    }

    @Test
    fun `values are written as plain JSON, not as the export's typed strings`() {
        val text = ConfigCodec.render(stored, keys)
        assertTrue(text, text.contains("\"browse.hidden\": true"))
        assertTrue(text, text.contains("\"play.waveGap\": 0.35"))
        assertTrue(text, text.contains("\"home.blockedFolders\": [\"Android\", \"DCIM/.thumbnails\"]"))
        assertFalse(text, text.contains("\"b:"))
    }

    @Test
    fun `every setting is listed, an unchanged one as null, and null means back to the default`() {
        val text = ConfigCodec.render(emptyMap(), keys)
        for (k in keys) assertTrue("${k.key} is not in the file", text.contains(ConfigCodec.quote(k.key) + ": null"))
        val read = ok(text)
        assertTrue(read.values.isEmpty())
        assertEquals(keys.map { it.key }.toSet(), read.reset)
    }

    @Test
    fun `the same settings always give the same bytes`() {
        val a = ConfigCodec.render(stored, keys)
        val reordered = stored + ("drives.names" to """{"1A2B-3C4D":"Card","primary":"Phone"}""")
        assertEquals(a, ConfigCodec.render(reordered, keys))
    }

    @Test
    fun `a wrong value is skipped alone, named with its line, and the rest still applies`() {
        val text = ConfigCodec.render(stored, keys)
            .replace("\"browse.viewStep\": 3", "\"browse.viewStep\": \"big\"")
        val read = ok(text)
        assertEquals("DARK", read.values["ui.theme"])
        assertFalse(read.values.containsKey("browse.viewStep"))
        val p = read.problems.single()
        assertEquals("browse.viewStep", p.key)
        val line = text.lines().indexOfFirst { it.contains("\"browse.viewStep\"") } + 1
        assertEquals(line, p.line)
        assertTrue(p.describe("filet.json"), p.describe("filet.json").startsWith("filet.json line $line: browse.viewStep"))
    }

    @Test
    fun `a name the setting does not have is refused with the names it does`() {
        val read = ok("""{"ui.theme": "PURPLE"}""")
        assertTrue(read.values.isEmpty())
        assertTrue(read.problems.single().message, read.problems.single().message.contains("DARK"))
    }

    @Test
    fun `a whole number is not taken from a fraction, and a number not from text`() {
        val read = ok("""{"browse.viewStep": 2.5, "play.waveGap": "0.3", "cache.remindPast": 5}""")
        assertTrue(read.values.isEmpty() || read.values.keys == setOf("cache.remindPast"))
        assertEquals(5L, read.values["cache.remindPast"])
        assertEquals(setOf("browse.viewStep", "play.waveGap"), read.problems.map { it.key }.toSet())
    }

    @Test
    fun `a file that is not JSON is refused whole, with the line it broke on`() {
        // A missing comma. An unquoted word would not do: both parsers read it as text, and the
        // value is then refused on its own for not being true or false.
        val text = "{\n  \"ui.theme\": \"DARK\",\n  \"browse.hidden\": true\n  \"ui.accent\": \"SLATE\"\n}\n"
        val read = ConfigCodec.parse(text, keys)
        assertTrue(read is ConfigCodec.Parsed.Refused)
        read as ConfigCodec.Parsed.Refused
        assertTrue("line ${read.problem.line}", read.problem.line in 3..4)
    }

    @Test
    fun `a key this build does not know is kept and written back`() {
        val read = ok("""{"ui.theme": "DARK", "ui.sparkles": {"on": true}}""")
        assertEquals(setOf("ui.sparkles"), read.unknown.keys)
        val again = ConfigCodec.render(mapOf("ui.theme" to "DARK"), keys, read.unknown)
        val back = ok(again)
        assertEquals(setOf("ui.sparkles"), back.unknown.keys)
        assertTrue(again.trimEnd().endsWith("}"))
    }

    @Test
    fun `share lists and this phone's own state are not read from the file, and not kept`() {
        val read = ok("""{"dav.shares": "[]", "tabs.v1": "[]", "settings.hintsSeen": true}""")
        assertTrue(read.values.isEmpty())
        assertTrue(read.unknown.isEmpty())
        assertEquals(setOf("dav.shares", "tabs.v1", "settings.hintsSeen"), read.problems.map { it.key }.toSet())
    }

    @Test
    fun `the file never carries an access code`() {
        val text = ConfigCodec.render(mapOf("dav.shares" to """[{"code":"SECRET"}]"""), keys)
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("dav.shares"))
        assertFalse(text.contains("nearby.shares"))
    }

    @Test
    fun `a file from a newer Filet is refused rather than half applied`() {
        val read = ConfigCodec.parse("""{"filet.config": ${ConfigCodec.FORMAT + 1}, "ui.theme": "DARK"}""", keys)
        assertTrue(read is ConfigCodec.Parsed.Refused)
    }

    @Test
    fun `the note and the format are neither applied nor kept`() {
        val read = ok(ConfigCodec.render(emptyMap(), keys))
        assertFalse(read.unknown.containsKey(ConfigCodec.NOTE_KEY))
        assertFalse(read.unknown.containsKey(ConfigCodec.FORMAT_KEY))
        assertTrue(read.problems.isEmpty())
    }

    @Test
    fun `a line is found for the key, not for the same text inside a value`() {
        val text = "{\n  \"home.folder\": \"ui.theme\",\n  \"ui.theme\": 4\n}"
        assertEquals(3, ConfigCodec.lineOf(text, "ui.theme"))
        assertNull(ConfigCodec.lineOf(text, "ui.accent"))
    }

    @Test
    fun `a list file round trips and refuses an entry that is not an object`() {
        val stored = """[{"path":"/storage/emulated/0/DCIM","label":"Camera"}]"""
        val text = ConfigCodec.renderList(stored)
        assertTrue(text, text.contains("\n"))
        val (back, problem) = ConfigCodec.parseList(text)
        assertNull(problem)
        assertEquals("Camera", org.json.JSONArray(back).getJSONObject(0).getString("label"))
        val (none, why) = ConfigCodec.parseList("""[{"path":"/a"}, "oops"]""")
        assertNull(none)
        assertTrue(why!!.message, why.message.contains("entry 2"))
    }
}
