package dev.niccc2007.filet.data

import android.content.Context

/**
 * The open tabs, as they were left a moment ago, in a preferences file of their own.
 *
 * ## Why not one more key in the main preferences
 *
 * Bug identified here: the tabs were one key in the same file as every setting, written half a
 * second after the last change. The pause meant a phone switched off, or an app swiped away,
 * inside it came back from wherever the tabs had been before - and the key sharing a file meant
 * every write rewrote every setting too, which is what the pause had been there to ration.
 *
 * In a file of their own a write is the size of the tabs alone, so it is cheap enough to make on
 * every change: a folder opened, a tab moved, a list come to rest. `apply` hands the write to
 * Android, which coalesces a burst into the newest state and flushes it before the Activity is
 * stopped, and writes it beside the old copy and swaps, so a phone dying mid-write comes back
 * with the previous save whole rather than half of this one.
 *
 * The file is under `shared_prefs` like the others, so the settings snapshot in the Filet folder
 * carries it with no change there - it takes every file in that directory.
 */
class TabSession(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): String? = sp.getString(KEY, null)

    fun save(text: String) {
        sp.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val FILE = "filet.tabs"
        const val KEY = "tabs"
    }
}
