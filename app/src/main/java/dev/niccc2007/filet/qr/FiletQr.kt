package dev.niccc2007.filet.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.niccc2007.filet.R
import dev.niccc2007.filet.browser.FiletIcons

/**
 * Every QR Filet draws: the network drive's, Share over this network's, and the QR maker's.
 *
 * Decision: one maker, so a QR looks like Filet's wherever it appears and a fix to how codes
 * are drawn lands everywhere at once. The badge in the middle - the app icon over the N mark and
 * the wordmark - is drawn over the modules, which the code survives because a badged code is
 * encoded at the highest correction level ([Qr.encode]). The badge covers under a tenth of the
 * code; level H rebuilds up to thirty per cent.
 *
 * Dark modules on white, always, whatever the theme: a scanner reads contrast, and an inverted
 * code is one some cameras refuse.
 */
@Composable
fun FiletQr(text: String, modifier: Modifier = Modifier, badge: Boolean = true) {
    val matrix = remember(text, badge) { Qr.encode(text, quietZone = 3, badged = badge) }
    BoxWithConstraints(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(QrLook.CORNER)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (matrix.isEmpty()) return@Canvas
            val cell = size.minDimension / matrix.size
            for (y in matrix.indices) {
                val row = matrix[y]
                for (x in row.indices) {
                    if (!row[x]) continue
                    // A fraction of a pixel over, so neighbouring modules meet without a seam.
                    drawRect(QrLook.INK, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
        if (badge && matrix.isNotEmpty()) Badge(maxWidth)
    }
}

/** The middle of a Filet QR: the app icon, and the N mark beside the wordmark under it. */
@Composable
private fun Badge(side: Dp) {
    Column(
        Modifier
            .background(Color.White, RoundedCornerShape(side * 0.075f))
            .padding(side * 0.014f)
            .background(QrLook.BADGE, RoundedCornerShape(side * 0.06f))
            .padding(horizontal = side * 0.028f, vertical = side * 0.016f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(side * 0.17f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FiletIcons.Mark, null, tint = QrLook.LIGHT, modifier = Modifier.size(side * 0.062f))
            Spacer(Modifier.width(side * 0.012f))
            Icon(
                FiletWordmark.vector, null, tint = QrLook.LIGHT,
                modifier = Modifier.height(side * 0.052f).aspectRatio(FiletWordmark.WIDTH / FiletWordmark.HEIGHT),
            )
        }
    }
}

/** The colours of a Filet QR. Fixed rather than themed - see [FiletQr]. */
object QrLook {
    val INK = Color(0xFF121418)
    val BADGE = Color(0xFF1E1F1D)
    val LIGHT = Color(0xFFECEEF0)
    const val CORNER = 8
}
