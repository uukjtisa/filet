package dev.niccc2007.filet.playback

import dev.niccc2007.filet.handlers.SPEED_STOPS
import dev.niccc2007.filet.handlers.snapSpeed
import dev.niccc2007.filet.handlers.speedAt
import dev.niccc2007.filet.handlers.speedFraction
import dev.niccc2007.filet.handlers.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speed slider's arithmetic, and what the notification says. */
class SpeedAndNoticeTest {

    // ── the rail ──────────────────────────────────────────────────────────────

    @Test
    fun `a thumb near a stop lands exactly on it`() {
        // 1.03x is nobody's intention and it is audible. A rail that cannot land on 1.0 is a
        // rail you have to undo.
        assertEquals(1f, snapSpeed(1.01f), 0f)
        assertEquals(1f, snapSpeed(0.99f), 0f)
        assertEquals(2f, snapSpeed(2.02f), 0f)
        assertEquals(0.5f, snapSpeed(0.501f), 0f)
    }

    @Test
    fun `away from a stop the grain is proportional, not fixed`() {
        // A twentieth is the right step at 1x and a fifth of the whole value at 0.2x.
        assertEquals(1.15f, snapSpeed(1.16f), 0.001f)
        assertEquals(0.17f, snapSpeed(0.172f), 0.001f)
    }

    @Test
    fun `it never leaves the range, however far the finger goes`() {
        for (raw in listOf(-4f, 0f, 0.001f, 99f, 1000f)) {
            val v = snapSpeed(raw)
            assertTrue("$raw -> $v", v in MIN_SPEED..MAX_SPEED)
        }
    }

    @Test
    fun `every stop is reachable and is its own fixed point`() {
        for (p in SPEED_STOPS) {
            assertEquals(p, snapSpeed(p), 0f)
            assertTrue("$p out of range", p in MIN_SPEED..MAX_SPEED)
        }
    }

    @Test
    fun `the stops are in order and include one times`() {
        assertEquals(SPEED_STOPS.sorted(), SPEED_STOPS)
        assertTrue(SPEED_STOPS.contains(1f))
    }

    @Test
    fun `a lowered ceiling takes the stops above it with it`() {
        // The player walks its ceiling down when a device refuses a rate. A rail that still
        // offered 4x there would be a position that plays nothing.
        assertEquals(2f, snapSpeed(3.5f, maxSpeed = 2f), 0f)
        assertEquals(1f, speedFraction(2f, 2f), 0.001f)
    }

    // ── the log scale ─────────────────────────────────────────────────────────

    @Test
    fun `the ends of the rail are the ends of the range`() {
        assertEquals(0f, speedFraction(MIN_SPEED, MAX_SPEED), 0.0001f)
        assertEquals(1f, speedFraction(MAX_SPEED, MAX_SPEED), 0.0001f)
        assertEquals(MIN_SPEED, speedAt(0f, MAX_SPEED), 0.0001f)
        assertEquals(MAX_SPEED, speedAt(1f, MAX_SPEED), 0.0001f)
    }

    @Test
    fun `position and speed are inverses of each other`() {
        for (f in listOf(0f, 0.17f, 0.5f, 0.624f, 0.9f, 1f)) {
            val back = speedFraction(speedAt(f, MAX_SPEED), MAX_SPEED)
            assertEquals("f=$f", f, back, 0.001f)
        }
    }

    @Test
    fun `every doubling gets the same travel`() {
        // The whole reason the rail is logarithmic: linearly, 1.0x sits at a quarter of the
        // travel and half the rail is spent between 2x and 4x, where nobody needs resolution.
        val a = speedFraction(1f, MAX_SPEED) - speedFraction(0.5f, MAX_SPEED)
        val b = speedFraction(2f, MAX_SPEED) - speedFraction(1f, MAX_SPEED)
        val c = speedFraction(4f, MAX_SPEED) - speedFraction(2f, MAX_SPEED)
        assertEquals(a, b, 0.001f)
        assertEquals(b, c, 0.001f)
    }

    @Test
    fun `one times is past the middle, which is where a thumb expects it`() {
        val f = speedFraction(1f, MAX_SPEED)
        assertTrue("$f", f in 0.55f..0.70f)
    }

    // ── what happens to the pitch ─────────────────────────────────────────────

    @Test
    fun `hold always holds and follow always follows`() {
        for (v in listOf(0.2f, 0.6f, 1f, 2f)) {
            assertEquals(1f, PitchMode.HOLD.pitchFor(v), 0f)
            assertEquals(v, PitchMode.FOLLOW.pitchFor(v), 0f)
        }
    }

