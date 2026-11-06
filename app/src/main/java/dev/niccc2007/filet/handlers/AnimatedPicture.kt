package dev.niccc2007.filet.handlers

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.niccc2007.filet.vfs.types.FileTypes
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GIFs and animated WebPs, moving in the picture viewer.
 *
 * Bug identified here: the viewer decodes with `BitmapFactory`, which returns the first frame of
 * an animated file and nothing else, so every GIF opened as a still picture. `ImageDecoder`
 * (Android 9 and later) returns an `AnimatedImageDrawable` that decodes its frames as it plays,
 * and the viewer draws that while the picture is only being looked at.
 *
 * Editing still works on the first frame, as it always has. An edit cannot be written back into
 * an animation, and saving one makes a PNG - so the moment a picture is edited, turned or in the
 * tools, the still is what is on screen, and what is on screen is what would be saved.
 *
 * Lists and grids keep the first frame (see `Thumbnails`): a folder of GIFs all moving at once
 * costs battery and shows nothing a tap does not.
 */
internal object AnimatedPicture {

    /** Past this the file shows as its first frame: playing it means holding all of it. */
    const val MAX_BYTES = 48L * 1024 * 1024

    /**
     * Whether to try at all: a format that can move, on an Android that can play it, and a file
     * whose size is known and small enough to hold. An unknown size is not read whole on a guess.
     */
    fun wanted(extension: String, sdk: Int, size: Long): Boolean =
        sdk >= Build.VERSION_CODES.P && FileTypes.isAnimated(extension) && size in 1..MAX_BYTES

    /** Whether the moving picture is what belongs on screen now, rather than the still. */
    fun plays(edited: Boolean, turned: Boolean, inTools: Boolean): Boolean = !edited && !turned && !inTools

    /**
     * The animation, or null when the file turns out to hold one frame - `ImageDecoder` hands back
     * a plain still for that, and the bitmap already on screen is the same picture.
     *
     * Decoded no larger than [maxEdge] on its long side, the same ceiling the still uses, so a
     * huge GIF does not decode every frame at full size.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun decode(bytes: ByteArray, maxEdge: Int): Drawable? {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val edge = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (edge / sample > maxEdge) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        return drawable as? AnimatedImageDrawable
    }
}

/**
 * Draw [drawable], fitted and centred in the space it is given, a new frame each time it asks.
 *
 * Fitted the same way `Image` fits the still, so a `graphicsLayer` zoom or pan around this one
 * behaves exactly as it does around the bitmap.
 */
@Composable
internal fun AnimatedFrame(drawable: Drawable, modifier: Modifier = Modifier) {
    val frame = remember(drawable) { mutableIntStateOf(0) }
    // Held in `remember` because a Drawable keeps its callback only weakly. A callback made inside
    // the effect below would be collected, and the animation would stop on whichever frame it had
    // reached when that happened.
    val callback = remember(drawable) {
        val main = Handler(Looper.getMainLooper())
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame.intValue++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                main.postAtTime(what, who, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                main.removeCallbacks(what, who)
            }
        }
    }
    DisposableEffect(drawable) {
        drawable.callback = callback
        (drawable as? Animatable)?.start()
        onDispose {
            (drawable as? Animatable)?.stop()
            drawable.callback = null
        }
    }
    Canvas(modifier) {
        // Read in the draw phase, so a new frame redraws this and recomposes nothing.
        if (frame.intValue < 0) return@Canvas
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f) return@Canvas
        val fit = min(size.width / iw, size.height / ih)
        val w = iw * fit
        val h = ih * fit
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        drawable.setBounds(left.roundToInt(), top.roundToInt(), (left + w).roundToInt(), (top + h).roundToInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}
