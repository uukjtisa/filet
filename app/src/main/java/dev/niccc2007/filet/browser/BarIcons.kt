package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector

/** The bar actions that are not tabs, in the order the picker lists them. */
val BAR_ACTIONS: List<BarItem> = listOf(BarItem.SEARCH, BarItem.SPLIT, BarItem.NEW_TAB, BarItem.CLOSE_TABS)

/** A bar entry's glyph: the tab's own for a tab, so the bar and the side bar draw one picture. */
fun barIcon(item: BarItem): ImageVector = item.tab?.let(::tabGlyph) ?: when (item) {
    BarItem.SEARCH -> FiletIcons.Search
    BarItem.SPLIT -> FiletIcons.SplitV
    BarItem.NEW_TAB -> FiletIcons.Plus
    BarItem.CLOSE_TABS -> FiletIcons.CloseAll
    else -> FiletIcons.Plus
}
