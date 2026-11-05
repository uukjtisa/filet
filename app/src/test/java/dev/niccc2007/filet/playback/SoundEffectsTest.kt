package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effects apart from the equaliser: each its own switch and strength, the reverb's strength
 * as its size, the equaliser's presets, and how all of it is stored and read back from what an
 * older version stored.
 */
class SoundEffectsTest {

    private val s0 = SoundSettings()

    @Test
    fun `the seventeen presets are real curves, each found again from what it sets`() {
        val presets = SoundMaths.EqPreset.entries
        assertEquals(17, presets.size)
        for (p in presets) {
            assertEquals(p.name, SoundMaths.BANDS_HZ.size, p.bands.size)
            // None past 6 dB: the equaliser reshapes, so a bigger boost is only a quieter track.
            assertTrue(p.name, p.bands.all { it in -6..6 })
            assertTrue(p.name, p.bass in 0..100)
            val s = p.on(SoundSettings(volume = 150, reverb = EffectLevel(true, 50)))
            assertTrue(s.eqOn)
            // A preset is the equaliser's: the volume and the effects stay where they were.
            assertEquals(150, s.volume)
            assertEquals(EffectLevel(true, 50), s.reverb)
            assertEquals(p, SoundMaths.EqPreset.of(s))
        }
        assertEquals(presets.size, presets.map { it.bands to it.bass }.toSet().size)
        assertEquals(presets.size, presets.map { it.label }.toSet().size)
    }

    @Test
    fun `touching any slider after a preset makes it custom`() {
        val rock = SoundMaths.EqPreset.ROCK.on(s0)
        assertNull(SoundMaths.EqPreset.of(rock.copy(bands = rock.bands.toMutableList().also { it[2] = 0 })))
        assertNull(SoundMaths.EqPreset.of(rock.copy(bass = 5)))
        // An effect is not part of the curve, so Rock with a hall on it is still Rock.
        assertEquals(SoundMaths.EqPreset.ROCK, SoundMaths.EqPreset.of(rock.copy(reverb = EffectLevel(true, 100))))
        assertEquals(SoundMaths.EqPreset.FLAT, SoundMaths.EqPreset.of(s0))
    }

    @Test
    fun `the equaliser's switch is its own and leaves the effects alone`() {
        // The switch off: the curve is not heard, the effects still are.
        val off = SoundMaths.EqPreset.ROCK.on(s0).copy(eqOn = false, muffled = EffectLevel(true, 100))
        val withMuffled = SoundMaths.plan(off).eqGains!!
        val muffledOnly = SoundMaths.plan(s0.copy(muffled = EffectLevel(true, 100))).eqGains!!
        assertTrue(withMuffled.contentEquals(muffledOnly))
        val spaced = SoundMaths.plan(s0.copy(eqOn = false, reverb = EffectLevel(true, 0)))
        assertEquals(SoundMaths.Space.SMALL_ROOM, spaced.space)
        // And every effect off with the switch off is nothing at all in the signal.
        assertNull(SoundMaths.plan(SoundMaths.EqPreset.ROCK.on(s0).copy(eqOn = false)).eqGains)
    }

    @Test
    fun `a filter's strength scales its curve, and at 0 it is no filter`() {
        val full = SoundMaths.plan(s0.copy(radio = EffectLevel(true, 100))).eqGains!!
        val half = SoundMaths.plan(s0.copy(radio = EffectLevel(true, 50))).eqGains!!
        for (i in full.indices) assertEquals("band $i", full[i] / 2f, half[i], 0.001f)
        assertNull(SoundMaths.plan(s0.copy(radio = EffectLevel(true, 0))).eqGains)
        assertFalse(SoundMaths.Overlay.RADIO.heard(EffectLevel(true, 0)))
        // Both at once add, and the headroom still keeps the net at or under full scale.
        val both = SoundMaths.plan(
            SoundMaths.EqPreset.BASS_BOOST.on(s0).copy(muffled = EffectLevel(true, 60), radio = EffectLevel(true, 40)),
        )
        assertTrue(SoundMaths.netPeakDb(both) <= 0.0001f)
    }

