// Ported from mikepenz/multiplatform-markdown-renderer - MarkdownAlert.kt, MarkdownAlertColors.kt,
// MarkdownAlertIcons.kt and MarkdownAlertType.kt (https://github.com/mikepenz/multiplatform-markdown-renderer).
// Copyright Mike Penz and contributors.
// SPDX-License-Identifier: Apache-2.0
//
// The icon paths are Material Design icons (info, lightbulb, feedback, warning, report, format_size
// and toc, all outlined), Copyright Google LLC, Apache-2.0.
//
// Changed from the original: the alert is drawn a block at a time as a frame of the flat reader
// list (see `Nest`) rather than as a Column around its children; the palette, titles and icons
// are kept as they were, keyed on this reader's `Frame`; two icons were added for the reader's bar.
package dev.niccc2007.filet.docs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * GitHub's five alerts: their names, their accents and their icons.
 *
 * Fixed rather than taken from the theme, as the original explains: Material's colour schemes have
 * no slot that means "a green tip" or "an amber warning", and an alert whose colour moved with the
 * accent setting would stop saying which kind it is.
 */
internal object DocAlerts {

    fun title(frame: Frame): String = when (frame) {
        Frame.NOTE -> "Note"
        Frame.TIP -> "Tip"
        Frame.IMPORTANT -> "Important"
        Frame.WARNING -> "Warning"
        Frame.CAUTION -> "Caution"
        Frame.INDENT, Frame.QUOTE -> ""
    }

    /** The accent of an alert's bar, icon and title, in GitHub's dark or light palette. */
    fun accent(frame: Frame, dark: Boolean): Color = when (frame) {
        Frame.NOTE -> if (dark) Color(0xFF4493F8) else Color(0xFF0969DA)
        Frame.TIP -> if (dark) Color(0xFF3FB950) else Color(0xFF1A7F37)
        Frame.IMPORTANT -> if (dark) Color(0xFFAB7DF8) else Color(0xFF8250DF)
        Frame.WARNING -> if (dark) Color(0xFFD29922) else Color(0xFF9A6700)
        Frame.CAUTION -> if (dark) Color(0xFFF85149) else Color(0xFFCF222E)
        Frame.INDENT, Frame.QUOTE -> Color.Unspecified
    }

    fun icon(frame: Frame): ImageVector? = when (frame) {
        Frame.NOTE -> DocIcons.Note
        Frame.TIP -> DocIcons.Tip
        Frame.IMPORTANT -> DocIcons.Important
        Frame.WARNING -> DocIcons.Warning
        Frame.CAUTION -> DocIcons.Caution
        Frame.INDENT, Frame.QUOTE -> null
    }
}

/** The reader's own icons. Tinted where drawn, so the fill here does not matter. */
internal object DocIcons {
    val Note: ImageVector by lazy {
        icon(
            "DocNote",
            "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 " +
                "18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
        )
    }

    val Tip: ImageVector by lazy {
        icon(
            "DocTip",
            "M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 " +
                "5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7zm2.85 " +
                "11.1l-.85.6V16h-4v-2.3l-.85-.6C7.8 12.16 7 10.63 7 9c0-2.76 2.24-5 5-5s5 2.24 5 5c0 1.63-.8 " +
                "3.16-2.15 4.1z",
        )
    }

    val Important: ImageVector by lazy {
        icon(
            "DocImportant",
            "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17l-.59.59-.58." +
                "58V4h16v12zm-9-4h2v2h-2zm0-6h2v4h-2z",
        )
    }

    val Warning: ImageVector by lazy {
        icon("DocWarning", "M12 5.99L19.53 19H4.47L12 5.99M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-6h-2v4h2v-4z")
    }

    val Caution: ImageVector by lazy {
        icon(
            "DocCaution",
            "M15.73 3H8.27L3 8.27v7.46L8.27 21h7.46L21 15.73V8.27L15.73 3zM19 14.9L14.9 19H9.1L5 14.9V9.1L9.1 " +
                "5h5.8L19 9.1v5.8zM11 7h2v6h-2zm0 8h2v2h-2z",
        )
    }

    /** "Aa": the reading settings. */
    val TextSize: ImageVector by lazy { icon("DocTextSize", "M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z") }

    /** The Contents sheet. */
    val Contents: ImageVector by lazy {
        icon("DocContents", "M3 9h14V7H3v2zm0 4h14v-2H3v2zm0 4h14v-2H3v2zm16 0h2v-2h-2v2zm0-10v2h2V7h-2zm0 6h2v-2h-2v2z")
    }

    private fun icon(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black),
    ).build()
}
