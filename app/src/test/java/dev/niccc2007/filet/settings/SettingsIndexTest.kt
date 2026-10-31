package dev.niccc2007.filet.settings

import dev.niccc2007.filet.data.PrefKeys
import dev.niccc2007.filet.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings search finds every setting, by what it is called and by its key.
 *
 * The index is generated from the page (see `tools/make-settings-index.mjs`), so this is the
 * other half: what the generator wrote, the search actually answers - and every key a row on
 * the page names is in it.
 */
class SettingsIndexTest {

    @Test
    fun `every setting is found by its own name`() {
        for (e in SETTINGS_INDEX) {
            assertTrue("${e.title} (${e.key})", SettingsSearch.find(e.title).any { it.key == e.key })
        }
    }

    @Test
    fun `every setting is found by its key`() {
        for (e in SETTINGS_INDEX) {
            assertTrue(e.key, SettingsSearch.find(e.key).any { it.key == e.key })
        }
    }

    @Test
    fun `everyday words find the setting they mean`() {
        assertEquals(Prefs.K_THEME, SettingsSearch.find("dark").first().key)
        assertTrue(SettingsSearch.find("cache").any { it.key == PrefKeys.CACHE_LOCATION })
        assertTrue(SettingsSearch.find("beat").any { it.key == Prefs.K_PULSE_DEPTH })
    }

    @Test
    fun `a title match comes before a match deeper in the text`() {
        val hits = SettingsSearch.find("glow")
        val firstDeep = hits.indexOfFirst { !it.title.contains("glow", ignoreCase = true) }
        val lastTitle = hits.indexOfLast { it.title.contains("glow", ignoreCase = true) }
        assertTrue("title hits $lastTitle before deep hits $firstDeep", firstDeep < 0 || lastTitle < firstDeep)
    }

    @Test
    fun `a result says where it lives`() {
        val disc = SETTINGS_INDEX.first { it.key == Prefs.K_DISC_GLOW }
        assertEquals("Viewers · Playback", SettingsSearch.pathOf(disc))
    }

    @Test
    fun `nothing typed finds nothing`() = assertTrue(SettingsSearch.find("  ").isEmpty())

    @Test
    fun `every key a row on the page names is in the index`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { File(it, "dev/niccc2007/filet").isDirectory }
        val page = File(root, "dev/niccc2007/filet/settings/SettingsPage.kt").readText()
        val index = File(root, "dev/niccc2007/filet/settings/SettingsIndex.kt").readText()
        val named = Regex("""(?:^|[\s,(])key\s*=\s*([A-Za-z_][\w.]*)""").findAll(page).map { it.groupValues[1] }.toSet()
        val indexed = Regex("""key = ([A-Za-z_][\w.]*),""").findAll(index).map { it.groupValues[1] }.toSet()
        assertTrue("named on the page but not searchable: ${named - indexed}", (named - indexed).isEmpty())
    }
}
