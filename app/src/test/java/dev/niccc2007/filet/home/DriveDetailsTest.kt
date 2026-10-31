package dev.niccc2007.filet.home

import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DriveDetailsTest {

    private fun vol(
        path: VPath,
        label: String = "SD card",
        system: String = label,
        free: Long? = 3_000L,
        total: Long? = 10_000L,
        remote: Boolean = false,
    ) = VolumeInfo(VNode(path, isDir = true, size = 0, mtime = 0), label, free, total, remote, system)

    private val bytes: (Long) -> String = { "$it B" }
    private fun keys(v: VolumeInfo) = DriveDetails.rows(v, bytes).map { it.first }

    @Test
    fun `a card on the phone shows its figures and where it is mounted`() {
        val rows = DriveDetails.rows(vol(VPath("local", "/storage/1A2B-3C4D")), bytes).toMap()
        assertEquals("10000 B", rows["Size"])
        assertEquals("7000 B", rows["Used"])
        assertEquals("3000 B", rows["Free"])
        assertEquals("/storage/1A2B-3C4D", rows[DriveDetails.MOUNT_POINT])
    }

    @Test
    fun `the system name is shown only once the drive is renamed`() {
        assertFalse("System name" in keys(vol(VPath("local", "/storage/1A2B-3C4D"))))
        val renamed = vol(VPath("local", "/storage/1A2B-3C4D"), label = "Music card", system = "SD card")
        assertEquals("SD card", DriveDetails.rows(renamed, bytes).toMap()["System name"])
        assertEquals("SD card", DriveDetails.subtitle(renamed))
    }

    @Test
    fun `a share names its protocol and has no mount point to show`() {
        val share = vol(VPath("smb", "/nmuknmxy0/media"), label = "NAS", remote = true)
        val rows = DriveDetails.rows(share, bytes).toMap()
        assertEquals("SMB", rows["File system"])
        assertNull(rows[DriveDetails.MOUNT_POINT])
    }

    @Test
    fun `a disk on the phone does not guess its file system`() =
        assertNull(DriveDetails.rows(vol(VPath("local", "/storage/emulated/0")), bytes).toMap()["File system"])

    @Test
    fun `no figures says so once instead of printing dashes`() {
        val asleep = vol(VPath("smb", "/x/y"), free = null, total = null, remote = true)
        val rows = DriveDetails.rows(asleep, bytes)
        assertEquals(listOf("Name", "Size", "File system"), rows.map { it.first })
        assertEquals("Not answering", rows.toMap()["Size"])
    }
}
