package dev.niccc2007.filet.playback

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The light the artwork casts on the player, and the colour it lends the controls.
 *
 * ## Why the background is the artwork and not a gradient somebody chose
 *
 * A fixed gradient is the wrong colour for every track but one. The artwork, blurred, is the
 * right colour for all of them by construction - it IS the colour of the thing playing - and
 * it costs one more draw of a bitmap that is already decoded. Every music app does this and
 * that is the reason.
 *
 * ## Why it is three layers
 *
 * One blurred copy of a dark track is a dark rectangle and produces no colour at all, which
 * is exactly what the first attempt looked like. So: a wide copy with its saturation pushed
 * hard, so what colour exists survives the blur; a second tighter copy behind the artwork
 * for a hot spot; and a veil that starts late and arrives gently, whose only job is to put
 * ground under the text at the bottom. The brief after seeing it on the phone was **spread,
 * not stronger** - so the reach is wide and the opacity is low.
 */
@Composable
fun AmbientBackdrop(art: Bitmap?, modifier: Modifier = Modifier) {
    if (art == null) return
    val image = art.asImageBitmap()
    // Saturation is applied with a colour matrix rather than by generating a second bitmap.
    // The blur radius does the spreading; the matrix does the colour.
    val lift = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(SATURATION) })
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = lift,
            alpha = WIDE_ALPHA,
            modifier = Modifier
                .fillMaxSize()
                // Scaled past the edges BEFORE the blur, so the blur has image to work with
                // at the borders instead of fading into nothing.
                .scale(WIDE_SCALE)
                .blur(WIDE_BLUR),
        )
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = lift,
            alpha = HOT_ALPHA,
            modifier = Modifier
                .fillMaxSize()
                .scale(HOT_SCALE)
                .blur(HOT_BLUR)
                // Faded out towards the bottom so the hot spot sits behind the artwork and
                // does not fight the text.
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                    )
                },
        )
    }
}

/**
 * The veil: ground under the text, and nothing else.
 *
 * Starts at [VEIL_START] rather than at the top, because a veil that begins immediately dims
 * the colour in the same breath as making it - which is what the pass that turned every dial
 * up was actually fighting.
 */
@Composable
fun AmbientVeil(ground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().drawWithContent {
            drawRect(
                Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    VEIL_START to Color.Transparent,
                    0.76f to ground.copy(alpha = 0.38f),
                    1.00f to ground.copy(alpha = 0.88f),
                ),
            )
        },
    )
}

/**
 * The artwork's colour, sampled off the main thread, with the theme accent as the answer
 * whenever the artwork has none to give.
 *
 * Keyed on the bitmap, so it is computed once per track rather than per recomposition - and
 * the player recomposes five times a second off the position ticker.
 */
@Composable
fun rememberArtPalette(art: Bitmap?, fallback: Color, onFallback: Color): Pair<Color, Color> {
    val result by produceState(ArtPalette.NONE, art) {
        value = if (art == null) {
            ArtPalette.NONE
        } else {
            withContext(Dispatchers.Default) {
                // A 24x24 is plenty: this is looking for the dominant hue, not for detail,
                // and it keeps the whole pass to a few hundred microseconds.
                val small = runCatching { Bitmap.createScaledBitmap(art, 24, 24, true) }.getOrNull()
                if (small == null) {
                    ArtPalette.NONE
                } else {
                    val px = IntArray(24 * 24)
                    small.getPixels(px, 0, 24, 0, 0, 24, 24)
                    if (small !== art) small.recycle()
                    ArtPalette.of(IntArray(px.size) { px[it] and 0xFFFFFF })
                }
            }
        }
    }
    val r = result
    return if (!r.usable) fallback to onFallback
    else Color(0xFF000000.toInt() or r.colour) to Color(0xFF000000.toInt() or r.onColour)
}

/**
 * How far a title has to travel, and the clock that moves it.
 *
 * Returns 0 when the title fits, which is the signal to draw it plainly: a marquee that runs
 * on a title that fits is motion for its own sake, and the fade that comes with it would clip
 * the first letter of a name that was never in trouble.
 */
@Composable
fun rememberMarquee(overflowPx: Float): State<Float> {
    if (overflowPx <= 2f) return remember { mutableFloatStateOf(0f) }
    val clock = rememberInfiniteTransition(label = "marquee")
    val t = clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Holds at each end before turning round. A marquee that reverses the instant it
            // arrives is unreadable at exactly the moment the end of the name is visible,
            // which is the only reason it is moving.
            animation = keyframes {
                durationMillis = CYCLE_MS
                0f at 0 using LinearEasing
                0f at CYCLE_MS / 10 using LinearEasing
                1f at CYCLE_MS / 2 using LinearEasing
                1f at CYCLE_MS * 6 / 10 using LinearEasing
                0f at CYCLE_MS
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "marquee",
    )
    // Derived rather than computed here, so the frame value is read by whoever draws with it
    // instead of by whoever composed it. See the note on `MarqueeTitle`'s graphicsLayer.
    return remember(overflowPx, t) { derivedStateOf { -overflowPx * t.value } }
}

/**
 * The two-sided fade a running marquee is drawn behind.
 *
 * **`CompositingStrategy.Offscreen` is not optional here.** A `DstIn` blend inside
 * `drawWithContent` composites against whatever the canvas already holds, and without an
 * offscreen layer that canvas is the whole screen - so instead of masking the text it
 * punched two black rectangles into the background at either end of the title. Exactly the
 * same trap as the About card's sheen, which was drawn with `SrcAtop` and produced a
 * rectangle for the same reason.
 *
 * The layer makes the content its own destination, so the gradient masks the text and
 * nothing else.
 */
fun Modifier.marqueeFade(running: Boolean): Modifier =
    if (!running) this
    else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.07f to Color.Black,
                    0.93f to Color.Black,
                    1.00f to Color.Transparent,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

/*
 * Spread, not intensity - and the numbers that worked in the browser were wrong here.
 *
 * The mock's values were tuned against a CSS blur over an HTML background. Compose's blur is
 * a render effect on a layer and `ContentScale.Crop` under a 1.9x scale magnifies the frame,
 * so the same alpha lands far hotter: on the phone it read as a wall of colour where the mock
 * read as a lit room. Saturation and both alphas come down, the blur radii and the scales go
 * up. The brief has not changed - the colour should reach the edges and stay faint.
 */
private const val SATURATION = 2.2f
private const val WIDE_ALPHA = 0.34f
private const val HOT_ALPHA = 0.17f
private const val WIDE_SCALE = 2.4f
private const val HOT_SCALE = 1.8f
private val WIDE_BLUR = 150.dp
private val HOT_BLUR = 110.dp

/** The colour is allowed to travel this far down before the veil begins. */
private const val VEIL_START = 0.46f

/** One full out-and-back. Slow enough to read, quick enough not to feel stuck. */
private const val CYCLE_MS = 14_000
