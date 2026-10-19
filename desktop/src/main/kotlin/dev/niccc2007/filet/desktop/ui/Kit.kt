package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The handful of controls the window needs, on `foundation` alone.
 *
 * Decision: no Material dependency. The window has buttons, a checkbox and one text field, and
 * pulling a design system in for that adds a theme to fight and a dependency whose version has to
 * track Compose's. Light-first, the same warm neutrals the phone uses.
 */
object Ink {
    val bg = Color(0xFFF7F6F2)
    val card = Color(0xFFFFFFFF)
    val text = Color(0xFF1C1B19)
    val muted = Color(0xFF6B6964)
    val line = Color(0xFFE3E1DA)
    val accent = Color(0xFF2F6B4F)
    val warn = Color(0xFF9A4A1C)
    val off = Color(0xFFB9B6AE)
}

@Composable
fun Txt(s: String, size: Int = 14, color: Color = Ink.text, bold: Boolean = false, mono: Boolean = false, modifier: Modifier = Modifier) =
    BasicText(
        s, modifier,
        style = TextStyle(
            color = color, fontSize = size.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )

/** A button that is visibly off when it cannot act - never a live-looking control that does nothing. */
@Composable
fun Btn(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val fill = when { !enabled -> Color.Transparent; primary -> Ink.accent; else -> Ink.card }
    val fg = when { !enabled -> Ink.off; primary -> Color.White; else -> Ink.text }
    Box(
        Modifier
            .background(fill, shape)
            .border(1.dp, if (enabled && primary) Ink.accent else Ink.line, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Txt(label, 13, fg, bold = primary) }
}

@Composable
fun Check(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val c = if (enabled) Ink.accent else Ink.off
        Box(
            Modifier.size(16.dp).border(1.5.dp, c, RoundedCornerShape(3.dp))
                .background(if (checked) c else Color.Transparent, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Txt("✓", 11, Color.White, bold = true) }
        Txt(label, 14, if (enabled) Ink.text else Ink.muted)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Ink.card, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) Txt(placeholder, 14, Ink.off)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Ink.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Ink.accent),
        )
    }
}

@Composable
fun SectionHead(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, 12, Ink.muted, bold = true, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Puts [s] on the system clipboard. AWT's, which needs no Compose clipboard API version. */
fun copy(s: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(s), null)
    }
}