    @Test
    fun `the reverb's strength is its size, five of them, and the smallest is still a room`() {
        assertEquals(
            listOf("Small room", "Room", "Large room", "Hall", "Large hall"),
            SoundMaths.Space.SIZES.map { it.label },
        )
        SoundMaths.Space.SIZES.forEachIndexed { i, size ->
            assertEquals(i * 25, size.amount)
            assertEquals(size, SoundMaths.Space.sized(size.amount))
            assertEquals(size, SoundMaths.plan(s0.copy(reverb = EffectLevel(true, size.amount))).space)
        }
        assertEquals(SoundMaths.Space.ROOM, SoundMaths.Space.sized(37))
        assertEquals(SoundMaths.Space.LARGE_ROOM, SoundMaths.Space.sized(38))
        assertEquals(SoundMaths.Space.HALL, SoundMaths.Space.sized(400))
        assertTrue(SoundMaths.Overlay.REVERB.heard(EffectLevel(true, 0)))
        assertFalse(SoundMaths.Overlay.REVERB.heard(EffectLevel(false, 100)))
        // The two that were stored before the sizes are still the rooms they were.
        assertEquals(SoundMaths.Space.ROOM, SoundMaths.Space.of("room"))
        assertEquals(SoundMaths.Space.HALL, SoundMaths.Space.of("hall"))
        // No equaliser preset brings a reverb in.
        for (p in SoundMaths.EqPreset.entries) assertFalse(p.name, SoundMaths.plan(p.on(s0)).spaces)
    }

    @Test
    fun `a strength snaps to the reverb's sizes or to fives, and says itself`() {
        val reverb = SoundMaths.Overlay.REVERB
        val muffled = SoundMaths.Overlay.MUFFLED
        assertEquals(50, reverb.snap(44f))
        assertEquals(25, reverb.snap(37f))
        assertEquals(65, muffled.snap(63f))
        assertEquals(0, muffled.snap(-20f))
        assertEquals(100, muffled.snap(Float.NaN))
        assertEquals("Small room", reverb.amountLabel(0))
        assertEquals("60%", muffled.amountLabel(60))
        // Set through the overlay, a strength off the grid lands on it.
        assertEquals(EffectLevel(true, 75), reverb.of(reverb.set(s0, EffectLevel(true, 70))))
        assertEquals(EffectLevel(false, 40), muffled.of(muffled.set(s0, EffectLevel(false, 41))))
        for (o in SoundMaths.Overlay.entries) assertEquals(o, SoundMaths.Overlay.of(o.id))
        assertNull(SoundMaths.Overlay.of("vinyl"))
    }

    @Test
    fun `a level is stored as its switch and its strength, and anything else is not a level`() {
        for (l in listOf(EffectLevel(true, 25), EffectLevel(false, 100), EffectLevel(true, 0))) {
            assertEquals(l, SoundMaths.levelOf(SoundMaths.levelText(l)))
        }
        assertEquals("1:25", SoundMaths.levelText(EffectLevel(true, 25)))
        assertEquals(EffectLevel(true, 100), SoundMaths.levelOf("1:400"))
        for (bad in listOf(null, "", "hall", "2:50", "1:", "1:x", "1:2:3")) assertNull(bad, SoundMaths.levelOf(bad))
    }

