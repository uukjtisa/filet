package dev.niccc2007.filet.icons

import android.content.Context
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Legacy icons setting, `look.legacy.v1`: which places wear which looks. See [IconStyles].
 *
 * Its own store, never the folder records': a look change writes only this, and Change icon writes
 * only `folder.styles.v1`. No icon is stored anywhere, so switching back to drawn brings back
 * exactly what was there.
 *
 * Kept through the generic string store and re-read on any write to its key, so Import and
 * Export (which carry it with the other Appearance settings) take effect without a restart.
 */
class LegacyLooks internal constructor(
    private val prefs: Prefs,
    /** The lists' Folders moved to or from whole colour: pinned folder icons take their paint from it. */
    private val repaint: () -> Unit = {},
) {
    private val _styles = MutableStateFlow(read())
    val styles: StateFlow<IconStyles> = _styles.asStateFlow()

    // Held for the life of the process, as the store is.
    @Suppress("unused")
    private val listening = prefs.onChange { key -> if (key == null || key == KEY) _styles.value = read() }

    fun set(next: IconStyles) {
        val before = _styles.value
        _styles.value = next
        if ((before.files.folders == FolderDrawing.DRAWN_WHOLE) != (next.files.folders == FolderDrawing.DRAWN_WHOLE)) repaint()
        // Back to drawn removes the key, so a fresh install and one switched back read the same.
        prefs.putString(KEY, if (next == IconStyles.DRAWN) null else next.toJson())
    }

    private fun read(): IconStyles = IconStyles.fromJson(prefs.getString(KEY))

    companion object {
        const val KEY = "look.legacy.v1"

        @Volatile private var one: LegacyLooks? = null

        fun of(context: Context): LegacyLooks = one ?: synchronized(this) {
            one ?: context.applicationContext.let { app ->
                LegacyLooks(FiletApp.graphOf(app).prefs) { FolderShortcutIcon.refresh(app) }
            }.also { one = it }
        }
    }
}
