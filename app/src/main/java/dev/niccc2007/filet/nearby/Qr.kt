package dev.niccc2007.filet.nearby

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encoding, on zxing's core (pure Java, no Android dependency, no camera code pulled in).
 *
 * Written as a matrix of booleans rather than a `Bitmap` so the drawing stays in Compose and
 * scales with the layout: a bitmap sized for one screen density is blurry on the next.
 */
object Qr {

    /** @return rows of modules, or an empty array when the text cannot be encoded. */
    fun encode(text: String, quietZone: Int = 1): Array<BooleanArray> {
        if (text.isEmpty()) return emptyArray()
        return runCatching {
            val hints = mapOf(
                // A LAN URL is short, so the lowest correction level keeps the modules large
                // and the code easy to scan across a room.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to quietZone,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
        }.getOrElse { emptyArray() }
    }
}
