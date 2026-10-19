package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every failure a person can act on has its own sentence, and an unmapped one still carries its
 * number. The failure this guards is the "something went wrong" that a parsed console message
 * collapses into on a machine set to another language.
 */
class MountErrorsTest {
    private val mapped = listOf(5, 53, 59, 64, 67, 85, 86, 1200, 1201, 1202, 1203, 1208, 1219, 1222, 1223, 1231, 1244, 1326, 2250, 2401, 2404)

    @Test
    fun `every mapped windows code has a specific sentence`() {
        val generic = WinNetErrors.message(99999)
        for (c in mapped) assertNotEquals("code $c fell through", generic.replace("99999", "$c"), WinNetErrors.message(c))
    }

    @Test
    fun `unmapped windows code keeps its number`() = assertTrue(WinNetErrors.message(4321).contains("4321"))

    @Test
    fun `the distinctions a person acts on stay distinct`() {
        // Unreachable, wrong share, letter taken and open files each need a different action.
        val s = listOf(53, 67, 85, 2401).map { WinNetErrors.message(it) }
        assertEquals(s.size, s.toSet().size)
    }

    @Test
    fun `bad net name mentions the WebClient service`() = assertTrue(WinNetErrors.message(67).contains("WebClient"))

    @Test
    fun `netfs maps errno and its own codes and keeps unknown numbers`() {
        assertTrue(NetFsErrors.message(61).contains("stopped sharing"))
        assertTrue(NetFsErrors.message(65).contains("reached"))
        assertTrue(NetFsErrors.message(-5998).contains("not offering"))
        assertTrue(NetFsErrors.message(777).contains("777"))
    }

    @Test
    fun `probe status separates wrong code from no share`() {
        assertNull(ProbeErrors.message(207))
        assertTrue(ProbeErrors.message(403)!!.contains("Wrong access code"))
        assertNotEquals(ProbeErrors.message(403), ProbeErrors.message(404))
        assertNotEquals(ProbeErrors.message(403), ProbeErrors.message(400))
        assertTrue(ProbeErrors.message(418)!!.contains("418"))
    }

    @Test
    fun `linux keeps the exit status when unmapped`() {
        assertTrue(LinuxMountErrors.message("gio", 1).contains("gio"))
        assertTrue(LinuxMountErrors.message("mount.davfs", 1).contains("fstab"))
        assertTrue(LinuxMountErrors.message("umount", 77).contains("77"))
    }
}
