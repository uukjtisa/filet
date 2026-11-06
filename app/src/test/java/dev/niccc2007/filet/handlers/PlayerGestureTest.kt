package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.Scrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drag on the left for brightness, on the right for volume, sideways to seek.
 *
 * Also the bar coming back under a still finger, which made a sideways swipe a level drag.
 *
 * The side each control lives on is settled by convention rather than taste - MX Player, VLC,
 * mpv, Plex and YouTube on mobile all put brightness left and volume right - so the side is
 * asserted here and cannot be swapped by accident later.
 */
class PlayerGestureTest {

    private val W = 1080f
    private val H = 2400f

    // ── which control ──

    @Test
    fun `dragging up on the left is brightness`() {
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, PlayerGesture.drag(startX = 200f, dx = 0f, dy = -300f, width = W))
    }

    @Test
    fun `dragging up on the right is volume`() {
        assertEquals(PlayerGesture.Drag.VOLUME, PlayerGesture.drag(startX = 900f, dx = 0f, dy = -300f, width = W))
    }

    @Test
    fun `down works the same as up, on both sides`() {
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, PlayerGesture.drag(200f, 0f, 300f, W))
        assertEquals(PlayerGesture.Drag.VOLUME, PlayerGesture.drag(900f, 0f, 300f, W))
    }

    @Test
    fun `sideways is a seek from either side`() {
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(200f, 300f, 0f, W))
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(900f, -300f, 0f, W))
    }

    @Test
    fun `a sideways drag that starts with a wobble is still a seek`() {
        // The reported fault, as the numbers that caused it. The answer is decided once and held
        // for the rest of the gesture, and it used to be decided at 24px of travel - a few pixels
        // past the platform's own touch slop, where a horizontal drag carries several pixels of
        // vertical jitter and either axis can be the larger. One wobble and the whole drag became
        // a brightness or volume drag.
        assertEquals(PlayerGesture.Drag.NONE, PlayerGesture.drag(900f, 12f, 15f, W))
        // By the time it has gone far enough to mean anything, it is plainly sideways.
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(900f, 50f, 15f, W))
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(200f, -50f, 15f, W))
    }

    @Test
    fun `a level needs a drag that is clearly up or down`() {
        // Not quite vertical enough: it seeks, which is the harmless one.
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(900f, 50f, -55f, W))
        // Clearly vertical: it is the volume.
        assertEquals(PlayerGesture.Drag.VOLUME, PlayerGesture.drag(900f, 20f, -100f, W))
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, PlayerGesture.drag(200f, 20f, -100f, W))
    }

    @Test
    fun `nothing is ignored once it has travelled far enough`() {
        // Whatever the angle, a committed drag means something. A viewer that swallows a gesture
        // reads as a frozen picture.
        var angle = 0
        while (angle < 360) {
            val r = Math.toRadians(angle.toDouble())
            val dx = (120 * Math.cos(r)).toFloat()
            val dy = (120 * Math.sin(r)).toFloat()
            assertTrue("angle=$angle", PlayerGesture.drag(900f, dx, dy, W) != PlayerGesture.Drag.NONE)
            angle += 15
        }
    }

    @Test
    fun `a small movement is not a drag at all`() {
        // Otherwise a tap that wobbles changes the volume.
        assertEquals(PlayerGesture.Drag.NONE, PlayerGesture.drag(200f, 5f, 5f, W))
    }

    @Test
    fun `the side comes from where the finger went down, not where it is now`() {
        // A brightness drag that wanders across the middle stays a brightness drag.
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, PlayerGesture.drag(startX = 100f, dx = 700f, dy = -900f, width = W))
    }

    @Test
    fun `a tie goes to seeking`() {
        // Seeking is the reversible one: a wrong seek is undone by seeking back, a wrong
        // volume change is noticed only once it is too loud.
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(200f, 300f, 300f, W))
    }

    @Test
    fun `the exact middle counts as the right, and nothing falls through`() {
        assertEquals(PlayerGesture.Drag.VOLUME, PlayerGesture.drag(W / 2f, 0f, -300f, W))
        // Every position on the screen answers something, so no drag is ignored.
        var x = 0f
        while (x <= W) {
            assertTrue("x=$x", PlayerGesture.drag(x, 0f, -300f, W) != PlayerGesture.Drag.NONE)
            x += 60f
        }
    }

    // ── the level ──

    @Test
    fun `up raises and down lowers`() {
        val start = 0.5f
        assertTrue(PlayerGesture.levelAfter(start, dyPx = -200f, heightPx = H) > start)
        assertTrue(PlayerGesture.levelAfter(start, dyPx = 200f, heightPx = H) < start)
    }

    @Test
    fun `a full sweep covers the whole range`() {
        // From silent to full in one reachable gesture, rather than needing the whole screen.
        val full = H * PlayerGesture.SWEEP
        assertEquals(1f, PlayerGesture.levelAfter(0f, -full, H), 0.001f)
        assertEquals(0f, PlayerGesture.levelAfter(1f, full, H), 0.001f)
    }

    @Test
    fun `it clamps at both ends`() {
        assertEquals(1f, PlayerGesture.levelAfter(0.9f, -H * 5, H), 0.0001f)
        assertEquals(0f, PlayerGesture.levelAfter(0.1f, H * 5, H), 0.0001f)
    }

    @Test
    fun `no movement changes nothing`() {
        assertEquals(0.42f, PlayerGesture.levelAfter(0.42f, 0f, H), 0.0001f)
    }

    @Test
    fun `a zero height cannot divide by zero`() {
        assertEquals(0.5f, PlayerGesture.levelAfter(0.5f, -100f, 0f), 0.0001f)
    }

    @Test
    fun `the level is taken from the start of the drag, so it does not drift`() {
        // Travel is measured from where the drag began. Accumulating per-frame deltas drifts,
        // and drift in a volume control shows up as "it never quite reaches the top".
        val start = 0.2f
        val once = PlayerGesture.levelAfter(start, -600f, H)
        // The same journey in ten steps must land in the same place.
        var total = 0f
        repeat(10) { total += -60f }
        assertEquals(once, PlayerGesture.levelAfter(start, total, H), 0.0001f)
    }

    // ── system volume steps ──

    @Test
    fun `the top of the drag reaches maximum volume`() {
        // Truncating instead of rounding leaves the last step unreachable, which reads as a
        // player that will not go loud enough.
        assertEquals(15, PlayerGesture.volumeSteps(1f, 15))
        assertEquals(0, PlayerGesture.volumeSteps(0f, 15))
    }

    @Test
    fun `steps and levels round trip`() {
        for (s in 0..15) {
            assertEquals(s, PlayerGesture.volumeSteps(PlayerGesture.volumeLevel(s, 15), 15))
        }
    }

    @Test
    fun `a device reporting no volume steps does not crash`() {
        assertEquals(0, PlayerGesture.volumeSteps(0.5f, 0))
        assertEquals(0f, PlayerGesture.volumeLevel(3, 0), 0.0001f)
    }

    // ── seeking by drag ──

    @Test
    fun `dragging right goes forward and left goes back`() {
        val d = 300_000L
        assertTrue(PlayerGesture.seekAfter(100_000L, 300f, W, d) > 100_000L)
        assertTrue(PlayerGesture.seekAfter(100_000L, -300f, W, d) < 100_000L)
    }

    @Test
    fun `a seek drag is relative to where it started`() {
        // Unlike the progress bar, where the position under the finger is the position. The
        // picture is a nudge from here; the bar is a jump to there.
        val d = 300_000L
        assertEquals(
            PlayerGesture.seekAfter(10_000L, 200f, W, d) - 10_000L,
            PlayerGesture.seekAfter(200_000L, 200f, W, d) - 200_000L,
        )
    }

    @Test
    fun `a short clip is covered once by a drag the width of the bar`() {
        val short = 30_000L
        assertEquals(short, PlayerGesture.seekAfter(0L, W, W, short))
    }

    @Test
    fun `a drag on the picture moves at the progress bar's pace`() {
        // The bar's own arithmetic at its normal speed: any drift between the two is a picture
        // drag that lags, or outruns, the bar under the same finger.
        val durations = listOf(30_000L, 4 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L)
        val moves = listOf(1f, 37f, 250f, -180f, 900f)
        for (d in durations) for (dx in moves) {
            val start = d / 3
            val bar = Scrub.barMove(start.toDouble(), dx, W, d, pullDp = 0f, pxPerDp = 2.75f)!!.toLong()
            assertEquals("duration $d, dx $dx", bar, PlayerGesture.seekAfter(start, dx, W, d))
        }
    }

    @Test
    fun `one bar's width covers the whole of an hour or two hours`() {
        for (d in listOf(60 * 60_000L, 2 * 60 * 60_000L)) {
            assertEquals(d, PlayerGesture.seekAfter(0L, W, W, d))
            assertEquals(0L, PlayerGesture.seekAfter(d, -W, W, d))
            assertEquals(d / 2, PlayerGesture.seekAfter(0L, W / 2, W, d))
        }
    }

    @Test
    fun `a long video is not capped at ninety seconds a sweep`() {
        // The old pace: a quarter of the width on an hour-long video moved 22.5 seconds.
        val hour = 60 * 60_000L
        assertEquals(15 * 60_000L, PlayerGesture.seekAfter(0L, W / 4, W, hour))
    }

    @Test
    fun `the pace comes from the bar's measured width when it is this screen's bar`() {
        // 28 dp of padding at 2.75 px per dp: the bar is 77 px narrower than the picture.
        assertEquals(1003f, PlayerGesture.seekTrackPx(1080f, 1003f), 0f)
        assertEquals("landscape, side navigation bar", 2180f, PlayerGesture.seekTrackPx(2400f, 2180f), 0f)
    }

    @Test
    fun `an unmeasured or stale bar falls back to the picture's width`() {
        assertEquals("never drawn", 1080f, PlayerGesture.seekTrackPx(1080f, 0f), 0f)
        assertEquals("landscape's bar after turning to portrait", 1080f, PlayerGesture.seekTrackPx(1080f, 2323f), 0f)
        assertEquals("portrait's bar after turning to landscape", 2400f, PlayerGesture.seekTrackPx(2400f, 1003f), 0f)
    }

    @Test
    fun `it never seeks outside the video`() {
        val d = 60_000L
        assertEquals(d, PlayerGesture.seekAfter(50_000L, W * 10, W, d))
        assertEquals(0L, PlayerGesture.seekAfter(10_000L, -W * 10, W, d))
    }

    @Test
    fun `an unknown duration seeks nowhere`() {
        assertEquals(5_000L, PlayerGesture.seekAfter(5_000L, 500f, W, durationMs = 0L))
    }

    @Test
    fun `a sideways swipe stays a seek when the bar comes back and moves the surface under it`() {
        val width = 1080f
        // The finger: straight across, 20 px an event, not a pixel up or down.
        val fingerX = listOf(500f, 520f, 540f, 560f, 580f, 600f)
        val fingerY = List(fingerX.size) { 1000f }
        // The surface: the top bar comes back on the second event and pushes it down 150 px.
        val surfaceTop = listOf(0f, 0f, 150f, 150f, 150f, 150f)
        val localY = fingerY.indices.map { fingerY[it] - surfaceTop[it] }

        // The old reading, where it is now minus where it started, in the moving surface's terms:
        // a clear upward drag on the left, which is brightness, going up.
        val absolute = PlayerGesture.drag(fingerX[0], fingerX.last() - fingerX[0], localY.last() - localY[0], width)
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, absolute)

        // The platform's per-event movement maps both positions through the layout of that
        // moment, so the surface moving is not in it. Summed, it is the finger's own travel.
        val travel = PlayerGesture.Travel()
        for (i in 1 until fingerX.size) travel.add(fingerX[i] - fingerX[i - 1], fingerY[i] - fingerY[i - 1])
        assertEquals(100f, travel.dx, 0f)
        assertEquals(0f, travel.dy, 0f)
        assertEquals(PlayerGesture.Drag.SEEK, PlayerGesture.drag(fingerX[0], travel.dx, travel.dy, width))

        // And a real vertical drag is still one.
        travel.reset()
        repeat(6) { travel.add(2f, -20f) }
        assertEquals(PlayerGesture.Drag.BRIGHTNESS, PlayerGesture.drag(fingerX[0], travel.dx, travel.dy, width))
    }
}