    @Test
    fun `an older stored room or filter is heard exactly when it was heard before`() {
        // Before the effects had a switch each, a stored name was heard only under the one switch.
        assertEquals(EffectLevel(true, 100), SoundMaths.reverbOf("hall", eqOn = true))
        assertEquals(EffectLevel(false, 25), SoundMaths.reverbOf("room", eqOn = false))
        assertEquals(EffectLevel(false, SoundMaths.REVERB_AMOUNT), SoundMaths.reverbOf("none", eqOn = true))
        assertEquals(EffectLevel(false, SoundMaths.REVERB_AMOUNT), SoundMaths.reverbOf(null, eqOn = true))
        assertEquals(EffectLevel(true, 50), SoundMaths.reverbOf("1:50", eqOn = false))

        val off = EffectLevel(false, 100)
        val muffled = SoundMaths.Overlay.MUFFLED
        val radio = SoundMaths.Overlay.RADIO
        assertEquals(mapOf(muffled to EffectLevel(true, 100), radio to off), SoundMaths.filtersOf("muffled", eqOn = true))
        assertEquals(mapOf(muffled to off, radio to EffectLevel(false, 100)), SoundMaths.filtersOf("radio", eqOn = false))
        assertTrue(SoundMaths.filtersOf(null, eqOn = true).isEmpty())
        assertTrue(SoundMaths.filtersOf("none", eqOn = true).isEmpty())
        // What the version with only those two wrote reads back as written, the rest left off.
        val m = EffectLevel(true, 60)
        val r = EffectLevel(false, 35)
        assertEquals(s0.copy(muffled = m, radio = r), SoundMaths.withFilters(s0, "muffled=1:60,radio=0:35", eqOn = false))
        // One of the two unreadable is that one as it was; the other is still read.
        assertEquals(s0.copy(muffled = m), SoundMaths.withFilters(s0, "muffled=1:60,radio=zz", eqOn = true))
        assertEquals(s0, SoundMaths.withFilters(s0, "=", eqOn = true))
    }

    @Test
    fun `every effect but the reverb is kept by name under the filter key, and reads back as written`() {
        val every = s0.copy(
            muffled = EffectLevel(true, 60),
            radio = EffectLevel(false, 35),
            underwater = EffectLevel(true, 45),
            wide = EffectLevel(true, 80),
            evenOut = EffectLevel(false, 25),
            nightcore = EffectLevel(true, 100),
            vaporwave = EffectLevel(false, 60),
            orbit = EffectLevel(true, 50),
        )
        val text = SoundMaths.filtersText(every)
        assertEquals(
            "muffled=1:60,radio=0:35,underwater=1:45,wide=1:80,evenout=0:25,nightcore=1:100,vaporwave=0:60,8d=1:50",
            text,
        )
        assertEquals(every, SoundMaths.withFilters(s0, text, eqOn = false))
        // The reverb has its own key, so one written here is not read.
        assertEquals(s0, SoundMaths.withFilters(s0, "reverb=1:100", eqOn = true))
        // A name from a later build is passed over and the rest still read.
        assertEquals(s0.copy(radio = EffectLevel(true, 20)), SoundMaths.withFilters(s0, "lofi=1:50,radio=1:20", eqOn = true))
        // Every effect is stored, and stored once.
        assertEquals(SoundMaths.Overlay.entries.size - 1, text.split(',').size)
    }

    @Test
    fun `underwater is a curve on the equaliser like the other two, under full scale`() {
        val plan = SoundMaths.plan(s0.copy(underwater = EffectLevel(true, 100)))
        assertTrue(plan.eqGains!!.contentEquals(SoundMaths.Filter.UNDERWATER.gains))
        // Its 4 dB swell is paid for in front, with the margin every curve gets.
        assertEquals(-(4f + SoundMaths.EQ_MARGIN_DB), plan.eqInputDb, 0.001f)
        assertTrue(SoundMaths.netPeakDb(plan) <= 0.0001f)
        // Duller than the wall above the low mids, at every band from 710 Hz up.
        val wall = SoundMaths.Filter.MUFFLED.gains
        for (i in 5 until wall.size) assertTrue("band $i", SoundMaths.Filter.UNDERWATER.gains[i] <= wall[i])
        assertNull(SoundMaths.plan(s0.copy(underwater = EffectLevel(true, 0))).eqGains)
    }

