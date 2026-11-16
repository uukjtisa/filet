package dev.niccc2007.filet.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from elsewhere in Filet to open Settings on one row: its tab, scrolled to it and
 * marked, exactly as a search result lands.
 *
 * Decision: a key and nothing else. The row's tab is read from the search index, which is written
 * from the page itself, so a jump cannot point at a tab the row has since moved out of.
 */
object SettingsJump {
    private val _to = MutableStateFlow<String?>(null)

    /** The row asked for and not yet shown. */
    val to: StateFlow<String?> = _to.asStateFlow()

    fun request(key: String) {
        _to.value = key
    }

    /** Settings has shown it. */
    fun taken() {
        _to.value = null
    }
}
