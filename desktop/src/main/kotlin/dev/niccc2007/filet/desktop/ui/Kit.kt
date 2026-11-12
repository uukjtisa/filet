package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The handful of controls the window needs, on `foundation` alone.
 *
 * Decision: no Material dependency. The window has buttons, a switch and a few text fields, and
 * pulling a design system in for that adds a theme to fight and a dependency whose version has to
 * track Compose's.
 *
 * Dark, with the colours taken from the Filet mark: [primary] and [accent] are the folder's two
 * blues, on near-black panels. State colours are reserved for state - green for mounted or on,
 * amber for something that needs a decision - so a glance at the window reads as a status board.
 */
object Ink {
    val bg = Color(0xFF0E1315)
    val panel = Color(0xFF151C1F)
    val strip = Color(0xFF0A0E10)
    val line = Color(0xFF1E282C)
    val edge = Color(0xFF2A383D)
    val text = Color(0xFFE4EBEE)
    val soft = Color(0xFFD6E0E3)
    val muted = Color(0xFF7E9096)
    val dim = Color(0xFF9FB0B6)
    val url = Color(0xFFCFE3EA)
    val primary = Color(0xFF4E7382)
    val accent = Color(0xFF7FA8B8)
    val ok = Color(0xFF6FCF97)
    val warn = Color(0xFFE7B074)
    val warnEdge = Color(0xFF6B4A22)
    val btn = Color(0xFF1A2327)
    val tile = Color(0xFF1E2A2F)
}

@Composable
fun Txt(
    s: String,
    size: Number = 14,
    color: Color = Ink.text,
    bold: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    oneLine: Boolean = false,
    spaced: Boolean = false,
) = BasicText(
    s, modifier,
    style = TextStyle(
        color = color, fontSize = size.toFloat().sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        letterSpacing = if (spaced) 0.9.sp else 0.sp,
    ),
    maxLines = if (oneLine) 1 else Int.MAX_VALUE,
    overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
)

enum class BtnKind { NORMAL, PRIMARY, GHOST, WARN }

/** A button that is visibly off when it cannot act - never a live-looking control that does nothing. */
@Composable
fun Btn(label: String, enabled: Boolean = true, primary: Boolean = false, kind: BtnKind = if (primary) BtnKind.PRIMARY else BtnKind.NORMAL, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val fill = when (kind) { BtnKind.PRIMARY -> Ink.primary; BtnKind.GHOST -> Color.Transparent; else -> Ink.btn }
    val edge = when (kind) { BtnKind.PRIMARY -> Ink.accent; BtnKind.WARN -> Ink.warnEdge; else -> Ink.edge }
    val fg = when (kind) { BtnKind.PRIMARY -> Color.White; BtnKind.WARN -> Ink.warn; else -> Ink.soft }
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Txt(label, 12.5, fg, bold = kind == BtnKind.PRIMARY) }
}

/** A state, in the colour the state is: [PillKind.ON] green, [PillKind.IDLE] grey, [PillKind.OFF] dim, [PillKind.WARN] amber. */
enum class PillKind(val fill: Color, val fg: Color) {
    ON(Color(0xFF163527), Color(0xFF6FCF97)),
    IDLE(Color(0xFF1E2A2F), Color(0xFF9FB0B6)),
    OFF(Color(0xFF1A1F21), Color(0xFF5F6F75)),
    WARN(Color(0xFF3A2A14), Color(0xFFE7B074)),
}

@Composable
fun Pill(text: String, kind: PillKind) {
    Box(Modifier.background(kind.fill, RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(text, 11.5, kind.fg, bold = true, oneLine = true)
    }
}

/** A small outlined label: a share's permission. */
@Composable
fun Tag(text: String) {
    Box(Modifier.border(1.dp, Ink.edge, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Txt(text, 11, Ink.dim)
    }
}

/** A switch with its label. Off and disabled both read as such, never as a live control. */
@Composable
fun Toggle(on: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.alpha(if (enabled) 1f else 0.4f).then(if (enabled) Modifier.clickable { onChange(!on) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(34.dp).height(20.dp).background(if (on) Ink.primary else Ink.edge, RoundedCornerShape(10.dp))) {
            Box(Modifier.offset(x = if (on) 17.dp else 3.dp, y = 3.dp).size(14.dp).background(Color.White, CircleShape))
        }
        Txt(label, 12.5, Ink.muted)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Ink.bg, RoundedCornerShape(8.dp)).border(1.dp, Ink.edge, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Txt(placeholder, 14, Ink.muted, mono = true)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Ink.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Ink.accent),
        )
    }
}

/** One block of the dashboard: a share, a device, a notice. */
@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = Ink.line, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Ink.panel, RoundedCornerShape(12.dp)).border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionHead(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, 12, Ink.muted, bold = true, spaced = true, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Puts [s] on the system clipboard. AWT's, which needs no Compose clipboard API version. */
fun copy(s: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(s), null)
    }
}