    @Test
    fun `nightcore and vaporwave are one rate on speed and pitch together, and never on together`() {
        val nightcore = SoundMaths.Overlay.NIGHTCORE
        val vaporwave = SoundMaths.Overlay.VAPORWAVE
        assertEquals(1.24f, SoundMaths.tapeRate(nightcore, SoundMaths.TAPE_AMOUNT), 0.0001f)
        assertEquals(1.4f, SoundMaths.tapeRate(nightcore, 100), 0.0001f)
        assertEquals(0.79f, SoundMaths.tapeRate(vaporwave, SoundMaths.TAPE_AMOUNT), 0.0001f)
        assertEquals(0.65f, SoundMaths.tapeRate(vaporwave, 100), 0.0001f)
        assertEquals("1.24\u00d7", nightcore.amountLabel(60))
        assertEquals("0.79\u00d7", vaporwave.amountLabel(60))
        assertEquals("1.4\u00d7", nightcore.amountLabel(100))
        assertEquals("1\u00d7", vaporwave.amountLabel(0))
        assertFalse(nightcore.heard(EffectLevel(true, 0)))
        // Switching one on switches the other off and keeps its strength for later.
        val fast = nightcore.switched(vaporwave.switched(vaporwave.withAmount(s0, 80), true), true)
        assertEquals(EffectLevel(true, SoundMaths.TAPE_AMOUNT), fast.nightcore)
        assertEquals(EffectLevel(false, 80), fast.vaporwave)
        assertEquals(1.24f, SoundMaths.rateOf(fast), 0.0001f)
        // Off is only ever off: switching one off does not switch the other on.
        assertEquals(EffectLevel(false, 80), nightcore.switched(fast, false).vaporwave)
        assertEquals(1f, SoundMaths.rateOf(nightcore.switched(fast, false)), 0f)
        // The player does it, so nothing goes in the signal for it.
        val plan = SoundMaths.plan(fast)
        assertNull(plan.eqGains)
        assertFalse(plan.boosts || plan.spaces || plan.widens || plan.evenOut != null)
        assertEquals(1.24f, plan.rate, 0.0001f)
    }

    @Test
    fun `the tape rides on the sheet's speed and stays inside what the platform plays`() {
        // Alone: speed and pitch together, a resampling.
        assertEquals(1.24f to 1.24f, SoundMaths.played(1f, 1f, 1.24f, 4f))
        // On top of 1.5x with the pitch held: half as fast again, the pitch moved only by the tape.
        val (speed, pitch) = SoundMaths.played(1.5f, 1f, 1.24f, 4f)
        assertEquals(1.86f, speed, 0.0001f)
        assertEquals(1.24f, pitch, 0.0001f)
        // Past the phone's fastest, and past the platform's pitch range, held at the edge.
        assertEquals(4f to 4f, SoundMaths.played(4f, 4f, 1.4f, 4f))
        assertEquals(SoundMaths.PITCH_FLOOR, SoundMaths.played(0.25f, 0.25f, 0.65f, 4f).second, 0f)
        // And no tape is the sheet's own.
        assertEquals(0.75f to 0.75f, SoundMaths.played(0.75f, 0.75f, 1f, 4f))
    }

    @Test
    fun `even out only ever brings the loud parts down, harder the stronger it is`() {
        assertNull(SoundMaths.compression(0))
        val half = SoundMaths.compression(50)!!
        assertEquals(-21f, half.thresholdDb, 0.001f)
        assertEquals(2.5f, half.ratio, 0.001f)
        val full = SoundMaths.compression(100)!!
        assertEquals(-30f, full.thresholdDb, 0.001f)
        assertEquals(4f, full.ratio, 0.001f)
        var last = SoundMaths.compression(5)!!
        for (a in 10..100 step 5) {
            val c = SoundMaths.compression(a)!!
            assertTrue("$a", c.thresholdDb < last.thresholdDb && c.ratio > last.ratio)
            last = c
        }
        // Alone it needs the equaliser's effect, flat and with no gain in front: it adds nothing.
        val plan = SoundMaths.plan(s0.copy(evenOut = EffectLevel(true, 50)))
        assertEquals(half, plan.evenOut)
        assertTrue(plan.eqGains!!.all { it == 0f })
        assertEquals(0f, plan.eqInputDb, 0f)
        assertNull(SoundMaths.plan(s0.copy(evenOut = EffectLevel(false, 50))).eqGains)
    }

