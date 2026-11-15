package dev.niccc2007.filet.handlers

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.docs.DocFormat
import dev.niccc2007.filet.docs.HTML_EXTENSIONS
import dev.niccc2007.filet.ui.dialogs.PanelRow
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The ways one open file can be shown, offered in its three-dot menu: the page it draws, its
 * source, and its bytes. One screen per file rather than a viewer and an editor pointing at each
 * other; the menu says what this file has and lights the one on screen.
 */
enum class ViewMode(val label: String) {
    PAGE("Page"),
    CODE("Code"),
    HEX("Hex"),
}

object ViewModes {
    /** The handler that opens [extension] on its page, or null when it has none. */
    fun pageHandler(extension: String): HandlerId? {
        val ext = extension.lowercase()
        return when {
            ext in HTML_EXTENSIONS -> HandlerId.WEB
            DocFormat.of(ext) == DocFormat.MARKDOWN -> HandlerId.DOC
            else -> null
        }
    }

    /** What [extension] can be shown as, in menu order. */
    fun of(extension: String): List<ViewMode> =
        listOfNotNull(if (pageHandler(extension) != null) ViewMode.PAGE else null, ViewMode.CODE, ViewMode.HEX)

    /** The handler that opens [extension] in [mode]. Page falls back to Code for a file with no page. */
    fun handlerFor(mode: ViewMode, extension: String): HandlerId = when (mode) {
        ViewMode.PAGE -> pageHandler(extension) ?: HandlerId.TEXT
        ViewMode.CODE -> HandlerId.TEXT
        ViewMode.HEX -> HandlerId.HEX
    }
}

private fun ViewMode.icon(): ImageVector = when (this) {
    ViewMode.PAGE -> FiletIcons.Eye
    ViewMode.CODE -> FiletIcons.Code
    ViewMode.HEX -> FiletIcons.Hex
}

/** One row per mode, the one on screen lit. Picking the lit one only closes the panel. */
@Composable
fun ColumnScope.ModeRows(modes: List<ViewMode>, current: ViewMode, close: () -> Unit, pick: (ViewMode) -> Unit) {
    val colors = Filet.colors
    for (m in modes) {
        PanelRow(m.label, colors.accent, null, m.icon(), ink = if (m == current) colors.accent else null) {
            close()
            if (m != current) pick(m)
        }
    }
}
