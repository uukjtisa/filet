package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * The sound settings' arithmetic: what 100% leaves alone, what 200% asks of the limiter, and what
 * the equaliser is allowed to do to the level.
 */
class SoundMathsTest {

    @Test
    fun `at the defaults nothing is in the signal path and the player is at full volume`() {
        val plan = SoundMaths.plan(SoundSettings())
        assertEquals(1f, plan.level, 0f)
        assertEquals(0, plan.boostMb)
        assertNull(plan.eqGains)
        assertFalse(plan.boosts)
        // The equaliser switched on but left flat is the same thing: no effect, not a flat one.
        assertNull(SoundMaths.plan(SoundSettings(eqOn = true)).eqGains)
        // And bands set while it is OFF stay out of the signal.
        assertNull(SoundMaths.plan(SoundSettings(eqOn = false, bands = listOf(6, 6, 6, 6, 6), bass = 80)).eqGains)
    }

    @Test
    fun `under 100 percent is the player's volume alone, and over it is the boost alone`() {
        assertEquals(0.5f, SoundMaths.plan(SoundSettings(volume = 50)).level, 0f)
        assertEquals(0, SoundMaths.plan(SoundSettings(volume = 50)).boostMb)
        assertEquals(0f, SoundMaths.plan(SoundSettings(volume = 0)).level, 0f)
        val twice = SoundMaths.plan(SoundSettings(volume = 200))
        assertEquals(1f, twice.level, 0f)
        // Twice the amplitude is +6.02 dB.
        assertEquals(602, twice.boostMb)
        // Half as much again is +3.52 dB.
        assertEquals(352, SoundMaths.boostMbOf(150))
        // Out of range from a hand-edited file is held in range.
        assertEquals(602, SoundMaths.boostMbOf(5000))
        assertEquals(0f, SoundMaths.levelOf(-30), 0f)
    }

    @Test
    fun `the equaliser reshapes and never adds level, however hard it is pushed`() {
        val cases = listOf(
            SoundSettings(eqOn = true, bands = listOf(12, 12, 12, 12, 12), bass = 100),
            SoundSettings(eqOn = true, bands = listOf(0, 0, 0, 0, 0), bass = 100),
            SoundSettings(eqOn = true, bands = listOf(-12, 4, -6, 0, 9)),
            SoundSettings(eqOn = true, bands = listOf(-6, -6, -6, -6, -6)),
        )
        for (s in cases) {
            val plan = SoundMaths.plan(s)
            assertTrue(s.toString(), SoundMaths.netPeakDb(plan) <= 0.0001f)
        }
        // A boost gets the margin on top; an equaliser that only cuts needs no headroom at all.
        val boosted = SoundMaths.plan(cases[2])
        assertEquals(-(9f + SoundMaths.EQ_MARGIN_DB), boosted.eqInputDb, 0.01f)
        assertEquals(0f, SoundMaths.plan(cases[3]).eqInputDb, 0f)
    }

    @Test
    fun `the bass boost lifts the bottom and leaves the voice and the top alone`() {
        val curve = SoundMaths.bassCurve(100)
        assertEquals(SoundMaths.BASS_MAX_DB, curve.first(), 0.6f)
        val centres = SoundMaths.centres()
        for (i in centres.indices) {
            if (centres[i] >= SoundMaths.BASS_TOP_HZ) assertEquals("at ${centres[i]} Hz", 0f, curve[i], 0.001f)
        }
        // Falling all the way, never rising again.
        for (i in 1 until curve.size) assertTrue(curve[i] <= curve[i - 1] + 1e-6f)
        assertTrue(SoundMaths.bassCurve(0).all { it == 0f })
    }

    @Test
    fun `a shown band carries onto the fine bands around it and not beyond`() {
        val mid = SoundMaths.spread(listOf(0, 0, 12, 0, 0))
        val centres = SoundMaths.centres()
        val nearest = centres.indices.minBy { abs(centres[it] - 910f) }
        assertTrue(mid[nearest] > 9f)
        assertEquals(0f, mid.first(), 0.001f)
        assertEquals(0f, mid.last(), 0.001f)
    }

