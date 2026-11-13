package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shortcuts tab's rows: folding, the empty card, and catalogue rows that know what is
 * already on the home screen.
 */
class ShortcutLinesTest {

    private fun lines(
        records: List<ShortcutRecord> = emptyList(),
        tabs: List<TabTarget> = TabTarget.entries,
        actions: List<AppAction> = AppAction.LISTED,
        folded: Set<String> = ShortcutStore.DEFAULT_FOLDED,
        searching: Boolean = false,
        nothingPlaced: Boolean = records.isEmpty(),
        menu: List<String>? = null,
    ) = shortcutLines(
        records = records,
        tabs = tabs,
        actions = actions,
        scripts = emptyList(),
        placedIds = records.mapTo(HashSet()) { it.id },
        folded = folded,
        searching = searching,
        nothingPlaced = nothingPlaced,
        menu = menu,
    )

    private fun tab(t: TabTarget) = ShortcutRecord(TAB_PREFIX + t.id, ShortcutKind.TAB, t.home, t.id, createdAt = 0L)

    @Test
    fun `nothing pinned shows the empty card where the list would be`() {
        val out = lines()
        assertEquals(Line.Empty, out.first())
        assertTrue(out.none { it is Line.Head && it.id == ShortcutStore.FOLD_PLACED })
    }

    @Test
    fun `tabs start open and actions start folded`() {
        val out = lines()
        assertEquals(TabTarget.entries.size, out.count { it is Line.Tab })
        val actions = out.filterIsInstance<Line.Head>().single { it.id == ShortcutStore.FOLD_ACTIONS }
        assertTrue(actions.folded)
        assertEquals(AppAction.LISTED.size, actions.count)
        assertTrue(out.none { it is Line.Action })
    }

    @Test
    fun `every tab is offered once, under its group`() {
        val out = lines()
        assertEquals(TabTarget.entries.toSet(), out.filterIsInstance<Line.Tab>().map { it.target }.toSet())
        var group: Line.Group? = null
        for (line in out) {
            if (line is Line.Group) group = line
            if (line is Line.Tab) assertEquals(line.target.group, group?.group)
        }
    }

    @Test
    fun `a folded section keeps its heading and count`() {
        val out = lines(folded = setOf(ShortcutStore.FOLD_TABS))
        val head = out.filterIsInstance<Line.Head>().single { it.id == ShortcutStore.FOLD_TABS }
        assertTrue(head.folded)
        assertEquals(TabTarget.entries.size, head.count)
        assertTrue(out.none { it is Line.Tab || it is Line.Group })
    }

    @Test
    fun `a tab already on the home screen says so in the catalogue`() {
        val out = lines(records = listOf(tab(TabTarget.PLAYLISTS)))
        val rows = out.filterIsInstance<Line.Tab>()
        assertTrue(rows.single { it.target == TabTarget.PLAYLISTS }.placed)
        assertFalse(rows.single { it.target == TabTarget.HOME }.placed)
    }

    @Test
    fun `an action already on the home screen says so in the catalogue`() {
        val search = ShortcutRecord("action:SEARCH", ShortcutKind.ACTION, "Search", "SEARCH", createdAt = 0L)
        val out = lines(records = listOf(search), folded = emptySet())
        assertTrue(out.filterIsInstance<Line.Action>().single { it.action == AppAction.SEARCH }.placed)
    }

    @Test
    fun `pinned rows come first under their heading`() {
        val out = lines(records = listOf(tab(TabTarget.HOME)))
        val head = out.first() as Line.Head
        assertEquals(ShortcutStore.FOLD_PLACED, head.id)
        assertEquals(1, head.count)
        assertTrue(out[1] is Line.Placed)
    }

    @Test
    fun `a search that matches nothing says so and shows nothing else`() {
        val out = lines(tabs = emptyList(), actions = emptyList(), searching = true, nothingPlaced = true)
        assertEquals(listOf(Line.NoMatch), out)
    }

    @Test
    fun `a search hides the empty card and drops sections with no match`() {
        val out = lines(tabs = listOf(TabTarget.SCAN), actions = emptyList(), folded = emptySet(), searching = true)
        assertTrue(out.none { it is Line.Empty })
        assertTrue(out.none { it is Line.Head && it.id == ShortcutStore.FOLD_ACTIONS })
        assertEquals(listOf(TabTarget.SCAN), out.filterIsInstance<Line.Tab>().map { it.target })
    }

    @Test
    fun `no scripts means no scripts heading`() {
        assertTrue(lines().none { it is Line.Head && it.id == ShortcutStore.FOLD_SCRIPTS })
    }

    @Test
    fun `row keys are unique, so the list can keep its place`() {
        val out = lines(records = listOf(tab(TabTarget.HOME)), folded = emptySet())
        assertEquals(out.size, out.map { it.key }.toSet().size)
    }

    @Test
    fun `the hold menu sits between the home-screen list and the tabs, counted against its cap`() {
        val out = lines(records = listOf(tab(TabTarget.HOME)), menu = HoldMenu.DEFAULT)
        val heads = out.filterIsInstance<Line.Head>()
        assertEquals(listOf(ShortcutStore.FOLD_PLACED, ShortcutStore.FOLD_MENU, ShortcutStore.FOLD_TABS), heads.map { it.id }.take(3))
        val menuHead = heads.first { it.id == ShortcutStore.FOLD_MENU }
        assertEquals(4, menuHead.count)
        assertEquals(LauncherShortcuts.MAX, menuHead.of)
        assertEquals(Line.MenuChosen(HoldMenu.DEFAULT), out[out.indexOf(menuHead) + 1])
    }

    @Test
    fun `the hold menu starts open and folds to its heading`() {
        assertTrue(lines(menu = HoldMenu.DEFAULT).any { it is Line.MenuChosen })
        val shut = lines(menu = HoldMenu.DEFAULT, folded = setOf(ShortcutStore.FOLD_MENU))
        assertTrue(shut.any { it is Line.Head && it.id == ShortcutStore.FOLD_MENU })
        assertTrue(shut.none { it is Line.MenuChosen || it is Line.MenuAdd || it is Line.MenuGroup })
    }

    @Test
    fun `with four in every other entry is shown but cannot be added`() {
        val full = lines(menu = HoldMenu.DEFAULT).filterIsInstance<Line.MenuAdd>()
        assertEquals(HoldMenu.CHOICES.size - 4, full.size)
        assertTrue(full.all { it.full })
        val three = lines(menu = HoldMenu.DEFAULT.take(3)).filterIsInstance<Line.MenuAdd>()
        assertTrue(three.none { it.full })
        assertTrue(three.any { it.id == HoldMenu.DEFAULT[3] })
    }

    @Test
    fun `a search leaves the hold menu out`() {
        val out = lines(menu = HoldMenu.DEFAULT, folded = emptySet(), searching = true)
        assertTrue(out.none { it is Line.Head && it.id == ShortcutStore.FOLD_MENU })
        assertTrue(out.none { it is Line.MenuChosen || it is Line.MenuAdd || it is Line.MenuGroup })
    }

    @Test
    fun `row keys stay unique with the hold menu open`() {
        val out = lines(records = listOf(tab(TabTarget.HOME)), folded = emptySet(), menu = listOf("search"))
        assertEquals(out.size, out.map { it.key }.toSet().size)
    }
}
