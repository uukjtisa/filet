package dev.niccc2007.filet.home

import dev.niccc2007.filet.home.HomeArrange.AppCard
import dev.niccc2007.filet.home.HomeArrange.Drive
import dev.niccc2007.filet.home.HomeArrange.DriveKind
import dev.niccc2007.filet.home.HomeArrange.Layout
import dev.niccc2007.filet.home.HomeArrange.PerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How Home's edit state orders, groups and folds the drives, and which categories it keeps. */
class HomeArrangeTest {

    private val internal = "local:///storage/emulated/0"
    private val sd = "local:///storage/1A2B-3C4D"
    private val share = "dav:///studio/movies"
    private val rootData = "root:///data"
    private fun saf(authority: String, tail: String) =
        "saf:///" + java.net.URLEncoder.encode("content://$authority/tree/$tail", "UTF-8") + "/" + tail
    private val termuxHome = saf("com.termux.documents", "home")
    private val driveA = saf("com.google.android.apps.docs.storage", "a")
    private val driveB = saf("com.google.android.apps.docs.storage", "b")

    @Test
    fun `each drive falls in the group it belongs to`() {
        assertEquals(DriveKind.PHONE, HomeArrange.kindOf(internal, remote = false))
        assertEquals(DriveKind.PHONE, HomeArrange.kindOf(rootData, remote = false))
        assertEquals(DriveKind.PLUGGED, HomeArrange.kindOf(sd, remote = false))
        // A drive the phone cannot read is still something plugged in, not part of the phone.
        assertEquals(DriveKind.PLUGGED, HomeArrange.kindOf(HomeArrange.unreadableCard("9F00-0001"), remote = false))
        assertEquals(DriveKind.APPS, HomeArrange.kindOf(termuxHome, remote = false))
        assertEquals(DriveKind.APPS, HomeArrange.kindOf(HomeArrange.TERMUX_OFFER, remote = false))
        assertEquals(DriveKind.APPS, HomeArrange.kindOf(HomeArrange.appCardId("x"), remote = false))
        assertEquals(DriveKind.NETWORK, HomeArrange.kindOf(share, remote = true))
    }

    @Test
    fun `the emulated and self mounts are the phone, not something plugged in`() {
        assertEquals(DriveKind.PHONE, HomeArrange.kindOf("local:///storage/self/primary", remote = false))
        assertEquals(DriveKind.PHONE, HomeArrange.kindOf("local:///storage", remote = false))
    }

    @Test
    fun `the app a granted folder comes from is read from its tree`() {
        assertEquals("com.termux.documents", HomeArrange.authorityOf(termuxHome))
        assertEquals("com.google.android.apps.docs.storage", HomeArrange.authorityOf(driveA))
        assertNull(HomeArrange.authorityOf(internal))
        assertNull(HomeArrange.authorityOf("saf:///not-a-tree"))
    }

    @Test
    fun `an arranged order is kept and a new drive joins after it`() {
        val natural = listOf(internal, sd, share)
        assertEquals(listOf(share, internal, sd), HomeArrange.ordered(natural, listOf(share, internal)))
    }

    @Test
    fun `nothing arranged yet is the natural order`() {
        val natural = listOf(internal, sd, share)
        assertEquals(natural, HomeArrange.ordered(natural, emptyList()))
    }

    @Test
    fun `a drive that is away keeps its place for when it comes back`() {
        val stored = listOf(sd, share, internal)
        // The card is out: the rest keep their order.
        assertEquals(listOf(share, internal), HomeArrange.ordered(listOf(internal, share), stored))
        // Moving something while it is out does not forget it.
        val shown = listOf(share, internal).map(::Drive)
        val after = HomeArrange.moved(stored, shown, internal, share)
        assertEquals(listOf(internal, share, sd), after)
        // Back in, it is drawn where the stored order now says.
        assertEquals(listOf(internal, share, sd), HomeArrange.ordered(listOf(internal, sd, share), after))
    }

    @Test
    fun `dropped further along it lands after the drive passed over`() {
        val shown = listOf(internal, sd, share).map(::Drive)
        assertEquals(listOf(sd, internal, share), HomeArrange.moved(emptyList(), shown, internal, sd))
        assertEquals(listOf(sd, share, internal), HomeArrange.moved(emptyList(), shown, internal, share))
    }

    @Test
    fun `dropped further back it lands in front of the drive passed over`() {
        val shown = listOf(internal, sd, share).map(::Drive)
        assertEquals(listOf(share, internal, sd), HomeArrange.moved(emptyList(), shown, share, internal))
    }

