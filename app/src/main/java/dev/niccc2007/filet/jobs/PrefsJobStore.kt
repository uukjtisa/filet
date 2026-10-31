package dev.niccc2007.filet.jobs

import android.content.Context

/**
 * The activity history, in a preference store of its own.
 *
 * Not the main one: that is what `filet.json` mirrors, and a history written on every finished
 * copy would rewrite a settings file nobody changed. A store of its own also means clearing it
 * never touches a setting.
 */
class PrefsJobStore(context: Context) : JobStore {
    private val sp = context.applicationContext.getSharedPreferences("filet.jobs", Context.MODE_PRIVATE)
    override fun load(): String? = sp.getString(KEY, null)
    override fun save(text: String) { sp.edit().putString(KEY, text).apply() }
    private companion object { const val KEY = "history" }
}
