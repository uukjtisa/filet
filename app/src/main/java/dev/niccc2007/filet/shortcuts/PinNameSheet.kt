package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.R
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A tab, an action or a script about to go on the home screen, waiting for its name.
 *
 * @param kind never [ShortcutKind.FILE]: a file has its own sheet, which also picks the opener.
 * @param target what the record will point at - a `TabTarget` id, an [AppAction] name or a
 *   script id.
 * @param name the name offered, which the person can change.
 * @param opens one line saying what a tap on the icon will do.
 * @param art the foreground layer of the icon the launcher will show, on the 108-unit canvas.
 * @param icon the launcher icon itself, an adaptive mipmap.
 * @param tile the background layer under [art], or null for Filet's plain ground colour.
 */
data class PinAsk(
    val kind: ShortcutKind,
    val target: String,
    val name: String,
    val opens: String,
    val art: Int = R.drawable.ic_launcher_foreground,
    val icon: Int = R.mipmap.ic_launcher,
    val tile: Int? = null,
)

/**
 * The name, chosen before the launcher is asked.
 *
 * The launcher's own dialog cannot rename anything: it shows the icon and offers Add or Cancel. So
 * a shortcut that should be called something else had to be added first and renamed from the
 * Shortcuts tab afterwards, and a rename only reaches launchers that still list the shortcut. Every
 * kind of shortcut is named here first, the way a file's already was.
 */
@Composable
fun PinNameSheet(ask: PinAsk, onDismiss: () -> Unit, onAdd: (label: String) -> Unit) {
    var label by remember(ask) { mutableStateOf(ask.name) }
    // dismissOnScrim is off for the same reason as the file sheet's: there is typing in here.
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Pin, "Add to home screen", onClose = onDismiss)
        DlgBody {
            DlgField("Name", label, { label = it.take(40) })
            Spacer(Modifier.height(12.dp))
            HomeIconPreview(ask.art, ask.tile, label.ifBlank { ask.name })
            Spacer(Modifier.height(8.dp))
            DlgCaption(ask.opens)
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Add", kind = BtnKind.PRIMARY, enabled = label.isNotBlank()) {
                onAdd(label.trim())
            }
        }
    }
}

/**
 * The icon over its name, cut where a home screen cuts it.
 *
 * A home screen gives a name one short line, and the end of a long one is what disappears - which
 * is the part that usually tells two shortcuts apart. Seeing the cut here is the point of it.
 */
@Composable
private fun HomeIconPreview(art: Int, tile: Int?, label: String) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            // The layer is drawn on 108 units of which a mask shows the middle 72: at 52dp for
            // the 72, the whole layer is 78dp, and the circle clips the rest as a launcher would.
            if (tile != null) Image(painterResource(tile), contentDescription = null, modifier = Modifier.requiredSize(78.dp))
            Image(painterResource(art), contentDescription = null, modifier = Modifier.requiredSize(78.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            color = colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp).padding(top = 5.dp),
        )
    }
}
