package dev.niccc2007.filet.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tile heading, which has less room than a volume label assumes. */
class VolumeLabelTest {

    @Test
    fun `the redundant word is dropped`() {
        // It rendered as "INTERN..." at the tile's width, which names nothing.
        assertEquals("Internal", shortVolumeLabel("Internal storage"))
        assertEquals("Internal", shortVolumeLabel("Internal Storage"))
    }

    @Test
    fun `a label that is only the redundant word keeps it`() {
        assertEquals("Storage", shortVolumeLabel("Storage"))
        assertEquals("storage", shortVolumeLabel("storage"))
    }

    @Test
    fun `labels without it are untouched`() {
        assertEquals("SD card", shortVolumeLabel("SD card"))
        assertEquals("SYSTEM", shortVolumeLabel("SYSTEM"))
        assertEquals("Termux", shortVolumeLabel("Termux"))
    }

    @Test
    fun `whitespace does not survive`() {
        assertEquals("Internal", shortVolumeLabel("  Internal storage  "))
        assertEquals("", shortVolumeLabel("   "))
    }

    @Test
    fun `only the trailing word goes`() {
        assertEquals("Storage box", shortVolumeLabel("Storage box"))
    }
}
