package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEARCH.md §13.8. What `SqliteIndex.volumeIdFor` decides, through [VolumeOwner]. */
class VolumeOwnerTest {

    private val vols = listOf(1L to "smb:///n1", 2L to "smb:///n12", 3L to "local:///storage/emulated/0")

    @Test fun `a root that is a prefix of another does not steal its rows`() {
        assertEquals(2L, VolumeOwner.of(vols, "smb:///n12/photos/a.jpg"))
        assertEquals(1L, VolumeOwner.of(vols, "smb:///n1/photos/a.jpg"))
    }

    @Test fun `the root itself is owned by its volume`() {
        assertEquals(1L, VolumeOwner.of(vols, "smb:///n1"))
        assertEquals(2L, VolumeOwner.of(vols, "smb:///n12"))
    }

    @Test fun `a path under no root has no owner`() =
        assertNull(VolumeOwner.of(vols, "smb:///n2/x"))

    @Test fun `a scheme root owns everything under it, and a deeper volume still wins`() {
        val withRoot = vols + (4L to "local:///")
        assertEquals(4L, VolumeOwner.of(withRoot, "local:///sdcard2/x"))
        assertEquals(3L, VolumeOwner.of(withRoot, "local:///storage/emulated/0/DCIM/a.jpg"))
    }

    @Test fun `no wildcard or case folding, which LIKE did`() {
        assertFalse(VolumeOwner.owns("smb:///n_1", "smb:///nx1/a"))
        assertFalse(VolumeOwner.owns("local:///Storage", "local:///storage/a"))
        assertTrue(VolumeOwner.owns("local:///storage", "local:///storage/a"))
    }
}