    @Test
    fun `auto holds the pitch while the stretcher is good at it`() {
        // Above the floor there is nothing to fix: the stretcher is transparent there.
        for (v in listOf(0.85f, 0.9f, 1f, 1.5f, 3f)) {
            assertEquals("$v", 1f, PitchMode.AUTO.pitchFor(v), 0f)
        }
    }

    @Test
    fun `auto splits the cost instead of paying one of them in full`() {
        // The first version let the pitch follow outright below the floor, which traded one
        // unintelligible thing for another: at 0.5x that is an octave down, and a voice an
        // octave down is no easier to follow than a warbling one. Two semitones at 0.6x is
        // what a voice survives.
        val at6 = PitchMode.AUTO.pitchFor(0.6f)
        assertTrue("$at6", at6 in 0.86f..0.91f)
        val at5 = PitchMode.AUTO.pitchFor(0.5f)
        assertTrue("$at5", at5 in 0.81f..0.86f)
        // And the stretcher is left with something it can actually do.
        assertTrue("stretch ${0.6f / at6}", 0.6f / at6 > 0.65f)
    }

    @Test
    fun `auto never sinks past the floor, however slow it gets`() {
        // Past about five semitones a voice stops being a voice whatever the stretcher is
        // doing, so Automatic stops sinking and accepts the warble instead.
        for (v in listOf(0.3f, 0.2f, 0.1f)) {
            assertTrue("$v", PitchMode.AUTO.pitchFor(v) >= PitchMode.PITCH_FLOOR - 0.0001f)
        }
    }

    @Test
    fun `auto never raises the pitch`() {
        for (v in listOf(0.1f, 0.3f, 0.6f, 0.84f, 0.85f, 1f, 2f, 4f)) {
            assertTrue("$v", PitchMode.AUTO.pitchFor(v) <= 1f + 0.0001f)
        }
    }

    @Test
    fun `auto slides rather than stepping`() {
        // A step at the crossover would drop the voice a whole interval the instant the
        // slider passed a line nobody can see, which reads as a glitch rather than a mode.
        var last = 1f
        var v = PitchMode.HOLD_FLOOR
        while (v > 0.1f) {
            val p = PitchMode.AUTO.pitchFor(v)
            assertTrue("jump at $v: $last -> $p", last - p < 0.02f)
            assertTrue("not monotonic at $v", p <= last + 0.0001f)
            last = p
            v -= 0.01f
        }
    }

    @Test
    fun `auto is the default, because it is the one that is never unintelligible`() {
        assertEquals(PitchMode.AUTO, PitchMode.DEFAULT)
        assertEquals(PitchMode.AUTO, PitchMode.of(null))
        assertEquals(PitchMode.AUTO, PitchMode.of("nonsense"))
        for (m in PitchMode.entries) assertEquals(m, PitchMode.of(m.id))
    }

    // ── what the shade says ───────────────────────────────────────────────────

    private fun track(
        id: String = "local:///storage/emulated/0/Music/one.mp3",
        artist: String? = null,
        album: String? = null,
    ) = Track(id = id, name = "one.mp3", artist = artist, album = album)

    @Test
    fun `line two is the artist when there is one`() {
        val s = NowPlaying(queue = listOf(track(artist = "Beach House")), active = true)
        assertEquals("Beach House", PlaybackNotice.line2(s))
    }

    @Test
    fun `line two carries the album beside the artist`() {
        val s = NowPlaying(
            queue = listOf(track(artist = "Beach House", album = "Depression Cherry")),
            active = true,
        )
        assertTrue(PlaybackNotice.line2(s).startsWith("Beach House"))
        assertTrue(PlaybackNotice.line2(s).endsWith("Depression Cherry"))
    }

    @Test
    fun `line two falls back to the folder, not to the filename`() {
        // No tags is the common case on a phone full of downloads, and the filename is
        // already the title line - repeating it there says nothing twice.
        val s = NowPlaying(queue = listOf(track()), active = true)
        assertEquals("Music", PlaybackNotice.line2(s))
    }

    @Test
    fun `line three answers how far through and what is on`() {
        val s = NowPlaying(
            queue = List(37) { track(id = "local:///m/$it.mp3") },
            index = 3,
            repeat = Repeat.ALL,
            active = true,
        )
        val line = PlaybackNotice.line3(s)
        assertTrue(line, line.contains("4 of 37"))
        assertTrue(line, line.contains("Repeating the queue"))
    }

