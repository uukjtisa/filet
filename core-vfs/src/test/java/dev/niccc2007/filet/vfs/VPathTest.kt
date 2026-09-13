package dev.niccc2007.filet.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VPathTest {

    @Test fun normalises_duplicate_and_trailing_separators() {
        assertEquals("/a/b", VPath.of("local", "//a///b/").path)
        assertEquals("/", VPath.of("local", "///").path)
    }

    @Test fun resolves_dot_and_dotdot() {
        assertEquals("/a/c", VPath.of("local", "/a/b/../c").path)
        assertEquals("/a", VPath.of("local", "/a/./").path)
    }

    @Test fun dotdot_cannot_escape_the_root() {
        // The traversal case. Clamped, never resolved above the volume it names.
        assertEquals("/", VPath.of("local", "/../../..").path)
        assertEquals("/etc", VPath.of("local", "/a/../../../etc").path)
    }

    @Test fun parses_round_trip() {
        val p = VPath.parse("local:///storage/emulated/0/Download")
        assertEquals("local", p.scheme)
        assertEquals("/storage/emulated/0/Download", p.path)
        assertEquals("local:///storage/emulated/0/Download", p.toString())
    }

    @Test fun name_parent_and_segments() {
        val p = VPath.of("local", "/a/b/c.txt")
        assertEquals("c.txt", p.name)
        assertEquals("/a/b", p.parent?.path)
        assertEquals(listOf("a", "b", "c.txt"), p.segments)
        assertNull(VPath.of("local", "/").parent)
        assertTrue(VPath.of("local", "/").isRoot)
    }

    @Test fun child_appends_one_segment() {
        assertEquals("/a/b", VPath.of("local", "/a").child("b").path)
        assertEquals("/x", VPath.of("local", "/").child("x").path)
    }

    @Test fun child_rejects_separators_and_traversal() {
        val base = VPath.of("local", "/a")
        for (bad in listOf("../x", "..", ".", "b/c", "")) {
            val threw = runCatching { base.child(bad) }.isFailure
            assertTrue("child(\"$bad\") should have been rejected", threw)
        }
    }

    @Test fun contains_is_prefix_aware_not_string_naive() {
        val dir = VPath.of("local", "/a/b")
        assertTrue(dir.contains(dir))
        assertTrue(dir.contains(VPath.of("local", "/a/b/c")))
        assertFalse(dir.contains(VPath.of("local", "/a/bb")))       // the classic prefix bug
        assertFalse(dir.contains(VPath.of("local", "/a")))
        assertFalse(dir.contains(VPath.of("other", "/a/b/c")))      // different scheme
    }

    @Test fun requires_absolute_path() {
        assertTrue(runCatching { VPath("local", "relative") }.isFailure)
    }
}
