package dev.niccc2007.filet.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video player's uninterruptible setting, made once from the switch the two players shared. */
class UninterruptedSeedTest {

    @Test
    fun `the video gets the shared value the first time, written into the store`() {
        for (shared in listOf(true, false)) {
            val sp = MemoryPrefs(mapOf(Prefs.K_MUSIC_UNINTERRUPTED to shared))
            Prefs.seedVideoUninterrupted(sp)
            assertTrue(sp.contains(Prefs.K_VIDEO_UNINTERRUPTED))
            assertEquals(shared, sp.getBoolean(Prefs.K_VIDEO_UNINTERRUPTED, !shared))
        }
    }

    @Test
    fun `after that the two are apart - the music's value no longer reaches the video`() {
        val sp = MemoryPrefs(mapOf(Prefs.K_MUSIC_UNINTERRUPTED to true))
        Prefs.seedVideoUninterrupted(sp)
        sp.edit().putBoolean(Prefs.K_MUSIC_UNINTERRUPTED, false).apply()
        Prefs.seedVideoUninterrupted(sp)
        assertTrue(sp.getBoolean(Prefs.K_VIDEO_UNINTERRUPTED, false))
        assertFalse(sp.getBoolean(Prefs.K_MUSIC_UNINTERRUPTED, true))
    }

    @Test
    fun `a video value already set is never overwritten`() {
        val sp = MemoryPrefs(mapOf(Prefs.K_MUSIC_UNINTERRUPTED to true, Prefs.K_VIDEO_UNINTERRUPTED to false))
        Prefs.seedVideoUninterrupted(sp)
        assertFalse(sp.getBoolean(Prefs.K_VIDEO_UNINTERRUPTED, true))
    }

    @Test
    fun `a store that never had either starts with both off`() {
        val sp = MemoryPrefs(emptyMap())
        Prefs.seedVideoUninterrupted(sp)
        assertFalse(sp.getBoolean(Prefs.K_VIDEO_UNINTERRUPTED, true))
        assertFalse(sp.contains(Prefs.K_MUSIC_UNINTERRUPTED))
    }

    @Test
    fun `music keeps the key it always had, so its value does not change on the update`() {
        assertEquals("play.uninterrupted", Prefs.K_MUSIC_UNINTERRUPTED)
    }

    /** Just enough of a store for the seed: booleans, contains, and an editor that applies. */
    private class MemoryPrefs(initial: Map<String, Any>) : SharedPreferences {
        private val values = HashMap(initial)

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { pending[key] = null }
            override fun clear() = also { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for ((k, v) in pending) if (v == null) values.remove(k) else values[k] = v
                pending.clear()
            }
        }
    }
}