    @Test
    fun `the limiter holds a full-scale sine at twice its level under full scale once it has caught it`() {
        val rate = 48_000
        val limiter = SoundMaths.Limiter(rate, SoundMaths.boostMbOf(200))
        var clampedAfterAttack = 0
        var peakAfter = 0f
        var peakOut = 0f
        for (n in 0 until rate) {
            val x = sin(2 * PI * 1000.0 * n / rate).toFloat()
            val y = limiter.process(x)
            peakOut = maxOf(peakOut, abs(y))
            // After two milliseconds the compressor has caught it: nothing reaches the clamp.
            if (n >= rate / 500) {
                peakAfter = maxOf(peakAfter, abs(limiter.unclamped))
                if (abs(limiter.unclamped) > 1f) clampedAfterAttack++
            }
        }
        assertEquals(0, clampedAfterAttack)
        assertTrue("settled peak $peakAfter", peakAfter < 0.8f)
        // And at no point, the first milliseconds included, does anything leave above full scale.
        assertTrue(peakOut <= 1f)
    }

    @Test
    fun `the limiter at 100 percent leaves a quiet signal as it was`() {
        // Under the knee, nothing is reduced: the boost's limiter costs a quiet passage nothing.
        val limiter = SoundMaths.Limiter(48_000, 0)
        for (n in 0 until 4800) {
            val x = 0.2f * sin(2 * PI * 440.0 * n / 48_000).toFloat()
            assertEquals(x, limiter.process(x), 1e-5f)
        }
    }

    @Test
    fun `the bands are stored as text and read back, and anything else reads as flat`() {
        assertEquals(listOf(3, -2, 0, 12, -12), SoundMaths.bandsOf(SoundMaths.bandsText(listOf(3, -2, 0, 12, -12))))
        assertEquals(listOf(12, 0, 0, 0, -12), SoundMaths.bandsOf("40,0,0,0,-99"))
        assertEquals(SoundMaths.FLAT, SoundMaths.bandsOf("1,2,3"))
        assertEquals(SoundMaths.FLAT, SoundMaths.bandsOf("a,b,c,d,e"))
        assertEquals(SoundMaths.FLAT, SoundMaths.bandsOf(null))
        // The space and the filter by their ids, and anything unknown as none.
        for (s in SoundMaths.Space.entries) assertEquals(s, SoundMaths.Space.of(s.id))
        for (f in SoundMaths.Filter.entries) assertEquals(f, SoundMaths.Filter.of(f.id))
        assertEquals(SoundMaths.Space.NONE, SoundMaths.Space.of("cathedral"))
        assertEquals(SoundMaths.Filter.NONE, SoundMaths.Filter.of(null))
    }

    @Test
    fun `muffled takes the top away and leaves the bass, radio keeps only the telephone band`() {
        val edges = SoundMaths.EQ_EDGES_HZ
        val muffled = SoundMaths.plan(SoundSettings(muffled = EffectLevel(true, 100))).eqGains!!
        for (i in edges.indices) {
            if (edges[i] <= 355f) assertEquals("at ${edges[i]} Hz", 0f, muffled[i], 0.001f)
            if (edges[i] >= 5600f) assertTrue("at ${edges[i]} Hz", muffled[i] <= -24f)
        }
        // Falling all the way up: a wall does not let a band through again higher up.
        for (i in 1 until muffled.size) assertTrue(muffled[i] <= muffled[i - 1])
        val radio = SoundMaths.plan(SoundSettings(radio = EffectLevel(true, 100))).eqGains!!
        for (i in edges.indices) {
            if (edges[i] <= 90f || edges[i] >= 11200f) assertTrue("at ${edges[i]} Hz", radio[i] <= -30f)
            if (edges[i] in 710f..2800f) assertTrue("at ${edges[i]} Hz", radio[i] >= 0f)
        }
    }