    @Test
    fun `wide keeps its margin in front, and alone still brings the flat equaliser for it`() {
        val plan = SoundMaths.plan(s0.copy(wide = EffectLevel(true, SoundMaths.WIDE_AMOUNT)))
        assertEquals(600, plan.wide)
        assertEquals(SoundMaths.WIDE_HEADROOM_DB, plan.wideHeadroomDb, 0f)
        assertEquals(-SoundMaths.WIDE_HEADROOM_DB, plan.eqInputDb, 0.001f)
        assertTrue(plan.eqGains!!.all { it == 0f })
        assertTrue(SoundMaths.netPeakDb(plan) <= 0.0001f)
        assertEquals(1000, SoundMaths.plan(s0.copy(wide = EffectLevel(true, 100))).wide)
        // With a hall and a curve on as well, all three are paid for and the net is still at zero.
        val all = SoundMaths.plan(
            SoundMaths.EqPreset.ROCK.on(s0).copy(wide = EffectLevel(true, 100), reverb = EffectLevel(true, 100)),
        )
        assertTrue(SoundMaths.netPeakDb(all) <= 0.0001f)
        assertNull(SoundMaths.plan(s0.copy(wide = EffectLevel(false, 100))).eqGains)
    }

    @Test
    fun `8D is an equal-power sweep from ear to ear, and moves smoothly`() {
        for (t in 0L..SoundMaths.ORBIT_PERIOD_MS step 37) {
            val (l, r) = SoundMaths.orbitGains(1f, t)
            assertEquals("at $t", 1f, l * l + r * r, 0.00001f)
        }
        // The middle, then each ear alone at a full swing.
        val (l0, r0) = SoundMaths.orbitGains(1f, 0)
        assertEquals(0.7071f, l0, 0.0001f)
        assertEquals(0.7071f, r0, 0.0001f)
        assertEquals(0f, SoundMaths.orbitGains(1f, 2_000).first, 0.0001f)
        assertEquals(0f, SoundMaths.orbitGains(1f, 6_000).second, 0.0001f)
        // At its default swing the far side is 16 dB down, not gone.
        val far = SoundMaths.orbitGains(SoundMaths.ORBIT_AMOUNT / 100f, 2_000).first
        assertEquals(-16f, 20f * kotlin.math.log10(far), 0.5f)
        // The steepest step between two of the sweep's ticks: 1.5% of full scale.
        var steepest = 0f
        for (t in 0L until SoundMaths.ORBIT_PERIOD_MS step 10) {
            val a = SoundMaths.orbitGains(1f, t)
            val b = SoundMaths.orbitGains(1f, t + Sound.SWEEP_STEP_MS)
            steepest = maxOf(steepest, kotlin.math.abs(a.first - b.first), kotlin.math.abs(a.second - b.second))
        }
        assertTrue("$steepest", steepest < 0.016f)
        // It is the player's volume, so nothing goes in the signal for it.
        val plan = SoundMaths.plan(s0.copy(orbit = EffectLevel(true, SoundMaths.ORBIT_AMOUNT)))
        assertNull(plan.eqGains)
        assertEquals(0.8f, plan.orbit, 0.0001f)
        assertEquals(0f, SoundMaths.plan(s0).orbit, 0f)
    }

    @Test
    fun `setting a strength leaves the switch where it is, and the switch keeps the strength`() {
        val reverb = SoundMaths.Overlay.REVERB
        val radio = SoundMaths.Overlay.RADIO
        // Off, a size is got ready and nothing starts playing.
        val ready = reverb.withAmount(s0, 100)
        assertEquals(EffectLevel(false, 100), ready.reverb)
        assertFalse(SoundMaths.plan(ready).spaces)
        // Switched on, it plays at what was set; off again, the size is kept for next time.
        val on = reverb.switched(ready, true)
        assertEquals(SoundMaths.Space.HALL, SoundMaths.plan(on).space)
        assertEquals(EffectLevel(false, 100), reverb.switched(on, false).reverb)
        // On, a new strength stays on, snapped to the grid.
        val loud = radio.switched(s0, true)
        assertEquals(EffectLevel(true, 35), radio.of(radio.withAmount(loud, 37)))
        // And one effect's write touches no other.
        assertEquals(s0.muffled, radio.withAmount(loud, 37).muffled)
    }
}
