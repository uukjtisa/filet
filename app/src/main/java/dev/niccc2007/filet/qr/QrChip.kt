package dev.niccc2007.filet.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A small "QR · Wi-Fi network" pill on a picture that holds a code, and nothing on one that
 * does not. Tapping it shows what the code is - see [QrDialog].
 *
 * ## When it looks
 *
 * After the picture has been on screen for a moment, not as it arrives: swiping through a
 * gallery should decode the picture somebody stops on, not every one they pass. The answer is
 * kept per file and version (path, size, time), so coming back to a picture costs nothing and a
 * picture that has none is not searched twice.
 */
@Composable
fun QrChip(node: VNode, vm: BrowserViewModel, modifier: Modifier = Modifier) {
    val key = node.path.toString() + "|" + node.size + "|" + node.mtime
    var found by remember(key) { mutableStateOf(QrCache.get(key)?.let(QrParse::parse)) }
    var open by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        if (QrCache.known(key)) return@LaunchedEffect
        delay(SETTLE_MS)
        val text = withContext(Dispatchers.Default) {
            // Stopped between attempts once the picture is swiped away: the answer would be
            // thrown out with this effect anyway.
            runCatching { QrDecode.fromStream({ vm.openRead(node) }, keepGoing = { isActive }) }.getOrNull()
        }
        QrCache.put(key, text)
        found = text?.let(QrParse::parse)
    }

    val content = found ?: return
    Text(
        "QR · " + content.kindLabel(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Filet.colors.accent.copy(alpha = 0.85f))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
    if (open) QrDialog(content, vm, fromPicture = true) { open = false }
}

private const val SETTLE_MS = 350L

/** What each picture held, by file and version. Null text is "looked, nothing there". */
private object QrCache {
    private const val CAP = 128
    private val map = object : LinkedHashMap<String, String?>(CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > CAP
    }

    @Synchronized fun known(key: String) = map.containsKey(key)
    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun put(key: String, text: String?) { map[key] = text }
}
