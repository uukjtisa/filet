package dev.niccc2007.filet.handlers

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow

/**
 * An editor's Save: one button, and a menu of where the edit goes - over the file, or into a new
 * one chosen with Filet's picker. One menu for the picture editor and the text editor, so the two
 * saves cannot drift into two shapes.
 *
 * @param quiet drawn dim while there is nothing new to write, still pressed for Save as new.
 * @param replaceDetail what replacing would do, or null where it is not offered: a folder that
 *   cannot be written to, a format Android cannot write back, or nothing changed. A new file can
 *   still be saved anywhere else, so the menu always opens.
 */
@Composable
internal fun SaveChoice(
    label: String,
    enabled: Boolean,
    replaceDetail: String?,
    newDetail: String,
    onReplace: () -> Unit,
    onNew: () -> Unit,
    quiet: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ViewerAction(FiletIcons.Save, label, enabled = enabled, quiet = quiet) { open = true }
        PopMenu(expanded = open, onDismiss = { open = false }, below = 44.dp) {
            if (replaceDetail != null) {
                PopRow(FiletIcons.Save, "Replace original", detail = replaceDetail) {
                    open = false
                    onReplace()
                }
            }
            PopRow(FiletIcons.Copy, "Save as new", detail = newDetail) {
                open = false
                onNew()
            }
        }
    }
}
