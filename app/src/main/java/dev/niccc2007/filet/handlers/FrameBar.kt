package dev.niccc2007.filet.handlers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.log.FiletLog
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether the frame bar is up, and its step.
 *
 * Up until it is switched off in the sound sheet: it does not go with the chrome, and it stays
 * from one video to the next. Not saved, though. It lasts for the app's run, for the reason the
 * mute does not persist: a bar left on from last week's frame hunt is a player that looks
 * different tomorrow for no reason anybody remembers.
 */
internal object FrameBarState {
    var on by mutableStateOf(false)
    var step by mutableIntStateOf(1)
}

/**
 * Frame by frame: a bar along the bottom of the video, under the picture rather than over it.
 *
 * Its controls used to be in the sound sheet, which is a dialogue: its scrim dimmed the video
 * and its card covered most of it, so a step was a press on a frame nobody could see. Now the
 * sheet only switches this on.
 *
 * - 1 · 2 · 5 · 10: how many frames a step is.
 * - ◀ ▶: a step. Each pauses, because a step is a look at one picture. Held, they keep
 *   stepping at a pace each frame can be seen at ([FrameStep.HOLD_EVERY_MS]), and never faster
 *   than the seeks land ([settle]), so a hold cannot queue up steps that arrive later.
 * - ◀◀ ▶▶: a second either way, to the frame nearest it. They leave playing as it is.
 * - Play and pause, so the bar does not need the chrome brought back to carry on.
 * - Where it is: `#1234 · 0:41.233`.
 *
 * The step buttons stay while the video plays. A step then pauses it on the frame it lands on,
 * which is the "stop there" a hunt for one moment wants.
 *
 * Two rows on a phone held upright, one on a wide screen.
 *
 * @param reading how much of the frame list is read, 0 to 1, or null once it is in.
 * @param onStep [FrameStep] frames, signed. `first` is the press's own step, not a repeat.
 * @param settle returns once the last step has landed, or after a bounded wait.
 */
@Composable
internal fun FrameBar(
    readout: String,
    reading: Float?,
    playing: Boolean,
    onStep: suspend (frames: Int, first: Boolean) -> Unit,
    onJump: suspend (direction: Int) -> Unit,
    settle: suspend () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = FrameBarState.step
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            // The bottom edge is the system's gesture strip: see the chrome's note on it.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val wide = maxWidth >= WIDE
        val transport: @Composable () -> Unit = {
            HoldButton(JumpBack, "Back one second", "1 s", settle) { onJump(-1) }
            HoldButton(StepBack, framesLabel("Back", step), "−$step", settle) { first -> onStep(-step, first) }
            PlayButton(playing, onToggle)
            HoldButton(StepOn, framesLabel("On", step), "+$step", settle) { first -> onStep(step, first) }
            HoldButton(JumpOn, "On one second", "1 s", settle) { onJump(1) }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepSizes(step)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    transport()
                }
                Spacer(Modifier.weight(1f))
                Readout(readout, reading)
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepSizes(step)
                    Spacer(Modifier.weight(1f))
                    Readout(readout, reading)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    transport()
                }
            }
        }
    }
}

/**
 * Past this the bar fits on one row: the sizes, the transport and the longest readout come to
 * about 650 dp. A phone on its side is 900 and up, held upright about 420.
 */
private val WIDE = 680.dp

private fun framesLabel(way: String, n: Int): String = "$way $n " + if (n == 1) "frame" else "frames"