    @Test
    fun `a room or a hall puts the reverb on and keeps its headroom, and its switch takes it off`() {
        val hall = SoundMaths.plan(SoundSettings(reverb = EffectLevel(true, 100)))
        assertEquals(SoundMaths.Space.HALL, hall.space)
        // A flat curve still gets the equaliser, for the headroom in front of the reverb.
        assertTrue(hall.equalises)
        assertEquals(-SoundMaths.room(SoundMaths.Space.HALL)!!.headroomDb, hall.eqInputDb, 0.001f)
        assertEquals(hall.spaceHeadroomDb, -hall.eqInputDb, 0.001f)
        assertTrue(SoundMaths.netPeakDb(hall) <= 0.0001f)
        // With a boosted curve on top, both headrooms add and the net still cannot clip.
        val loud = SoundMaths.plan(
            SoundSettings(eqOn = true, bands = listOf(12, 12, 12, 12, 12), bass = 100, reverb = EffectLevel(true, 25)),
        )
        assertTrue(SoundMaths.netPeakDb(loud) <= 0.0001f)
        // Off is off: no reverb, and no equaliser kept in for it, whatever size is stored.
        val off = SoundMaths.plan(SoundSettings(reverb = EffectLevel(false, 100)))
        assertEquals(SoundMaths.Space.NONE, off.space)
        assertNull(off.eqGains)
    }

    @Test
    fun `each size is its own room, longer and louder than the one under it`() {
        // Size, decay ms, HF ratio, reverb mB, wet %, headroom dB to one place.
        val table = listOf(
            listOf(SoundMaths.Space.SMALL_ROOM, 600, 800, 800, 25, "1.9"),
            listOf(SoundMaths.Space.ROOM, 1000, 800, 1100, 35, "2.6"),
            listOf(SoundMaths.Space.LARGE_ROOM, 1600, 750, 1400, 50, "3.5"),
            listOf(SoundMaths.Space.MEDIUM_HALL, 2600, 700, 1700, 71, "4.6"),
            listOf(SoundMaths.Space.HALL, 4000, 650, 2000, 100, "6.0"),
        )
        assertEquals(SoundMaths.Space.SIZES, table.map { it[0] })
        for (row in table) {
            val name = (row[0] as SoundMaths.Space).label
            val r = SoundMaths.room(row[0] as SoundMaths.Space)!!
            assertEquals(name, row[1], r.decayMs)
            assertEquals(name, row[2], r.decayHfRatio)
            assertEquals(name, row[3], r.reverbLevelMb)
            assertEquals(name, 0, r.roomLevelMb)
            assertEquals(name, -300, r.roomHfLevelMb)
            assertEquals(name, 1000, r.diffusion)
            assertEquals(name, 1000, r.density)
            assertEquals(name, row[4], kotlin.math.round(r.wet * 100).toInt())
            assertEquals(name, row[5], String.format(java.util.Locale.US, "%.1f", r.headroomDb))
        }
        val rooms = SoundMaths.Space.SIZES.map { SoundMaths.room(it)!! }
        for (i in 1 until rooms.size) {
            assertTrue(rooms[i].decayMs > rooms[i - 1].decayMs)
            assertTrue(rooms[i].wet > rooms[i - 1].wet)
            assertTrue(rooms[i].decayHfRatio <= rooms[i - 1].decayHfRatio)
        }
        assertNull(SoundMaths.room(SoundMaths.Space.NONE))
        // Inside the platform's own range for each parameter.
        for (r in rooms) {
            assertTrue(r.decayMs in 100..7000)
            assertTrue(r.decayHfRatio in 100..2000)
            assertTrue(r.reverbLevelMb in -9000..SoundMaths.REVERB_MAX_MB)
            assertTrue(r.roomLevelMb in -9000..0 && r.roomHfLevelMb in -9000..0)
        }
    }

