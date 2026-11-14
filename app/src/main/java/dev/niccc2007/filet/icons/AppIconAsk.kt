package dev.niccc2007.filet.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet

/** A design and colour as the picker names it: "Tile, Ember". */
fun AppIconSwitch.Choice.label(): String {
    val d = AppIconSet.DESIGNS.firstOrNull { it.first == design }?.second ?: design
    val c = AppIconSet.COLOURS.firstOrNull { it.first == colour }?.second ?: colour
    return "$d, $c"
}

/**
 * Import's ask: the settings file carries an app icon other than this phone's. No keeps the icon
 * as it is; Yes switches, as the picker's Switch does.
 */
@Composable
fun AppIconAsk(to: AppIconSwitch.Choice, onUse: () -> Unit, onKeep: () -> Unit) {
    val context = LocalContext.current
    val now = remember { AppIconSwitch.current(context) }
    Dlg(onDismiss = onKeep) {
        DlgHeader(FiletIcons.Info, "Also use this app icon?", onClose = onKeep)
        DlgBody {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAndName(now, "Now")
                IconAndName(to, "In the file")
            }
            Spacer(Modifier.height(10.dp))
            DlgCaption(
                "Switching moves the icon: your launcher takes it off its spot, and it comes back " +
                    "in the app list or at the end of your last home screen.",
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Keep mine", onClick = onKeep)
            DlgBtn("Use it", kind = BtnKind.PRIMARY, onClick = onUse)
        }
    }
}

@Composable
private fun IconAndName(c: AppIconSwitch.Choice, caption: String) {
    val context = LocalContext.current
    val res = AppIconSet.ICON[c.key]
    val bitmap = remember(res) {
        res?.let { runCatching { context.getDrawable(it)?.toBitmap(144, 144)?.asImageBitmap() }.getOrNull() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) Image(bitmap, c.label(), Modifier.size(56.dp))
        Spacer(Modifier.height(4.dp))
        Text(c.label(), fontSize = 12.sp, color = Filet.colors.fg2)
        Text(caption, fontSize = 10.sp, color = Filet.colors.fg3)
    }
}