    @Test
    fun `line three says nothing when there is nothing to say`() {
        // One track, no repeat, no shuffle: a sub-text saying "Repeat off" would be true of
        // nearly every track nearly all of the time.
        val s = NowPlaying(queue = listOf(track()), active = true)
        assertEquals("", PlaybackNotice.line3(s))
    }

    @Test
    fun `line three names repeat-one apart from repeat-queue`() {
        val one = NowPlaying(queue = listOf(track()), repeat = Repeat.ONE, active = true)
        assertEquals("Repeating this track", PlaybackNotice.line3(one))
    }

    @Test
    fun `nothing playing produces empty lines rather than a crash`() {
        val empty = NowPlaying()
        assertEquals("", PlaybackNotice.line2(empty))
        assertEquals("", PlaybackNotice.line3(empty))
    }

    // ── the shade's ground ────────────────────────────────────────────────────

    @Test
    fun `the notification ground is dark enough for white text on every hue`() {
        // Reported as "the notification card is too bright". The control colour is lightened
        // until it clears 4.5:1 against a dark panel, which is right for a 68dp button and is
        // a slab of neon across a whole card. Every hue has to come back dark, or the system
        // flips its text colour between tracks.
        for (hue in 0 until 360 step 15) {
            val control = ArtPalette.of(IntArray(64) { hueSample(hue) })
            if (!control.usable) continue
            val ground = ArtPalette.ground(control)
            assertTrue(
                "hue $hue -> ${ground.toString(16)}",
                ArtPalette.luminance(ground) < 0.22,
            )
            assertTrue(
                "hue $hue is unreadable",
                ArtPalette.contrast(ground, 0xFFFFFF) >= 4.5,
            )
        }
    }

    @Test
    fun `the ground is dimmer than the control it came from`() {
        val control = ArtPalette.of(IntArray(64) { hueSample(140) })
        assertTrue(control.usable)
        assertTrue(
            ArtPalette.luminance(ArtPalette.ground(control)) <
                ArtPalette.luminance(control.colour),
        )
    }

    @Test
    fun `an unusable sample leaves the card alone`() {
        assertEquals(0, ArtPalette.ground(ArtPalette.NONE))
    }

    /** A mid-lightness, strongly saturated pixel of one hue, which is what artwork gives. */
    private fun hueSample(hue: Int): Int {
        val c = 0.7
        val x = c * (1 - kotlin.math.abs((hue / 60.0) % 2 - 1))
        val m = 0.5 - c / 2
        val (r, g, b) = when {
            hue < 60 -> Triple(c, x, 0.0)
            hue < 120 -> Triple(x, c, 0.0)
            hue < 180 -> Triple(0.0, c, x)
            hue < 240 -> Triple(0.0, x, c)
            hue < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun byte(v: Double) = (((v + m) * 255).toInt()).coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    // ── the preset labels ─────────────────────────────────────────────────────

    @Test
    fun `a speed is spelled as short as it can honestly be`() {
        // It was "%.2f" with a `.replace("0x", "x")` behind it, which turned 2.00 into 2 by
        // deleting a zero it happened to contain - and would have mangled 1.05.
        assertEquals("1×", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1.05×", speedLabel(1.05f))
    }

    // ── where the queue came from ─────────────────────────────────────────────

    @Test
    fun `the emulated-storage bookkeeping is not a folder name`() {
        // "/storage/emulated/0/Music" rendered as "0 / Music", which names a directory
        // nobody has ever typed and reads as a bug.
        assertEquals(
            "Music",
            dev.niccc2007.filet.handlers.folderContext(
                dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/0/Music/a.mp3"),
            ),
        )
    }

    @Test
    fun `a second user id is a number too, not the literal zero`() {
        assertEquals(
            "Music",
            dev.niccc2007.filet.handlers.folderContext(
                dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/11/Music/a.mp3"),
            ),
        )
    }

    @Test
    fun `two real segments are both shown`() {
        assertEquals(
            "Download / SimpleTikTokMod",
            dev.niccc2007.filet.handlers.folderContext(
                dev.niccc2007.filet.vfs.VPath.of(
                    "local",
                    "/storage/emulated/0/Download/SimpleTikTokMod/a.mp4",
                ),
            ),
        )
    }

    @Test
    fun `a track at the root of a volume names the volume`() {
        assertEquals(
            "dav",
            dev.niccc2007.filet.handlers.folderContext(
                dev.niccc2007.filet.vfs.VPath.of("dav", "/a.mp3"),
            ),
        )
    }
}
