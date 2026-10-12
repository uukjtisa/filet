package dev.niccc2007.filet.playback

import dev.niccc2007.filet.browser.headTrimmed
import dev.niccc2007.filet.browser.plural
import dev.niccc2007.filet.handlers.sleepMinutesLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind the four player faces and the clipboard tray's copy. */
class PlayerFaceTest {

    @Test
    fun `an unknown stored id falls back rather than crashing`() {
        // The stored value survives an upgrade that renames or drops a face, and a player that
        // will not open is a worse outcome than one that opens looking different.
        assertEquals(PlayerFace.DEFAULT, PlayerFace.of("something-else"))
        assertEquals(PlayerFace.DEFAULT, PlayerFace.of(null))
    }

    @Test
    fun `every face round-trips through its stored id`() {
        for (face in PlayerFace.entries) assertEquals(face, PlayerFace.of(face.id))
    }

    @Test
    fun `the ids are distinct, or two faces share a stored value`() {
        assertEquals(PlayerFace.entries.size, PlayerFace.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `only classic is not ambient`() {
        // `ambient` gates the backdrop, the artwork-sampled palette and the immersive bar
        // handling all at once. Getting it wrong on one face is three bugs.
        assertFalse(PlayerFace.CLASSIC.ambient)
        for (face in PlayerFace.entries - PlayerFace.CLASSIC) assertTrue(face.about, face.ambient)
    }

    @Test
    fun `every face says what it is, so the picker is not four bare names`() {
        for (face in PlayerFace.entries) {
            assertTrue(face.id, face.label.isNotBlank())
            assertTrue(face.id, face.about.length > 20)
        }
    }

    // ── the sleep chip ────────────────────────────────────────────────────────

    @Test
    fun `no timer reads as no minutes`() {
        assertEquals(0, sleepMinutesLeft(deadline = 0L, now = 1_000L))
    }

    @Test
    fun `a deadline already passed reads as no minutes`() {
        // The job pauses playback and clears the deadline, but the chip may recompose in
        // between - and "-1m" on a chip is worse than the chip being a tick early.
        assertEquals(0, sleepMinutesLeft(deadline = 500L, now = 1_000L))
    }

    @Test
    fun `a part minute rounds up, so the chip never says zero while it is still running`() {
        assertEquals(1, sleepMinutesLeft(deadline = 1_000L, now = 0L))
        assertEquals(1, sleepMinutesLeft(deadline = 60_000L, now = 0L))
        assertEquals(2, sleepMinutesLeft(deadline = 60_001L, now = 0L))
    }

    @Test
    fun `a fresh fifteen-minute timer reads fifteen`() {
        assertEquals(15, sleepMinutesLeft(deadline = 15 * 60_000L, now = 0L))
    }

    // ── the tray's copy ───────────────────────────────────────────────────────

    @Test
    fun `one batch is not one batches`() {
        assertEquals("1 batch", plural(1, "batch", "batches"))
        assertEquals("3 batches", plural(3, "batch", "batches"))
        assertEquals("0 batches", plural(0, "batch", "batches"))
    }

    @Test
    fun `a short path is left alone`() {
        assertEquals("/sdcard/x", headTrimmed("/sdcard/x", 42))
    }

    @Test
    fun `a long path loses its head, not its tail`() {
        // The end of a path says which folder. Clipping the right reads
        // "/storage/emulated/0/Andro…" on every destination there is.
        val trimmed = headTrimmed("/storage/emulated/0/Download/invoices/2026/october", 20)
        assertEquals(20, trimmed.length)
        assertTrue(trimmed, trimmed.startsWith("…"))
        assertTrue(trimmed, trimmed.endsWith("october"))
    }
}