    @Test
    fun `a room's headroom is what its wet level adds at the worst, and never below nothing`() {
        for (space in SoundMaths.Space.SIZES) {
            val r = SoundMaths.room(space)!!
            // The wrapper's level: 2000 * log10(level) millibels is room + reverb - 2000.
            assertEquals(space.label, (r.roomLevelMb + r.reverbLevelMb - 2000).toDouble(), 2000 * log10(r.wet.toDouble()), 0.5)
            assertEquals(space.label, 20 * log10(1.0 + r.wet), r.headroomDb.toDouble(), 0.0001)
            assertEquals(r.headroomDb, SoundMaths.spaceHeadroomDb(space, SoundMaths.Reverb.ENVIRONMENTAL), 0f)
        }
        // A room turned all the way down adds nothing, and nothing is not a negative headroom.
        val silent = SoundMaths.Room(decayMs = 1000, decayHfRatio = 1000, reverbLevelMb = -9000, roomLevelMb = -9000)
        assertTrue(silent.headroomDb >= 0f)
        assertEquals(0f, silent.headroomDb, 0.001f)
        assertEquals(0f, SoundMaths.spaceHeadroomDb(SoundMaths.Space.NONE, SoundMaths.Reverb.ENVIRONMENTAL), 0f)
        // Every size, on top of the loudest curve there is, still nets at or under zero.
        for (amount in listOf(0, 25, 50, 75, 100)) {
            val p = SoundMaths.plan(
                SoundSettings(eqOn = true, bands = listOf(12, 12, 12, 12, 12), bass = 100, reverb = EffectLevel(true, amount)),
            )
            assertEquals(SoundMaths.Space.sized(amount), p.space)
            assertTrue("at $amount", SoundMaths.netPeakDb(p) <= 0.0001f)
        }
    }

    @Test
    fun `without an insert environmental reverb the presets are used, at the headroom they always had`() {
        assertEquals(SoundMaths.Reverb.ENVIRONMENTAL, SoundMaths.reverbFor(environmental = true, preset = true))
        assertEquals(SoundMaths.Reverb.ENVIRONMENTAL, SoundMaths.reverbFor(environmental = true, preset = false))
        assertEquals(SoundMaths.Reverb.PRESET, SoundMaths.reverbFor(environmental = false, preset = true))
        assertNull(SoundMaths.reverbFor(environmental = false, preset = false))
        assertEquals(3f, SoundMaths.SPACE_HEADROOM_DB, 0f)
        for (space in SoundMaths.Space.SIZES) {
            assertEquals(space.label, 3f, SoundMaths.spaceHeadroomDb(space, SoundMaths.Reverb.PRESET), 0f)
            val p = SoundMaths.plan(SoundSettings(reverb = EffectLevel(true, space.amount)), SoundMaths.Reverb.PRESET)
            assertEquals(space, p.space)
            assertEquals(space.label, -3f, p.eqInputDb, 0.001f)
            assertEquals(3f, p.spaceHeadroomDb, 0f)
            assertTrue(SoundMaths.netPeakDb(p) <= 0.0001f)
        }
        assertEquals(0f, SoundMaths.spaceHeadroomDb(SoundMaths.Space.NONE, SoundMaths.Reverb.PRESET), 0f)
        // The same settings keep each reverb's own headroom and nothing else changes between them.
        val s = SoundSettings(eqOn = true, bass = 40, reverb = EffectLevel(true, 100))
        val env = SoundMaths.plan(s, SoundMaths.Reverb.ENVIRONMENTAL)
        val preset = SoundMaths.plan(s, SoundMaths.Reverb.PRESET)
        assertTrue(env.eqGains!!.contentEquals(preset.eqGains))
        assertEquals(env.space, preset.space)
        assertEquals(env.eqInputDb + env.spaceHeadroomDb, preset.eqInputDb + preset.spaceHeadroomDb, 0.001f)
    }
}
