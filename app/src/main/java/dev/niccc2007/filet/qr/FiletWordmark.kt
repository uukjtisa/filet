package dev.niccc2007.filet.qr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.link.QrMark

/** The wordmark in the middle of a Filet QR, as a vector. The outline itself is [QrMark]'s. */
object FiletWordmark {
    const val WIDTH = QrMark.WORDMARK_WIDTH
    const val HEIGHT = QrMark.WORDMARK_HEIGHT

    val vector: ImageVector by lazy {
        ImageVector.Builder(
            name = "wordmark", defaultWidth = (WIDTH / 100f).dp, defaultHeight = (HEIGHT / 100f).dp,
            viewportWidth = WIDTH, viewportHeight = HEIGHT,
        ).addPath(PathParser().parsePathString(QrMark.WORDMARK).toNodes(), fill = SolidColor(Color.White)).build()
    }
}
