package dev.niccc2007.filet.handlers

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSpacer

/**
 * Leaving an editor with unsaved changes asks first: Save, Discard or Keep editing.
 *
 * Bug identified here: the editors' own back arrows went straight to `closeHandler`, and the
 * phone's back went to the viewer host's handler, which does the same - so a text file edited
 * and left by back lost every change without a word. Two editors had their own question, each on
 * one of the two ways out and not the other. This is the one place the question lives: it takes
 * the phone's back while there is anything to lose, and hands back what the bar's arrow calls, so
 * an editor cannot answer one way out and forget the other.
 *
 * Save asks once more before it writes, because from here it writes over the file without the
 * editor in front of the reader; Discard does not, because it leaves the file exactly as it was.
 *
 * @param dirty whether anything would be lost.
 * @param name the file's name, for the second question.
 * @param leave what leaving is - usually `vm.closeHandler()`.
 * @param save writes the changes and calls its argument once they are written. Null where this
 *   editor cannot save from a question (a picture whose format cannot be written back in place):
 *   then only Discard and Keep editing are offered, and the editor's own Save is still there.
 * @param saveLabel the editor's own word for saving - "Apply" where that is what its bar says.
 * @return what the editor's back arrow calls.
 */
@Composable
fun rememberLeaveGuard(
    dirty: Boolean,
    name: String,
    leave: () -> Unit,
    save: ((after: () -> Unit) -> Unit)?,
    saveLabel: String = "Save",
): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    var sure by remember { mutableStateOf(false) }
    val nowDirty by rememberUpdatedState(dirty)
    val nowLeave by rememberUpdatedState(leave)
    // Declared inside the editor, so after the viewer host's own handler, and so asked first.
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        val close = { asking = false; sure = false }
        Dlg(onDismiss = close) {
            DlgHeader(
                icon = FiletIcons.Save,
                title = if (sure) "$saveLabel over $name?" else "Save your changes?",
                sub = if (sure) "The file as it was is replaced." else "They are not in the file yet.",
                onClose = close,
            )
            if (sure) {
                DlgFooter {
                    DlgSpacer()
                    DlgBtn("Back") { sure = false }
                    DlgBtn(saveLabel, kind = BtnKind.PRIMARY) {
                        close()
                        save?.invoke { nowLeave() }
                    }
                }
            } else {
                DlgBody {
                    if (save != null) DlgPick(FiletIcons.Check, saveLabel, detail = "Into $name, then leave.") { sure = true }
                    DlgPick(FiletIcons.Close, "Discard", detail = "Leave without them. The file stays as it was.") {
                        close()
                        nowLeave()
                    }
                    DlgPick(FiletIcons.Back, "Keep editing") { close() }
                }
            }
        }
    }
    return remember { { if (nowDirty) asking = true else nowLeave() } }
}
