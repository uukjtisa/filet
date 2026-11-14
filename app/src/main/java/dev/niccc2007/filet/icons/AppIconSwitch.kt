package dev.niccc2007.filet.icons

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.niccc2007.filet.shortcuts.LauncherShortcuts

/**
 * Which app icon is Filet's, and switching it.
 *
 * Each design and colour is a launcher alias of MainActivity (see [AppIconSet] and the manifest's
 * APP ICON ALIASES), and the icon is whichever alias is enabled. Android holds that, not a pref, so
 * there is nothing to keep in step: the package manager is asked every time.
 *
 * A switch enables the new alias before disabling the rest, so there is never a moment with no
 * launcher entry, and does it without killing the app. Android drops the hold menu's entries with
 * the alias they belonged to, so the menu is published again straight away, onto the new one.
 */
object AppIconSwitch {
    /**
     * The app icon in a settings file. Not a preference: Export writes [current] under it, and
     * Import asks before switching to what it holds. Never written to the store.
     */
    const val EXPORT_KEY = "look.appIcon"

    data class Choice(val design: String, val colour: String) {
        val key: String get() = "$design.$colour"

        companion object {
            val DEFAULT = Choice(AppIconSet.DEFAULT_DESIGN, AppIconSet.DEFAULT_COLOUR)

            /** Reads [key]'s form; anything not in the set is null. */
            fun of(key: String?): Choice? {
                val (d, c) = key?.split('.')?.takeIf { it.size == 2 } ?: return null
                val ok = AppIconSet.DESIGNS.any { it.first == d } && AppIconSet.COLOURS.any { it.first == c }
                return if (ok) Choice(d, c) else null
            }
        }
    }

    fun all(): List<Choice> = AppIconSet.DESIGNS.flatMap { (d, _) -> AppIconSet.COLOURS.map { (c, _) -> Choice(d, c) } }

    private fun component(context: Context, c: Choice) =
        ComponentName(context.packageName, AppIconSet.aliasClass(c.design, c.colour))

    /** The icon on now: the enabled alias, or the manifest's default where none was ever switched. */
    fun current(context: Context): Choice {
        val pm = context.packageManager
        for (c in all()) {
            val state = runCatching { pm.getComponentEnabledSetting(component(context, c)) }.getOrNull() ?: continue
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return c
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && c == Choice.DEFAULT) return c
        }
        return Choice.DEFAULT
    }

    /** The launcher entry the hold menu belongs to. */
    fun activeComponent(context: Context): ComponentName = component(context, current(context))

    /** Make [to] Filet's icon. Nothing happens when it already is. */
    fun switchTo(context: Context, to: Choice) {
        val now = current(context)
        if (to == now) return
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, to),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (c in all()) {
            if (c == to) continue
            // Explicitly off, the default included: the manifest has the default on.
            runCatching {
                pm.setComponentEnabledSetting(
                    component(context, c),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        LauncherShortcuts.republish(context)
    }
}
