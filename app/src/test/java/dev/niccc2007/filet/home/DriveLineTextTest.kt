package dev.niccc2007.filet.home

import dev.niccc2007.filet.browser.RemovableDrive
import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.data.DriveRegistry
import dev.niccc2007.filet.data.DriveRegistry.Kind
import dev.niccc2007.filet.jobs.Job
import dev.niccc2007.filet.jobs.JobKind
import dev.niccc2007.filet.jobs.JobState
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the drive cards and the not-plugged-in line say, off the device. */
class DriveLineTextTest {

    private val cardPath = VPath.of("local", "/storage/1A2B-3C4D")
    private val card = VolumeInfo(
        VNode(cardPath, isDir = true, size = 0, mtime = 0), "SD card", 1L, 2L,
        removable = RemovableDrive("1A2B-3C4D", Kind.SD),
    )
    private val phone = VolumeInfo(
        VNode(VPath.of("local", "/storage/emulated/0"), isDir = true, size = 0, mtime = 0), "Phone", 1L, 2L,
    )

    private fun job(to: String, kind: JobKind = JobKind.COPY, state: JobState = JobState.RUNNING, items: Int? = null) =
        Job(id = 1, title = "x", state = state, kind = kind, to = to, totalItems = items, doneItems = items?.let { 0 })

    @Test fun `a job writing into the drive counts, one beside it does not`() {
        val into = job("local:///storage/1A2B-3C4D/DCIM")
        val root = job("local:///storage/1A2B-3C4D")
        // Same leading characters, a different volume: a prefix match alone would catch it.
        val neighbour = job("local:///storage/1A2B-3C4D0/x")
        val elsewhere = job("local:///storage/emulated/0/Download")
        assertEquals(listOf(into, root), DriveLineText.writingTo(listOf(into, root, neighbour, elsewhere), card))
    }

    @Test fun `only work still going, and only work that writes, makes the dot amber`() {
        val done = job("local:///storage/1A2B-3C4D/a", state = JobState.DONE)
        val measuring = job("local:///storage/1A2B-3C4D/a", kind = JobKind.MEASURE)
        assertEquals(emptyList<Job>(), DriveLineText.writingTo(listOf(done, measuring), card))
    }

    @Test fun `a volume that is not a drive never reads as being written`() {
        assertEquals(emptyList<Job>(), DriveLineText.writingTo(listOf(job("local:///storage/emulated/0/a")), phone))
    }

    @Test fun `on an older phone, writing through the granted tree is writing to the drive`() {
        val granted = VPath.of("saf", "/content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2F1A2B-3C4D%253A")
        val old = card.copy(removable = RemovableDrive("1A2B-3C4D", Kind.SD, granted = granted))
        val j = job("$granted/DCIM")
        assertEquals(listOf(j), DriveLineText.writingTo(listOf(j), old))
    }

    @Test fun `the caption counts what is left and says what kind of writing`() {
        assertEquals("Copying 3 files · don't unplug", DriveLineText.writingCaption(listOf(job("x", items = 3))))
        assertEquals("Copying 1 file · don't unplug", DriveLineText.writingCaption(listOf(job("x"))))
        assertEquals(
            "Writing 2 files · don't unplug",
            DriveLineText.writingCaption(listOf(job("x"), job("y", kind = JobKind.EXTRACT))),
        )
    }

    private fun known(name: String = "SanDisk", total: Long? = 64L * 1024 * 1024 * 1024, kind: Kind = Kind.SD) =
        DriveRegistry.Known("1A2B-3C4D", kind, name, "/storage/1A2B-3C4D", total, firstSeen = 1, lastSeen = 1_000)

    @Test fun `a gone drive's line says its size, when it was seen and what is indexed`() {
        val now = 1_000L + 3 * 86_400_000L
        val line = DriveLineText.goneDetail(known(), 18_402, now)
        assertEquals(true, line.startsWith("64"))
        assertEquals(true, line.contains("last seen 3 days ago"))
        assertEquals(true, line.endsWith("18,402 files indexed"))
        assertEquals(true, DriveLineText.goneDetail(known(total = null), 0, now).startsWith("last seen"))
        assertEquals(true, DriveLineText.goneDetail(known(), 0, now).endsWith("not indexed"))
    }

    @Test fun `a gone drive goes by the name it was given, else the system's`() {
        val k = known()
        assertEquals("SD · SanDisk", DriveLineText.nameOf(k, emptyMap()))
        assertEquals("Camera card", DriveLineText.nameOf(k, mapOf("local:///storage/1A2B-3C4D" to "Camera card")))
        assertEquals("USB drive", DriveLineText.nameOf(known(name = "", kind = Kind.USB), emptyMap()))
    }
}
