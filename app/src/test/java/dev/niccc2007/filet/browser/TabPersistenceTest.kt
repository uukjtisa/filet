package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tab-reopening switch, and the destructive way of building it that this avoids.
 */
class TabPersistenceTest {

    private val saved = """{"tabs":[{"kind":"FOLDER","path":"/storage/emulated/0/Download"}],"a":0,"b":0}"""

    @Test
    fun `with the setting on, the saved tabs are used`() {
        assertEquals(saved, TabRestore.savedStateFor(saved, enabled = true))
    }

    @Test
    fun `with the setting off, the app starts with Home alone`() {
        assertNull(TabRestore.savedStateFor(saved, enabled = false))
    }

    @Test
    fun `turning it off does not destroy the saved tabs`() {
        // The whole point of this file. The obvious implementation clears the record when the
        // setting goes off - which means flicking the switch to see what it does loses every
        // tab, permanently, and turning it back on gives you nothing.
        val off = TabRestore.savedStateFor(saved, enabled = false)
        assertNull(off)

        val backOn = TabRestore.savedStateFor(saved, enabled = true)
        assertEquals("the record must survive being switched off", saved, backOn)
    }

    @Test
    fun `a first run with nothing saved restores nothing either way`() {
        assertNull(TabRestore.savedStateFor(null, enabled = true))
        assertNull(TabRestore.savedStateFor(null, enabled = false))
    }
}
