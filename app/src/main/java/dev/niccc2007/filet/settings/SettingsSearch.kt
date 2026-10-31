package dev.niccc2007.filet.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.ListNameText
import dev.niccc2007.filet.ui.NoMatch
import dev.niccc2007.filet.ui.highlighted
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.data.PrefKeys
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.TextMatch

/**
 * One setting as the search knows it.
 *
 * Built by `tools/make-settings-index.mjs` from the rows in [SettingsPage] themselves - see
 * [SETTINGS_INDEX] - so a row added to the page is in the search on the next regeneration, and
 * `check-settingsindex` fails until it is. Nothing here is typed out a second time by hand
 * except [WORDS].
 *
 * @param text every word the row's declaration carries: its title, its hints, its options.
 * @param section the heading it sits under, shown beside the title in a result.
 */
internal data class SettingsEntry(
    val key: String,
    val title: String,
    val tab: SettingsGroup,
    val section: String,
    val text: String,
)

/**
 * Everyday words somebody might type for a setting, by its key - the one hand-written part.
 *
 * Only words the row does not already say. "Dark" is not on the Theme row, but it is what a
 * person looking for dark mode types.
 */
internal val WORDS: Map<String, String> = mapOf(
    Prefs.K_THEME to "dark light night mode colour color",
    Prefs.K_ACCENT to "colour color accent palette tint",
    Prefs.K_TAB_SIZE to "tabs bigger smaller touch",
    Prefs.K_NAME_LINES to "wrap long names truncate cut",
    Prefs.K_E_REL to "today yesterday ago",
    Prefs.K_RESTORE_TABS to "startup launch session remember",
    Prefs.K_FOLDERS_FIRST to "sort directories order",
    Prefs.K_STOP_CONFIRM to "quit exit close warning",
    Prefs.K_REACH_MODE to "thumb reach bottom one hand",
    Prefs.K_PANE_SWIPE to "gesture swipe tabs sideways",
    Prefs.K_PANE_SWIPE_AT to "gesture swipe hard soft firm",
    Prefs.K_PLAYER_FACE to "music player look style",
    Prefs.K_DISC_GLOW to "aura light record",
    Prefs.K_BEAT_PULSE to "beat drum rhythm",
    Prefs.K_PULSE_DEPTH to "beat sensitivity rhythm drum",
    Prefs.K_SCRATCH to "dj turntable scrub record",
    Prefs.K_RESUME to "continue position remember carry on",
    Prefs.K_UNINTERRUPTED to "audio focus call interrupt pause",
    Prefs.K_PITCH_MODE to "speed slow chipmunk tape",
    Prefs.K_INDEX to "search index database fast",
    Prefs.K_INDEX_AUTO to "search index background charging battery",
    Prefs.K_LISTEN_MINUTES to "watch new files background",
    Prefs.K_ROOT_VOLUMES to "su superuser system data",
    Prefs.K_UPD_OFF to "update version github notification",
    PrefKeys.CACHE_LOCATION to "cache storage space data folder",
    Prefs.K_CACHE_REMIND to "cache space size reminder",
    dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE to "debug logs verbose",
    Prefs.K_FEED_PREFIXES to "hide exclude new files",
    Prefs.K_BOTTOM_BAR to "toolbar buttons bottom",
    Prefs.K_SELECTION_BAR to "selection actions toolbar",
)

/** The search itself. Pure, so what a word finds is a test. */
internal object SettingsSearch {

    /** Matching entries, title hits first, then the rest in page order. */
    fun find(query: String, index: List<SettingsEntry> = SETTINGS_INDEX): List<SettingsEntry> {
        if (TextMatch.terms(query).isEmpty()) return emptyList()
        val hits = index.filter { e ->
            TextMatch.matches(query, e.title, e.text, e.key, e.section, WORDS[e.key])
        }
        return hits.sortedBy { if (TextMatch.matches(query, it.title)) 0 else 1 }
    }

    /** "Disc glow · Viewers": what a result says it is and where it lives. */
    fun pathOf(e: SettingsEntry): String =
        if (e.section.isBlank() || e.section == e.title) e.tab.label else e.section + " · " + e.tab.label
}

/**
 * The Settings list, counting its items so a setting can be scrolled to by key.
 *
 * Every `item` and `items` the page emits goes through here and is counted; an [anchor] just
 * before a row's item records the index that item will have. The page is rebuilt for each tab,
 * and the count with it, so the index a key maps to is always the one in the tab on screen.
 */
internal class AnchoredList(private val base: LazyListScope, private val marks: MutableMap<String, Int>) :
    LazyListScope by base {

    private var count = 0

    /** The next item holds the rows for [keys]. The first anchor for a key wins. */
    fun anchor(vararg keys: String) {
        for (k in keys) marks.putIfAbsent(k, count)
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        count++
        base.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        this.count += count
        base.items(count, key, contentType, itemContent)
    }

    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.(Int) -> Unit) {
        count++
        base.stickyHeader(key, contentType, content)
    }
}

/**
 * The page while the box has letters in it: one flat list of the settings that match, each with
 * where it lives - "Disc glow · Viewers · Playback" - and the matched letters lit. A tap is the
 * caller's: it goes to the tab and marks the row.
 */
@Composable
internal fun SettingsResults(query: String, onPick: (SettingsEntry) -> Unit) {
    val colors = Filet.colors
    val hits = remember(query) { SettingsSearch.find(query) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (hits.isEmpty()) item { NoMatch(query) }
        items(hits, key = { it.key }) { e ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(e) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                ListNameText(
                    text = highlighted(e.title, query, colors.accent),
                    color = MaterialTheme.colorScheme.onSurface,
                    startSp = 13f,
                    lines = 1,
                    isDir = true,
                )
                ListNameText(
                    name = SettingsSearch.pathOf(e),
                    color = colors.fg3,
                    startSp = 10.5f,
                    isDir = true,
                )
            }
        }
    }
}
