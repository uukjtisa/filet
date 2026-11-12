package dev.niccc2007.filet.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.niccc2007.filet.desktop.ui.FiletQr
import dev.niccc2007.filet.desktop.ui.Ink
import dev.niccc2007.filet.desktop.ui.ShareQr
import dev.niccc2007.filet.link.DriveLink
import dev.niccc2007.filet.link.FiletLink
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class ShareQrTest {

    private val share = Endpoint(
        name = "Documents", path = "D:\\Documents", url = "http://192.168.5.20:8322/a/tres9/", writable = true,
        code = "tres9", port = 8322, hosts = listOf("192.168.5.20", "192.168.4.13"),
    )

    /** As long as a real one gets: a stock PC name, a full device id, three adapters, a long code. */
    private val worst = Endpoint(
        name = "Downloads and Documents", path = "C:\\", url = "http://192.168.5.22:8322/a/7t8mb8ptztdq/", writable = true,
        code = "7t8mb8ptztdq", port = 8322, hosts = listOf("192.168.5.22", "192.168.56.1", "172.24.160.1"),
    )
    private val worstId = "3f0c1b9e-6a2d-4c57-9e3b-8d41a7f2c615"

    @Test
    fun `the link is the drive the phone's scanner adds`() {
        val link = ShareQr.link("N-DESKTOP", "pc-1", share)
        assertTrue(link.startsWith(FiletLink.WEB + "#"))
        val drive = DriveLink.of(FiletLink.parse(link)!!)!!
        assertEquals("N-DESKTOP · Documents", drive.name)
        assertEquals("pc-1", drive.deviceId)
        assertEquals(listOf("192.168.5.20", "192.168.4.13"), drive.hosts)
        assertEquals(8322, drive.port)
        assertEquals("a/tres9", drive.share)
        assertFalse(drive.tls)
    }

    /**
     * The window's own drawing, rendered offscreen and read back with a stock decoder.
     *
     * Bug identified here: an earlier version of this test drew its own bitmap at four pixels a
     * module and passed, while the window drew the same code at under three pixels a module,
     * smeared across pixel edges, and a screenshot of it did not decode. So this renders the
     * composable itself, on the panel colour around it, at the display scales Windows offers.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the QR the window draws reads back as its link at every common display scale`() {
        val link = ShareQr.link("DESKTOP-K7Q2M4N", worstId, worst)
        for (scale in listOf(1f, 1.25f, 1.5f)) {
            val side = ceil((240 + 32) * scale).toInt()
            val scene = ImageComposeScene(side, side, Density(scale)) {
                Box(Modifier.fillMaxSize().background(Ink.panel), contentAlignment = Alignment.Center) {
                    FiletQr(link, Modifier.size(240.dp))
                }
            }
            val bmp = try {
                Bitmap.makeFromImage(scene.render())
            } finally {
                scene.close()
            }
            val px = IntArray(side * side) { i -> bmp.getColor(i % side, i / side) }
            val read = QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px))),
                mapOf(DecodeHintType.TRY_HARDER to true),
            )
            assertEquals("at ${scale}x", link, read.text)
            assertNotNull(DriveLink.of(FiletLink.parse(read.text)!!))
        }
    }

    @Test
    fun `nothing to encode draws nothing`() {
        assertTrue(ShareQr.encode("").isEmpty())
    }
}
