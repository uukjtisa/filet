package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.Perm
import dev.niccc2007.filet.vfs.provider.PermProbe
import dev.niccc2007.filet.vfs.provider.Stat
import dev.niccc2007.filet.vfs.provider.StatFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions a listing makes from one stat, and the cache that keeps it to one.
 *
 * Every assertion here is about a number of syscalls or a bit pattern, because the fault this
 * replaced was invisible to every other kind of check: seven correct answers cost seven times
 * what one correct answer costs, and nothing in a test suite that only looks at values can tell
 * the difference. So the count is the assertion.
 */
class StatFactsTest {

    private fun st(mode: Int, uid: Int = 1000, gid: Int = 1000) =
        Stat(mode = mode, size = 0, mtimeMillis = 0, ino = 1, uid = uid, gid = gid)

    // ── the type bits ──

    @Test
    fun `a directory mode reads as a directory`() {
        // 040755: S_IFDIR | rwxr-xr-x, the ordinary folder.
        assertTrue(StatFacts.isDir(0x41ED))
    }

    @Test
    fun `a regular file mode does not`() {
        // 0100644: S_IFREG | rw-r--r--.
        assertFalse(StatFacts.isDir(0x81A4))
    }

    @Test
    fun `a symlink mode does not read as a directory`() {
        // 0120777: S_IFLNK. Only reachable through lstat, and the provider follows links - but
        // the mask has to be exact or a link would be mistaken for a folder on the fallback
        // path, and tapping it would try to list a file.
        assertFalse(StatFacts.isDir(0xA1FF))
    }

    @Test
    fun `the type test uses the type bits and not merely their presence`() {
        // 0060644: a block device. Its high nibble is 6, and an implementation that tested
        // `mode and S_IFDIR != 0` rather than comparing the masked value would call this a
        // directory, because 6 and 4 share a bit.
        assertFalse(StatFacts.isDir(0x61A4))
    }

    // ── the permission bits ──

    @Test
    fun `permission bits drop the file type and keep all twelve`() {
        // 041777: a sticky world-writable directory, which /storage really does contain.
        assertEquals(0x3FF, StatFacts.permBits(0x43FF))
    }

    @Test
    fun `a file and a folder with the same bits share one signature`() {
        assertEquals(StatFacts.permBits(0x41ED), StatFacts.permBits(0x81ED))
    }

    // ── time ──

    @Test
    fun `seconds and nanoseconds become milliseconds`() {
        assertEquals(1_695_000_000_123L, StatFacts.mtimeMillis(1_695_000_000L, 123_456_789L))
    }

    @Test
    fun `nanoseconds below a millisecond truncate rather than round`() {
        // Truncation is the same direction java-io-File reports, and a timestamp that rounded UP
        // would sit in the future relative to the write that produced it.
        assertEquals(1_000L, StatFacts.mtimeMillis(1L, 999_999L))
    }

    @Test
    fun `a zero nanosecond field gives whole seconds`() {
        assertEquals(1_695_000_000_000L, StatFacts.mtimeMillis(1_695_000_000L, 0L))
    }

    // ── the probe cache ──

    @Test
    fun `a thousand identical entries cost one probe`() {
        val probe = PermProbe { Perm(readable = true, writable = true) }
        repeat(1000) { probe.of("/dcim/camera/IMG_$it.jpg", st(0x81B4)) }
        assertEquals(1, probe.probes)
    }

    @Test
    fun `each distinct signature costs exactly one probe`() {
        val probe = PermProbe { Perm(readable = true, writable = false) }
        probe.of("/a", st(0x81B4))
        probe.of("/b", st(0x81A4))
        probe.of("/c", st(0x81B4))
        probe.of("/d", st(0x81A4))
        assertEquals(2, probe.probes)
    }

    @Test
    fun `a different owner is a different question`() {
        val probe = PermProbe { Perm(readable = true, writable = true) }
        probe.of("/a", st(0x81A4, uid = 1000))
        probe.of("/b", st(0x81A4, uid = 1001))
        assertEquals(2, probe.probes)
    }

    @Test
    fun `a different group is a different question`() {
        val probe = PermProbe { Perm(readable = true, writable = true) }
        probe.of("/a", st(0x81A4, gid = 1000))
        probe.of("/b", st(0x81A4, gid = 9997))
        assertEquals(2, probe.probes)
    }

    @Test
    fun `the cached answer is the probe's answer and not a default`() {
        // The failure this guards is a cache that returns a fresh Perm() on a hit. Both fields
        // false is the opposite of both true, so a stubbed-out hit cannot pass by accident.
        val probe = PermProbe { Perm(readable = false, writable = false) }
        val first = probe.of("/a", st(0x81A4))
        val second = probe.of("/b", st(0x81A4))
        assertEquals(first, second)
        assertFalse(second.readable)
        assertFalse(second.writable)
    }

    @Test
    fun `the answer follows the path that was probed`() {
        // A cache keyed correctly but PROBING the wrong path is the other way to get this wrong,
        // and it would be invisible in a folder where every answer is the same. So the probe
        // here answers differently per path, and the first path asked must be the one used.
        val probe = PermProbe { p -> Perm(readable = p == "/first", writable = false) }
        assertTrue(probe.of("/first", st(0x81A4)).readable)
        // Same signature, so no second probe - and the answer is the first one's, by design.
        assertTrue(probe.of("/second", st(0x81A4)).readable)
        assertEquals(1, probe.probes)
    }

    @Test
    fun `keying on the path instead of the signature is the fault being prevented`() {
        // The negative control, and the only one that matters here: this is what the code did
        // before, expressed as a cache that cannot help. A thousand files, a thousand probes.
        val byPath = HashMap<String, Perm>()
        var probes = 0
        repeat(1000) { i ->
            byPath.getOrPut("/dcim/camera/IMG_$i.jpg") { probes++; Perm(true, true) }
        }
        assertEquals(1000, probes)
    }
}
