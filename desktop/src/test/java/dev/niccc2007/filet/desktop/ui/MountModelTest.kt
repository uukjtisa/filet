package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.KnownPhone
import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.Attached
import dev.niccc2007.filet.desktop.mount.DavTarget
import dev.niccc2007.filet.desktop.ui.MountModel.LetterChoice
import dev.niccc2007.filet.desktop.ui.MountModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MountModelTest {
    private val phone = Peer("Phone", "192.168.100.6", 11111, false, "/a/", "Whole phone", true, deviceId = "dev-1")
    private val tablet = Peer("Tablet", "192.168.100.14", 8321, false, "/a/")

    private fun saved(p: Peer, code: String, label: String = p.name, letter: Char? = null) =
        KnownPhone(MountModel.idOf(p), p.name, label, p.host, p.port, code, deviceId = p.deviceId, letter = letter)

    private fun drive(host: String, port: Int, code: String, where: String = "Z:", base: String = "/a/") =
        Attached(DavTarget(host, port, false, base, code), where)

    @Test
    fun `targetOf builds the share address from a peer and a code`() {
        assertEquals(DavTarget("192.168.100.6", 11111, false, "/a/", "tres3"), MountModel.targetOf(phone, " tres3 "))
        assertEquals("/a/", MountModel.targetOf(phone.copy(base = ""), "x")!!.base)
        assertNull(MountModel.targetOf(phone, ""))
        assertNull(MountModel.targetOf(phone, "a/b"))
    }

    @Test
    fun `mount is allowed only online, with a letter, idle, and a valid code`() {
        assertNull(MountModel.mountBlocker(online = true, "tres3", null, letterFree = true, busy = false))
        assertEquals("This device has left the network.", MountModel.mountBlocker(false, "tres3", null, true, false))
        assertEquals("no webclient", MountModel.mountBlocker(true, "c", "no webclient", true, false))
        assertEquals("Every drive letter from D: to Z: is in use. Disconnect one first.", MountModel.mountBlocker(true, "c", null, false, false))
        assertEquals("Mounting...", MountModel.mountBlocker(true, "c", null, true, busy = true))
        assertEquals("Type the access code shown on the device.", MountModel.mountBlocker(true, "", null, true, false))
    }

    // ── the bug: the row offered Mount again after a mount that worked ──

    @Test
    fun `a saved phone shows as mounted from the OS's own report, with no memory of the run`() {
        val rows = MountModel.rows(listOf(saved(phone, "Tres3")), listOf(phone), listOf(drive("192.168.100.6", 11111, "Tres3")))
        assertEquals(State.MOUNTED, rows.single().state)
        assertEquals("Mounted on Z:", MountModel.stateLine(rows.single()))
    }

    @Test
    fun `the drive is still found when the base or the host's case is spelled differently`() {
        val k = saved(phone, "Tres3").copy(base = "/")
        assertEquals("Z:", MountModel.driveOf(k, listOf(drive("192.168.100.6", 11111, "Tres3")))?.where)
        val named = k.copy(host = "Pixel.local")
        assertEquals("Y:", MountModel.driveOf(named, listOf(drive("pixel.LOCAL", 11111, "Tres3", "Y:")))?.where)
    }

    @Test
    fun `the saved letter finds a drive whose host the OS spells another way, but only with the same code`() {
        val k = saved(phone, "Tres3", letter = 'Y')
        assertEquals("Y:", MountModel.driveOf(k, listOf(drive("fe80::1%12", 11111, "Tres3", "Y:")))?.where)
        assertNull(MountModel.driveOf(k, listOf(drive("fe80::1%12", 11111, "other", "Y:"))))
    }

    @Test
    fun `a different code on the same phone is a different share`() {
        assertNull(MountModel.driveOf(saved(phone, "Tres3"), listOf(drive("192.168.100.6", 11111, "tres3"))))
    }

    // ── two mapped drives that read as not mounted, as Windows reported them ──

    private val honor = Peer("LGN-LX2-Whole Phone", "192.168.5.23", 8321, false, "/a/", "Whole phone", true, deviceId = "honor")
    private val nova = Peer("NCO-LX1-Whole Phone", "192.168.5.15", 11111, false, "/a/", "Whole phone", true, deviceId = "nova")

    /** A drive as Windows reports a connected one: the UNC path with no DavWWWRoot. */
    private fun connected(unc: String, where: String) = Attached(DavTarget.parseUnc(unc)!!, where, remote = unc)

    @Test
    fun `a drive mapped before the list existed is adopted and its row reads mounted`() {
        val z = connected("\\\\192.168.5.23@8321\\a\\tres33", "Z:")
        val known = MountModel.adopt(emptyList(), listOf(z), listOf(honor, nova))
        val rows = MountModel.rows(known, listOf(honor, nova), listOf(z))
        val row = rows.first { it.peer == honor }
        assertEquals(State.MOUNTED, row.state)
        assertEquals("Mounted on Z:", MountModel.stateLine(row))
        assertEquals("192.168.5.23:8321", MountModel.addressLine(row))
        assertEquals(State.NOT_MOUNTED, rows.first { it.peer == nova }.state)
    }

    @Test
    fun `a drive left on the phone's old address is listed, mounted, and says the phone is not there`() {
        val y = connected("\\\\192.168.5.11@11111\\a\\tres3", "Y:")
        val known = MountModel.adopt(emptyList(), listOf(y), listOf(honor, nova))
        val rows = MountModel.rows(known, listOf(honor, nova), listOf(y))
        val orphan = rows.single { it.drive != null }
        assertEquals(State.MOUNTED, orphan.state)
        assertNull(orphan.peer)
        assertEquals("Filet drive at 192.168.5.11:11111, phone not found there", MountModel.addressLine(orphan))
        // The phone itself still has its own row at its new address.
        assertEquals(State.NOT_MOUNTED, rows.first { it.peer == nova }.state)
    }

    @Test
    fun `a saved phone whose drive is on its old address offers a remount at the new one`() {
        val y = connected("\\\\192.168.5.11@11111\\a\\tres3", "Y:")
        val saved = KnownPhone(MountModel.idOf(nova), nova.name, nova.name, "192.168.5.11", 11111, "tres3", deviceId = "nova", letter = 'Y')
        val row = MountModel.rows(listOf(saved), listOf(nova), listOf(y)).single()
        assertEquals(State.MOUNTED, row.state)
        assertEquals(nova, MountModel.movedTo(row))
        assertEquals("Drive at 192.168.5.11:11111; the phone is now at 192.168.5.15:11111", MountModel.addressLine(row))
        assertNull(MountModel.movedTo(MountModel.rows(listOf(saved.copy(host = "192.168.5.15")), listOf(nova),
            listOf(connected("\\\\192.168.5.15@11111\\a\\tres3", "Y:"))).single()))
    }

    // ── rows ──

    @Test
    fun `each row says mounted, not mounted or offline`() {
        val music = phone.copy(name = "Music")
        val rows = MountModel.rows(
            listOf(saved(phone, "a1"), saved(tablet, "b2"), saved(music, "c3")),
            listOf(phone, music),
            listOf(drive(phone.host, phone.port, "a1")),
        )
        assertEquals(listOf(State.MOUNTED, State.OFFLINE, State.NOT_MOUNTED), rows.map { it.state })
        assertEquals(listOf("Mounted on Z:", "Offline", "Not mounted"), rows.map(MountModel::stateLine))
    }

    @Test
    fun `saved phones come first, then phones on the network never mounted here`() {
        val rows = MountModel.rows(listOf(saved(tablet, "b2")), listOf(phone, tablet), emptyList())
        assertEquals(listOf("Tablet", "Phone"), rows.map { it.label })
        assertNull(rows[1].phone)
        assertEquals(phone, rows[1].peer)
    }

    @Test
    fun `a renamed phone keeps its row and its peer`() {
        val row = MountModel.rows(listOf(saved(phone, "a1", label = "Work phone")), listOf(phone), emptyList()).single()
        assertEquals("Work phone", row.label)
        assertEquals(phone, row.peer)
    }

    @Test
    fun `a phone back on a new address is matched by its device id`() {
        val moved = phone.copy(host = "192.168.100.77")
        val row = MountModel.rows(listOf(saved(phone, "a1")), listOf(moved), emptyList()).single()
        assertEquals(moved, row.peer)
        assertEquals(State.NOT_MOUNTED, row.state)
    }

    @Test
    fun `a phone with no device id is matched by name and address`() {
        val k = saved(tablet, "b2")
        assertTrue(MountModel.isPeer(k, tablet.copy(scope = "changed")))
        assertEquals(false, MountModel.isPeer(k, tablet.copy(port = 9999)))
    }

    // ── adopting drives mapped before the list was saved ──

    @Test
    fun `an unclaimed Filet drive gets a row named after the one share on its host and port`() {
        val out = MountModel.adopt(emptyList(), listOf(drive(phone.host, phone.port, "a1", "X:")), listOf(phone, tablet))
        val k = out.single()
        assertEquals("Phone", k.label)
        assertEquals(MountModel.idOf(phone), k.id)
        assertEquals('X', k.letter)
        assertEquals("dev-1", k.deviceId)
    }

    @Test
    fun `with several shares on that port, or none, the address names it rather than a guess`() {
        val d = listOf(drive(phone.host, phone.port, "a1"))
        assertEquals("192.168.100.6:11111", MountModel.adopt(emptyList(), d, listOf(phone, phone.copy(name = "Music"))).single().label)
        assertEquals("192.168.100.6:11111", MountModel.adopt(emptyList(), d, emptyList()).single().label)
    }

    @Test
    fun `a drive a saved phone already claims is not adopted again`() {
        val known = listOf(saved(phone, "a1"))
        assertEquals(known, MountModel.adopt(known, listOf(drive(phone.host, phone.port, "a1")), listOf(phone)))
    }

    @Test
    fun `saving a mount replaces its own entry and any adopted twin, in place`() {
        val adopted = KnownPhone("192.168.100.6:11111/a1", "192.168.100.6:11111", "192.168.100.6:11111", phone.host, phone.port, "a1")
        val other = saved(tablet, "b2")
        val fresh = saved(phone, "a1", label = "Phone")
        assertEquals(listOf(fresh, other), MountModel.upsert(listOf(adopted, other), fresh))
        assertEquals(listOf(other, fresh), MountModel.upsert(listOf(other), fresh))
        val renamed = fresh.copy(label = "Work")
        assertEquals(listOf(renamed, other), MountModel.upsert(listOf(fresh, other), renamed))
    }

    // ── drive letters ──

    @Test
    fun `free letters are offered, Filet's own as replacements, and nobody else's at all`() {
        val choices = MountModel.letterChoices(taken = setOf('Z', 'Y', 'E'), filet = mapOf('Z' to "Phone"))
        assertEquals(LetterChoice('Z', "Phone"), choices.first())
        assertEquals("replace Z: (Phone)", choices.first().text)
        assertTrue(choices.none { it.letter == 'Y' || it.letter == 'E' })
        assertEquals(LetterChoice('X'), choices[1])
        assertEquals("X:", choices[1].text)
        assertEquals('D', choices.last().letter)
    }

    @Test
    fun `moving a drive does not offer the letter it is on`() {
        val choices = MountModel.letterChoices(setOf('Z'), mapOf('Z' to "Phone"), own = 'Z')
        assertTrue(choices.none { it.letter == 'Z' })
    }

    @Test
    fun `the picker starts on the last letter if free, else the first free, and replaces only as a last resort`() {
        val choices = MountModel.letterChoices(setOf('Z', 'Y'), mapOf('Z' to "Phone"))
        assertEquals('X', MountModel.firstChoice(choices, null)?.letter)
        assertEquals('M', MountModel.firstChoice(choices, 'M')?.letter)
        assertEquals('X', MountModel.firstChoice(choices, 'Z')?.letter)
        val full = MountModel.letterChoices(('D'..'Z').toSet(), mapOf('Q' to "Tablet"))
        assertEquals(LetterChoice('Q', "Tablet"), MountModel.firstChoice(full, null))
        assertNull(MountModel.firstChoice(emptyList(), 'Z'))
    }
}
