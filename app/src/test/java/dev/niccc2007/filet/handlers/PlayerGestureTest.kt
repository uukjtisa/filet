package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drag on the left for brightness, on the right for volume, sideways to seek.
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
    fun `a short clip is not scrubbed faster than it is long`() {
        // A full-width drag on a 30s clip must not try to cover 90 seconds.
        val short = 30_000L
        assertEquals(short, PlayerGesture.seekAfter(0L, W, W, short))
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
}
