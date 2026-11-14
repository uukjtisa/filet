package dev.niccc2007.filet.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.niccc2007.filet.R

/**
 * The menu that appears when the Filet icon is held on the home screen.
 *
 * Different from the pinned shortcuts on the Shortcuts tab, which the user builds by hand and
 * which live on the home screen as icons of their own. These are the app's own suggestions,
 * and the app owns them entirely - it decides what is in the list and rewrites it as the state
 * changes.
 *
 * Kept short on purpose. Android shows about four before it stops, launchers vary, and a list
 * of nine means the one that is wanted is never in the same place twice. So: four at most, in
 * the order chosen on the Shortcuts tab's Hold menu, and the only one that changes by itself is
 * the sharing entry, which has to say what pressing it will do.
 */
object LauncherShortcuts {

    /**
     * How many the platform will actually show.
     *
     * Beyond this the extras are silently dropped, and which ones get dropped is up to the
     * launcher - so a list longer than this has entries that exist on some phones and not
     * others.
     */
    const val MAX = 4

    /**
     * The menu as it should read right now: the four chosen on the Shortcuts tab, in their order,
     * with the sharing entry saying Start or Stop by what is running.
     *
     * Pure, and tested, because it is a real decision with two ways to be wrong that are both
     * invisible in the app itself: a stale sharing entry that offers to start a share already
     * running, and a list that silently loses its last item past the platform cap.
     *
     * @param chosen the ids kept by the Shortcuts tab's Hold menu, read through [HoldMenu.normalise].
     * @param sharing whether the LAN share is currently running.
     */
    fun menuFor(chosen: List<String>, sharing: Boolean): List<HoldMenu.Entry> =
        HoldMenu.entriesFor(HoldMenu.encode(chosen), sharing)

    // What was last asked for. The share's state arrives from the app, the four from the store,
    // each on its own schedule; whichever changes publishes both.
    @Volatile private var sharingNow = false
    @Volatile private var chosenNow: List<String> = HoldMenu.DEFAULT

    /** The share started or stopped: publish the menu with the sharing entry turned to match. */
    fun publish(context: Context, sharing: Boolean) {
        sharingNow = sharing
        push(context)
    }

    /** The app icon was switched: Android dropped the menu with the old icon's alias, so put it back. */
    fun republish(context: Context) = push(context)

    /** A different four were chosen on the Shortcuts tab: publish them. */
    fun choose(context: Context, chosen: List<String>) {
        chosenNow = chosen
        push(context)
    }

    /**
     * Push the current menu to the launcher.
     *
     * Every failure here is swallowed on purpose. Shortcut APIs throw on rate limiting, on
     * launchers that do not implement them and on some OEM skins that implement them badly,
     * and none of that is worth taking the app down for - the menu is a convenience, and an
     * app that crashes on launch because a launcher refused a shortcut is not.
     */
    private fun push(context: Context) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val infos = menuFor(chosenNow, sharingNow).mapIndexedNotNull { rank, entry ->
            runCatching {
                val intent = Intent(context, ShortcutRouterActivity::class.java).apply {
                    this.action = Intent.ACTION_VIEW
                    putExtra(Shortcuts.EXTRA_ACTION, entry.route)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ShortcutInfo.Builder(context, "menu:" + entry.route)
                    .setShortLabel(entry.short.take(10))
                    .setLongLabel(entry.long.take(25))
                    .setIcon(Icon.createWithResource(context, launcherIcon(entry.art)))
                    .setIntent(intent)
                    // On the launcher entry that is on now, by name: left to Android it is the
                    // first one, and with the app icon's aliases that may be one about to go.
                    .apply { runCatching { setActivity(dev.niccc2007.filet.icons.AppIconSwitch.activeComponent(context)) } }
                    .setRank(rank)
                    .build()
            }.getOrNull()
        }
        runCatching { sm.dynamicShortcuts = infos }
    }
}
