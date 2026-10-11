package dev.niccc2007.filet.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermsTest {

    @Test
    fun `every entry is unique and says what going without it costs`() {
        val ids = Perms.CATALOGUE.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (e in Perms.CATALOGUE) {
            assertTrue("${e.id} has no label", e.label.isNotBlank())
            assertTrue("${e.id} has no reason", e.needed.length > 20)
            assertTrue("${e.id} has a backwards range", e.minSdk <= e.maxSdk)
        }
    }

    @Test
    fun `an install-time permission is never revocable and never in the queue`() {
        for (e in Perms.CATALOGUE.filter { it.kind == Perms.Kind.INSTALL }) {
            assertFalse("${e.id} claims to be revocable", e.revocable)
        }
        // Nothing granted at all: the queue still refuses to ask for install-time entries,
        // because there is no dialogue and no settings page behind them.
        val queue = Perms.requestQueue(34, emptySet())
        assertTrue(queue.none { it.kind == Perms.Kind.INSTALL })
    }

    @Test
    fun `a permission that does not exist on this phone is not shown`() {
        // Notifications arrive at 33 and legacy storage dies at 29. An old phone must not be
        // shown a permission it cannot have, and a new one must not be shown a dead one.
        val old = Perms.shownOn(28).map { it.id }
        assertFalse(Perms.NOTIFICATIONS in old)
        assertFalse(Perms.ALL_FILES in old)
        assertTrue(Perms.READ_STORAGE in old)

        val new = Perms.shownOn(34).map { it.id }
        assertTrue(Perms.NOTIFICATIONS in new)
        assertTrue(Perms.ALL_FILES in new)
        assertFalse("legacy storage is superseded at 30", Perms.READ_STORAGE in new)
    }

    @Test
    fun `the queue puts the cheap asks first`() {
        // Runtime permissions are one dialogue and never leave the app; a special is a whole
        // Settings page. Front-loading the dialogues means the sequence banks what it can
        // before it starts backgrounding the app.
        val queue = Perms.requestQueue(34, emptySet())
        val firstSpecial = queue.indexOfFirst { it.kind == Perms.Kind.SPECIAL }
        val lastRuntime = queue.indexOfLast { it.kind == Perms.Kind.RUNTIME }
        assertTrue(queue.isNotEmpty())
        if (firstSpecial >= 0 && lastRuntime >= 0) assertTrue(lastRuntime < firstSpecial)
    }

    @Test
    fun `granting something empties it out of the queue`() {
        val all = Perms.requestQueue(34, emptySet())
        val one = Perms.requestQueue(34, setOf(Perms.ALL_FILES))
        assertEquals(all.size - 1, one.size)
        assertTrue(one.none { it.id == Perms.ALL_FILES })
    }

    @Test
    fun `the summary never claims more than is held`() {
        val none = Perms.summary(34, emptySet())
        assertTrue(none.startsWith("0 of "))

        val askable = Perms.shownOn(34).filter { it.kind != Perms.Kind.INSTALL }
        val everything = Perms.summary(34, askable.map { it.id }.toSet())
        assertTrue("all granted should say so", everything.contains("All ${askable.size} granted"))
    }

    @Test
    fun `the battery exemption is carried as a setting, not pretended to be a permission`() {
        val e = Perms.CATALOGUE.first { it.id == Perms.BATTERY }
        // Deliberately not an `android.permission.` id: it is a device setting, and a fake
        // permission id here would be passed to checkSelfPermission by the next person.
        assertFalse(e.id.startsWith("android.permission."))
        assertEquals(Perms.Kind.SPECIAL, e.kind)
    }
}
