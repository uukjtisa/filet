package dev.niccc2007.filet.browser

/**
 * Whether the tabs from last time come back.
 *
 * Saving and restoring already worked - `persistTabs` and `restoreTabs` were both there and
 * both correct. What was missing was the switch, and one rule about it that is easy to get
 * wrong in a way nobody notices until their tabs are gone for good.
 */
object TabRestore {

    /**
     * The saved tab list to restore from, or null to start with Home alone.
     *
     * **Turning the setting off must not delete the saved tabs.** The obvious implementation is
     * to clear the record when the setting goes off, and it is destructive and irreversible: a
     * person who flicks the switch to see what it does loses every tab they had, and turning it
     * back on gives them nothing. So the record is kept and simply not read, and switching back
     * on restores what was there.
     */
    fun savedStateFor(raw: String?, enabled: Boolean): String? = if (enabled) raw else null
}