    @Test
    fun `a drop onto itself or onto nothing changes nothing`() {
        val stored = listOf(sd, internal)
        val shown = listOf(sd, internal).map(::Drive)
        assertEquals(stored, HomeArrange.moved(stored, shown, sd, sd))
        assertEquals(stored, HomeArrange.moved(stored, shown, sd, "gone"))
    }

    @Test
    fun `grouped, a drive moves only within its own group`() {
        assertTrue(HomeArrange.canMove(Layout.FLOW, DriveKind.NETWORK, DriveKind.PHONE))
        assertFalse(HomeArrange.canMove(Layout.GROUPED, DriveKind.NETWORK, DriveKind.PHONE))
        assertTrue(HomeArrange.canMove(Layout.GROUPED, DriveKind.APPS, DriveKind.APPS))
    }

    @Test
    fun `one per app folds an app's folders into one card where the first one was`() {
        val ids = listOf(internal, driveA, termuxHome, driveB)
        val tiles = HomeArrange.tiles(ids, PerApp.APP)
        assertEquals(3, tiles.size)
        val card = tiles[1] as AppCard
        assertEquals("com.google.android.apps.docs.storage", card.authority)
        assertEquals(listOf(driveA, driveB), card.ids)
        assertEquals(Drive(termuxHome), tiles[2])
    }

    @Test
    fun `an app with one folder stays a plain card, and a card each is every folder`() {
        assertEquals(listOf(Drive(termuxHome)), HomeArrange.tiles(listOf(termuxHome), PerApp.APP))
        assertEquals(listOf(driveA, driveB).map(::Drive), HomeArrange.tiles(listOf(driveA, driveB), PerApp.EACH))
    }

    @Test
    fun `moving an app's card moves all of its folders and stores only drives`() {
        val ids = listOf(internal, driveA, driveB, share)
        val tiles = HomeArrange.tiles(ids, PerApp.APP)
        val app = tiles[1].id
        val after = HomeArrange.moved(ids, tiles, app, internal)
        assertEquals(listOf(driveA, driveB, internal, share), after)
        assertTrue(after.none { it.startsWith("app:") })
    }

    @Test
    fun `a category can be hidden and shown, but never the last one`() {
        val all = listOf("images", "videos", "audio")
        assertEquals(setOf("images"), HomeArrange.toggledCategory(all, emptySet(), "images"))
        assertEquals(emptySet<String>(), HomeArrange.toggledCategory(all, setOf("images"), "images"))
        assertEquals(setOf("images", "videos"), HomeArrange.toggledCategory(all, setOf("images", "videos"), "audio"))
        assertEquals(emptySet<String>(), HomeArrange.toggledCategory(all, emptySet(), "nonsense"))
    }

    @Test
    fun `stored layout words that are not known read as the defaults`() {
        assertEquals(Layout.FLOW, Layout.of(null))
        assertEquals(Layout.GROUPED, Layout.of("grouped"))
        assertEquals(Layout.FLOW, Layout.of("sideways"))
        assertEquals(PerApp.EACH, PerApp.of(null))
        assertEquals(PerApp.APP, PerApp.of("app"))
    }

    @Test fun `the groups keep the order they were put in, and a group never stored still shows`() {
        assertEquals(DriveKind.entries, HomeArrange.groupOrder(emptyList()))
        assertEquals(
            listOf(DriveKind.NETWORK, DriveKind.PHONE, DriveKind.PLUGGED, DriveKind.APPS),
            HomeArrange.groupOrder(listOf("NETWORK", "PHONE")),
        )
        // A name this version does not know is passed over, never a reason to hide a group.
        assertEquals(DriveKind.entries, HomeArrange.groupOrder(listOf("SATELLITE")))
    }

    @Test fun `a group moves among the groups on screen, and the absent ones keep their place`() {
        val all = DriveKind.entries
        // No stick plugged in: Plugged in is not drawn, so moving Network up passes From apps.
        val shown = listOf(DriveKind.PHONE, DriveKind.APPS, DriveKind.NETWORK)
        assertEquals(
            listOf(DriveKind.PHONE, DriveKind.PLUGGED, DriveKind.NETWORK, DriveKind.APPS),
            HomeArrange.movedGroup(all, shown, DriveKind.NETWORK, -1),
        )
        // Off either end: nothing changes.
        assertEquals(all, HomeArrange.movedGroup(all, shown, DriveKind.PHONE, -1))
        assertEquals(all, HomeArrange.movedGroup(all, shown, DriveKind.NETWORK, 1))
        // A group not drawn cannot be moved.
        assertEquals(all, HomeArrange.movedGroup(all, shown, DriveKind.PLUGGED, 1))
    }
}