@Composable
private fun StepSizes(step: Int) {
    val accent = Filet.colors.accent
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (n in FrameStep.SIZES) {
            val on = n == step
            Box(
                Modifier
                    .size(width = 34.dp, height = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) accent else Color.White.copy(alpha = 0.12f))
                    .clickable { FrameBarState.step = n }
                    .semantics {
                        selected = on
                        contentDescription = (if (n == 1) "1 frame" else "$n frames") + " a step"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    color = if (on) onAccent else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            "frames",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Readout(readout: String, reading: Float?) {
    Column(horizontalAlignment = Alignment.End) {
        Text(readout, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        // While the list is read the number is the rate's estimate, and the ≈ says so; this
        // says why, and that it will not stay one.
        if (reading != null) {
            Text(
                "reading frames ${(reading.coerceIn(0f, 1f) * 100).roundToInt()}%",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A step button that keeps stepping while held.
 *
 * The press's own step runs even if the finger is already up by the time it is under way: a tap
 * is shorter than the picture copy a first step may wait on, and a tap that did nothing would be
 * the worst of it.
 */
@Composable
private fun HoldButton(
    icon: ImageVector,
    label: String,
    caption: String,
    settle: suspend () -> Unit,
    onFire: suspend (first: Boolean) -> Unit,
) {
    val fire by rememberUpdatedState(onFire)
    val wait by rememberUpdatedState(settle)
    val scope = rememberCoroutineScope()
    var down by remember { mutableStateOf(false) }
    Column(
        Modifier
            .size(width = 52.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (down) 0.26f else 0.12f))
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) {
                    scope.launch { fire(true) }
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    val run = scope.launch {
                        withContext(NonCancellable) { fire(true) }
                        var repeat = 0
                        while (isActive) {
                            val since = SystemClock.uptimeMillis()
                            wait()
                            delay((FrameStep.holdDelay(repeat) - (SystemClock.uptimeMillis() - since)).coerceAtLeast(0L))
                            fire(false)
                            repeat++
                        }
                    }
                    tryAwaitRelease()
                    run.cancel()
                    down = false
                })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(caption, color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PlayButton(playing: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) FiletIcons.Pause else FiletIcons.PlaySolid,
            if (playing) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

// The bar's own glyphs. One triangle is a step and two are a jump; `FiletIcons.Prev` and `Next`,
// a triangle against a bar, already mean the track before and after.
private fun glyph(name: String, vararg d: String): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (spec in d) b.addPath(pathData = PathParser().parsePathString(spec).toNodes(), fill = SolidColor(Color.White))
    return b.build()
}

private val StepBack = glyph("stepback", "M16.5 5.5v13L7 12z")
private val StepOn = glyph("stepon", "M7.5 5.5v13L17 12z")
private val JumpBack = glyph("jumpback", "M11.5 5.5v13L3 12z", "M21 5.5v13L12.5 12z")
private val JumpOn = glyph("jumpon", "M3 5.5v13l8.5-6.5z", "M12.5 5.5v13L21 12z")

/**
 * Every frame's time in [uri]'s video, read off the container. No frame is decoded.
 *
 * Read with the extractor the platform's own player reads the file with, so the times are the
 * ones it seeks to and reports. It reads the video track's bytes once, which on a long film is
 * some seconds, hence [onProgress] and the estimate meanwhile. Null when the file has no video
 * track or will not be read.
 */
internal suspend fun readFrames(context: Context, uri: Uri, onProgress: (Float) -> Unit): FrameList? =
    withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@withContext null
            val format = ex.getTrackFormat(track)
            val lengthUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(track)
            var times = LongArray(4096)
            var n = 0
            while (true) {
                ensureActive()
                val t = ex.sampleTime
                if (t < 0) break
                if (n == times.size) times = times.copyOf(n * 2)
                times[n++] = t
                if (n % 256 == 0 && lengthUs > 0) onProgress((t.toFloat() / lengthUs).coerceIn(0f, 1f))
                if (!ex.advance()) break
            }
            FrameList.of(times, n)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FiletLog.w("frames", "the frame list could not be read; steps stay on the rate's estimate", e)
            null
        } finally {
            runCatching { ex.release() }
        }
    }

private const val PRINT_W = 32
private const val PRINT_H = 18

/**
 * What the surface shows now, very small and in grey, for telling whether a paused seek drew
 * anything. Only the log uses it. Null where the surface cannot be read.
 */
internal suspend fun surfacePrint(view: SurfaceView): IntArray? {
    if (!view.holder.surface.isValid) return null
    // Not recycled: a cancelled wait can leave the copy still writing into it.
    val bitmap = Bitmap.createBitmap(PRINT_W, PRINT_H, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { c ->
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val px = IntArray(PRINT_W * PRINT_H)
                    bitmap.getPixels(px, 0, PRINT_W, 0, 0, PRINT_W, PRINT_H)
                    c.resume(IntArray(px.size) { i ->
                        val p = px[i]
                        (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                    })
                } else {
                    c.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            c.resume(null)
        }
    }
}

/** How far apart two prints are, as the mean grey difference out of 255; null if either is missing. */
internal fun printChange(a: IntArray?, b: IntArray?): Float? {
    if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
    var sum = 0L
    for (i in a.indices) sum += abs(a[i] - b[i])
    return sum.toFloat() / a.size
}
