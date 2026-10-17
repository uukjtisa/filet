package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sample rate a decoded track is LABELLED with, against the samples that are actually
 * there.
 *
 * ## The defect these are about
 *
 * A long track played back an octave high - the chipmunk. Pitch error has exactly one cause:
 * samples at one rate carrying a label that says another. Several places in the decode could
 * produce that disagreement, they all sound identical, and none of them is distinguishable by
 * listening, so the label is measured against the container's own duration rather than trusted.
 *
 * `TrackPcm.trueRate` is the arbiter and it is pure, which is the only reason this can be
 * tested at all - the rest of that decode needs a codec.
 */
class TrackPcmRateTest {

    @Test
    fun `a label that agrees with the samples is left alone`() {
        // Three minutes at 22050: 180 * 22050 samples, declared 22050.
        val count = 180 * 22_050
        assertEquals(22_050, TrackPcm.trueRate(count, 22_050, 180_000L))
    }

    @Test
    fun `an octave of pitch error is caught and corrected`() {
        // THE chipmunk, as arithmetic: half as many samples as the label claims means the
        // audio plays twice as fast, which is one octave up.
        val count = 180 * 11_025
        assertEquals(11_025, TrackPcm.trueRate(count, 22_050, 180_000L))
        // And the other direction, which would drag rather than chirp.
        assertEquals(44_100, TrackPcm.trueRate(180 * 44_100, 22_050, 180_000L))
    }

    @Test
    fun `rounding and a missing tail buffer do not trigger a correction`() {
        // A container's duration is whole milliseconds and the last output buffer is not
        // always decoded, so the measurement is always slightly under. Believing a two
        // percent difference would relabel every track in the library.
        val count = (180 * 22_050 * 0.98).toInt()
        assertEquals(22_050, TrackPcm.trueRate(count, 22_050, 180_000L))
    }

    @Test
    fun `a container with no duration leaves the label alone`() {
        // Some streams report nothing. There is then no second fact to check against, and a
        // guess is worse than the decimation's own answer.
        assertEquals(22_050, TrackPcm.trueRate(180 * 11_025, 22_050, 0L))
        assertEquals(22_050, TrackPcm.trueRate(180 * 11_025, 22_050, -1L))
    }

    @Test
    fun `an absurd measurement is refused rather than applied`() {
        // A duration that is wrong by orders of magnitude - a broken variable-bitrate header
        // does this - would otherwise relabel the track at a rate no device can play, which
        // turns a pitch bug into silence.
        assertEquals(22_050, TrackPcm.trueRate(100, 22_050, 180_000L))
        assertEquals(22_050, TrackPcm.trueRate(180 * 22_050, 22_050, 10L))
    }

    @Test
    fun `nothing decoded is not a rate`() {
        assertEquals(22_050, TrackPcm.trueRate(0, 22_050, 180_000L))
    }
}
