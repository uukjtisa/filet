package dev.niccc2007.filet.handlers

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8D in Filet's player: each side scaled on its own, without steps, and nothing else touched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SidePanTest {

    private val stereo = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private fun shorts(vararg s: Int): ByteBuffer =
        ByteBuffer.allocateDirect(s.size * 2).order(ByteOrder.nativeOrder()).apply { s.forEach { putShort(it.toShort()) }; flip() }

    private fun read(b: ByteBuffer): List<Int> = buildList { while (b.remaining() >= 2) add(b.getShort().toInt()) }

    private fun ready(p: SidePan, f: AudioFormat = stereo): SidePan = p.apply { configure(f); flush() }

    private fun pass(p: SidePan, input: ByteBuffer): List<Int> {
        p.queueInput(input)
        return read(p.output)
    }

    @Test fun `both sides at full leave the sound exactly as it was`() {
        val p = ready(SidePan())
        assertEquals(listOf(1000, -2000, 32767, -32768), pass(p, shorts(1000, -2000, 32767, -32768)))
    }

    @Test fun `a side turned down is quieter on that side only, once the ramp is past`() {
        val p = ready(SidePan())
        p.set(0f, 1f)
        pass(p, shorts(1000, 1000, 1000, 1000))
        assertEquals(listOf(0, 1000, 0, 1000), pass(p, shorts(1000, 1000, 1000, 1000)))
        p.set(1f, 0.5f)
        pass(p, shorts(1000, 1000))
        assertEquals(listOf(1000, 500), pass(p, shorts(1000, 1000)))
    }

    @Test fun `a change is ramped across the buffer it arrives in, never stepped`() {
        val p = ready(SidePan())
        p.set(0f, 1f)
        // Five frames: the left side goes 1, 0.75, 0.5, 0.25, 0 across them.
        val out = pass(p, shorts(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000))
        assertEquals(listOf(1000, 750, 500, 250, 0), out.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(List(5) { 1000 }, out.filterIndexed { i, _ -> i % 2 == 1 })
    }

    @Test fun `mono comes out on both sides, so it pans too`() {
        val p = SidePan()
        assertEquals(2, p.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)).channelCount)
        p.flush()
        p.set(1f, 0f)
        pass(p, shorts(800))
        assertEquals(listOf(800, 0, -800, 0), pass(p, shorts(800, -800)))
    }

    @Test fun `float sound is panned the same way`() {
        val p = ready(SidePan(), AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        p.set(0.5f, 1f)
        val warm = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putFloat(0.5f).putFloat(0.5f).apply { flip() }
        p.queueInput(warm)
        p.output
        p.queueInput(ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putFloat(0.8f).putFloat(-0.8f).apply { flip() })
        val out = p.output
        assertEquals(0.4f, out.getFloat(), 1e-6f)
        assertEquals(-0.8f, out.getFloat(), 1e-6f)
    }

    @Test fun `more than two channels go through untouched`() {
        val p = SidePan()
        p.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        p.flush()
        assertFalse(p.isActive)
        val q = SidePan()
        q.configure(stereo)
        q.flush()
        assertTrue(q.isActive)
    }

    @Test fun `a gain never wraps a loud sample round`() {
        assertEquals(32767, SidePan.scale(Short.MAX_VALUE, 1f).toInt())
        assertEquals(-32768, SidePan.scale(Short.MIN_VALUE, 1f).toInt())
        assertEquals(0, SidePan.scale(12345.toShort(), 0f).toInt())
    }
}
