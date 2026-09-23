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
 * The brief came with its own constraint - *don't bloat* - and it is the right one. Android
 * shows about four before it stops, launchers vary, and a list of nine means the one that is
 * wanted is never in the same place twice. So: four, fixed order, and the only one that
 * changes is the sharing entry, which has to say what pressing it will do.
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
     * Which actions belong in the menu right now.
     *
     * Pure, and tested, because it is a real decision with two ways to be wrong that are both
     * invisible in the app itself: a stale sharing entry that offers to start a share already
     * running, and a list that silently loses its last item past the platform cap.
     *
     * @param sharing whether the LAN share is currently running.
     */
    fun actionsFor(sharing: Boolean): List<AppAction> = listOf(
        // Search first: it is the one anybody reaches for without thinking, and the first
        // slot is the only one whose position is stable across every launcher.
        AppAction.SEARCH,
        AppAction.BOOKMARKS,
        // The pair that changes. Only ever one of them, because offering both means one of
        // the two is always wrong.
        if (sharing) AppAction.STOP_SHARING else AppAction.SHARE_NEARBY,
        AppAction.INDEX_NOW,
    ).take(MAX)

    /**
     * Push the current menu to the launcher.
     *
     * Every failure here is swallowed on purpose. Shortcut APIs throw on rate limiting, on
     * launchers that do not implement them and on some OEM skins that implement them badly,
     * and none of that is worth taking the app down for - the menu is a convenience, and an
     * app that crashes on launch because a launcher refused a shortcut is not.
     */
    fun publish(context: Context, sharing: Boolean) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val infos = actionsFor(sharing).mapNotNull { action ->
            runCatching {
                val intent = Intent(context, ShortcutRouterActivity::class.java).apply {
                    this.action = Intent.ACTION_VIEW
                    putExtra(Shortcuts.EXTRA_ACTION, action.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ShortcutInfo.Builder(context, "menu:" + action.name)
                    .setShortLabel(action.label.take(10))
                    .setLongLabel(action.label.take(25))
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .setRank(0)
                    .build()
            }.getOrNull()
        }
        runCatching { sm.dynamicShortcuts = infos }
    }
}
