package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.niccc2007.filet.desktop.Endpoint
import dev.niccc2007.filet.link.DriveLink
import kotlin.math.floor

/**
 * A share on this PC as a QR, for Filet on a phone to scan and add as a network drive.
 *
 * The same link the phone's own share QR carries (`HostingCard.ShareQrDialog`): a [DriveLink] named
 * "device · share", with the device id, every address, the port and `a/<code>`. So the phone's
 * scanner needs nothing new, and scanning again after the PC moves to another address updates the
 * saved drive instead of adding a second one - the device id is what it matches on.
 *
 * Per share and never per PC, for the phone's reason: each share is its own root, code and
 * permission, so a code for "the PC" would have to pick one of them.
 */
object ShareQr {

    /** The https form: a camera app opens it too, and the fragment keeps the code off any server. */
    fun link(pcName: String, deviceId: String, e: Endpoint): String = DriveLink(
        name = "$pcName · ${e.name}",
        deviceId = deviceId,
        hosts = e.hosts,
        port = e.port,
        share = "a/${e.code}",
        tls = false,
    ).link().web()

    /**
     * Rows of modules, or an empty array when [text] cannot be encoded.
     *
     * Level Q. The badge over the middle hides about six per cent of the modules and Q rebuilds up
     * to a quarter. H, which the phone draws at, packs a share link into about seventy-five modules
     * a side, and at the size a PC window gives it those were under three pixels each and did not
     * read back off the screen.
     */
    fun encode(text: String, quietZone: Int = 3): Array<BooleanArray> {
        if (text.isEmpty()) return emptyArray()
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to quietZone,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        }.getOrElse { emptyArray() }
    }

    /** The phone's QR colours. Dark on white whatever the theme: an inverted code is one some cameras refuse. */
    val INK = Color(0xFF121418)
    val BADGE = Color(0xFF1E1F1D)
}

/** [text] as a Filet QR: modules drawn to the layout's size, the Filet mark in the middle. */
@Composable
fun FiletQr(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { ShareQr.encode(text) }
    BoxWithConstraints(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (matrix.isEmpty()) return@Canvas
            // Whole pixels per module, centred. A fractional module is smeared across two pixel
            // columns, and a smeared code at this density is one a camera cannot read.
            val cell = floor(size.minDimension / matrix.size).coerceAtLeast(1f)
            val origin = floor((size.minDimension - cell * matrix.size) / 2f)
            for (y in matrix.indices) {
                val row = matrix[y]
                for (x in row.indices) {
                    if (row[x]) drawRect(ShareQr.INK, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
                }
            }
        }
        if (matrix.isNotEmpty()) {
            val side = maxWidth
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(side * 0.06f))
                    .padding(side * 0.014f)
                    .background(ShareQr.BADGE, RoundedCornerShape(side * 0.05f))
                    .padding(side * 0.02f),
            ) {
                Image(painterResource("filet.png"), null, Modifier.size(side * 0.15f))
            }
        }
    }
}
