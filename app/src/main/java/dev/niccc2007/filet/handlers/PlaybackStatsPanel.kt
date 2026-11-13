package dev.niccc2007.filet.handlers

import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Whether the Playback stats readout is up.
 *
 * Decision: held for the session and not saved, so it is off at every cold start. It is a tool for
 * looking into one file that plays badly, as VLC's statistics view is; left on by a saved setting
 * it would be a box of numbers over every film from then on.
 */
internal object StatsReadout {
    var on by mutableStateOf(false)
}

/**
 * The readout: what [VideoEngine.stats] says, as rates, refreshed once a second. Over the picture
 * and never in the way of a touch - it has no click handler, so every gesture goes through to the
 * player under it. [key] restarts the count when the engine is replaced, since one engine's totals
 * mean nothing beside the next one's.
 */
@Composable
internal fun PlaybackStatsPanel(key: Any, engine: () -> VideoEngine?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val read by rememberUpdatedState(engine)
    var lines by remember(key) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(key) {
        val power = context.getSystemService(PowerManager::class.java)
        var before: PlaybackStats.Snapshot? = null
        while (isActive) {
            val e = read()
            val now = e?.stats()
            lines = if (now == null) {
                listOf("Picture" to "this player keeps no figures")
            } else {
                val hz = runCatching { e.surface.display?.refreshRate }.getOrNull()
                val heat = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { power?.currentThermalStatus }.getOrNull()?.let { PlaybackStats.heat(it) }
                } else {
                    null
                }
                PlaybackStats.lines(now, PlaybackStats.rates(before, now), hz, heat)
            }
            before = now
            delay(TICK_MS)
        }
    }
    if (lines.isEmpty()) return
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((label, value) in lines) {
            Row {
                Text(
                    label,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.width(66.dp),
                )
                Text(value, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color.White)
            }
        }
    }
}

/** A second: long enough for a rate to mean something, short enough to follow a scene change. */
private const val TICK_MS = 1_000L
