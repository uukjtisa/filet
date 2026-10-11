package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedSeedsTest {

    private val primary = VPath.of("local", "/storage/emulated/0")
    private val card = VPath.of("local", "/storage/1A2B-3C4D")
    private val share = VPath.of("webdav", "/a/code")
    private val granted = VPath.of("saf", "/tree/primary%3AAndroid%2Fmedia")

    private fun p(root: VPath, name: String) = root.child(name)

    @Test
    fun `candidates are offered on every local volume and on no remote`() {
        val c = TrackedSeeds.candidatesFor(listOf(primary, card, share))
        assertEquals(TrackedSeeds.CANDIDATES.size * 2, c.size)
        assertTrue(c.contains(p(primary, "DCIM")))
        assertTrue(c.contains(p(card, "DCIM")))
        assertTrue(c.none { it.scheme == "webdav" })
    }

    /**
     * The rule that keeps Home fast. Every tracked entry is refreshed, so a folder nobody has
     * is a listing that fails on every refresh for ever.
     */
    @Test
    fun `a folder that is not there is not tracked`() {
        val present = setOf(p(primary, "DCIM"), p(primary, "Download"))
        val plan = TrackedSeeds.plan(listOf(primary), present, already = emptyList())
        assertEquals(listOf(p(primary, "DCIM"), p(primary, "Download")), plan.map { it.path })
    }

    @Test
    fun `the camera roll pictures music and downloads are watched beneath, documents is not`() {
        val all = TrackedSeeds.candidatesFor(listOf(primary)).toSet()
        val plan = TrackedSeeds.plan(listOf(primary), all, already = emptyList())
        val byName = plan.associate { it.path.name to it.recursive }
        assertEquals(true, byName["DCIM"])
        assertEquals(true, byName["Pictures"])
        assertEquals(true, byName["Music"])
        assertEquals(true, byName["Download"])
        assertEquals(false, byName["Documents"])
    }

    @Test
    fun `nothing already tracked is added twice`() {
        val all = TrackedSeeds.candidatesFor(listOf(primary)).toSet()
        val plan = TrackedSeeds.plan(
            listOf(primary), all, already = listOf(p(primary, "DCIM"), p(primary, "Music")),
        )
        assertTrue(plan.none { it.path.name == "DCIM" })
        assertTrue(plan.none { it.path.name == "Music" })
        assertTrue(plan.any { it.path.name == "Pictures" })
    }

    /**
     * The migration rule. `Download` was seeded by earlier versions, so on an existing install
     * its absence is a removal somebody meant - and a seeding pass that puts it back has
     * overruled them.
     */
    @Test
    fun `a skipped folder is not re-added on an existing install`() {
        val all = TrackedSeeds.candidatesFor(listOf(primary)).toSet()
        val plan = TrackedSeeds.plan(
            listOf(primary), all, already = emptyList(),
            skip = setOf(p(primary, "Download")),
        )
        assertTrue(plan.none { it.path.name == "Download" })
        assertTrue(plan.any { it.path.name == "DCIM" })
    }

    /**
     * The one the device caught. A granted tree is a subtree, not a volume, so a `DCIM` under
     * it is a guess - and on a real phone the grant was `Android/media`, where the provider
     * cheerfully answered that all four candidates existed.
     */
    @Test
    fun `a granted document tree is never seeded`() {
        assertTrue(TrackedSeeds.candidatesFor(listOf(granted)).isEmpty())
        val plan = TrackedSeeds.plan(
            listOf(granted),
            present = setOf(p(granted, "DCIM"), p(granted, "Pictures")),
            already = emptyList(),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a remote volume is never seeded even when its folders exist`() {
        val plan = TrackedSeeds.plan(
            listOf(share), present = setOf(p(share, "DCIM")), already = emptyList(),
        )
        assertTrue(plan.isEmpty())
    }

    /** A device can report the same volume under two roots; it must not be tracked twice. */
    @Test
    fun `the same volume arriving twice yields one entry each`() {
        val plan = TrackedSeeds.plan(
            listOf(primary, primary), present = setOf(p(primary, "DCIM")), already = emptyList(),
        )
        assertEquals(1, plan.size)
    }

    @Test
    fun `an empty device plans nothing`() {
        assertTrue(TrackedSeeds.plan(listOf(primary), emptySet(), emptyList()).isEmpty())
        assertTrue(TrackedSeeds.plan(emptyList(), emptySet(), emptyList()).isEmpty())
    }
}
