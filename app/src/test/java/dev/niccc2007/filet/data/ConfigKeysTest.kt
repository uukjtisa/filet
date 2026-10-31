package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every key in the main preference store is decided: registered with its type, or excluded with a
 * reason. Read out of the sources, because a hand-kept list of keys is the thing that went stale -
 * thirteen settings were in no export section when the registry was written. See [ConfigKeys].
 */
class ConfigKeysTest {

    private fun mainRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun sources(): List<File> = mainRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    /**
     * Keys other stores keep. Not this registry's: they are not in the "filet" preferences, so
     * neither the file nor the export can see them.
     */
    private val otherStores = setOf(
        "welcome.shown", // filet.notice
        "onboarding.done", // filet.signet
        "paired", // nearby peers
        "points", // filet.resume
        "spots", // filet.reading - where each document was left
        "history", // filet.jobs - the Activity tab's history
    )

    private val constPattern = Regex("""const val (\w+)\s*=\s*"([A-Za-z][\w.]*)"""")

    /** Constants that name a preference key, by name: K_*, KEY*, *_KEY, PREF_*, DAV_*, and the sweep stamp. */
    private fun isKeyConst(name: String) =
        name.startsWith("K_") || name.startsWith("KEY") || name.endsWith("_KEY") ||
            name.startsWith("PREF_") || name.startsWith("DAV_") || name == "MEDIA_SWEEP_AT" ||
            name == "STORE_MIGRATED" || name == "CACHE_LOCATION"

    /** Every key constant declared in a file that uses the main store. */
    private fun declared(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in sources()) {
            val text = f.readText()
            val usesMain = text.contains("Prefs") || f.name == "FiletLog.kt"
            if (!usesMain) continue
            for (m in constPattern.findAll(text)) {
                val (name, key) = m.destructured
                if (isKeyConst(name)) out[key] = f.name + ":" + name
            }
        }
        return out
    }

    @Test
    fun `the scan finds the keys it is meant to`() {
        val found = declared().keys
        for (k in listOf("ui.theme", "bookmarks", "handlers.v1", "nearby.uploads", "logs.verbose", "tabs.v1", "allmusic.sort", "cache.location")) {
            assertTrue("the scan missed $k - its pattern is stale", k in found)
        }
    }

    @Test
    fun `every key in the main store is registered or excluded with a reason`() {
        val undecided = declared().filterKeys { k ->
            ConfigKeys.of(k) == null && k !in ConfigKeys.EXCLUDED && k !in otherStores
        }
        assertTrue(
            "keys that are neither in ConfigKeys.ALL nor ConfigKeys.EXCLUDED - decide whether each travels: $undecided",
            undecided.isEmpty(),
        )
    }

    @Test
    fun `every registered key still exists somewhere`() {
        val all = sources().joinToString("\n") { it.readText() }
        val stale = ConfigKeys.ALL.map { it.key }.filterNot { all.contains("\"" + it + "\"") }
        assertTrue("registered but written nowhere: $stale", stale.isEmpty())
    }

    @Test
    fun `no key is both registered and excluded, or registered twice`() {
        val keys = ConfigKeys.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.filter { it in ConfigKeys.EXCLUDED }.toString(), keys.none { it in ConfigKeys.EXCLUDED })
    }

    /**
     * The declared type is the getter's type. A key registered as INT and read with getLong is a
     * ClassCastException on the first value applied from a file.
     */
    @Test
    fun `every registered type matches the getter that reads it`() {
        val getter = Regex("""\b(?:sp|prefs)\.get(Boolean|Bool|Int|Long|Float|String|StringSet)\(\s*([\w.]+)""")
        val wrong = ArrayList<String>()
        var checked = 0
        for (f in sources()) {
            val text = f.readText()
            val consts = constPattern.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            for (m in getter.findAll(text)) {
                val (kind, ref) = m.destructured
                val name = ref.substringAfterLast('.')
                val key = consts[name] ?: (if (name == "CACHE_LOCATION") PrefKeys.CACHE_LOCATION else null) ?: continue
                val k = ConfigKeys.of(key) ?: continue
                val ok = when (k.type) {
                    ConfigType.BOOL -> kind == "Boolean" || kind == "Bool"
                    ConfigType.INT -> kind == "Int"
                    ConfigType.LONG -> kind == "Long"
                    ConfigType.FLOAT -> kind == "Float"
                    ConfigType.STRING, ConfigType.JSON -> kind == "String"
                    ConfigType.STRING_SET -> kind == "StringSet"
                }
                checked++
                if (!ok) wrong += "${k.key} is ${k.type} and read with get$kind in ${f.name}"
            }
        }
        assertTrue("checked only $checked reads - the pattern is stale", checked >= 60)
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `every export section reads its keys from the registry`() {
        for (s in SettingsTransfer.Section.entries) {
            assertEquals(ConfigKeys.keysOf(s), s.keys)
        }
        assertTrue("bookmarks" in SettingsTransfer.Section.BOOKMARKS.keys)
        assertTrue("dav.shares" in SettingsTransfer.Section.SHARING.keys)
    }

    @Test
    fun `the file carries every setting except share codes and superseded keys`() {
        val notInFile = ConfigKeys.ALL.filterNot { it.inFile }.map { it.key }.toSet()
        assertEquals(
            setOf(
                "entry.subtitle", "entry.measure", "entry.dateColumn",
                // The one sound both players shared before the split: read from older files only.
                "sound.eqOn", "sound.bands", "sound.bass", "sound.volume", "sound.space", "sound.filter",
                "bookmarks", "dav.shares", "nearby.shares",
            ),
            notInFile,
        )
    }
}
