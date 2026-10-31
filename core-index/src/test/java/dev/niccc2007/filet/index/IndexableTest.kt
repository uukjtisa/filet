package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a crawl is allowed to walk.
 *
 * The defect behind every one of these: 46% of a day's battery, spent walking two network
 * shares and `/data` from the beginning on every single open - including the opens another app
 * causes by asking Filet for a file.
 */
class IndexableTest {

    private val internal = VPath.of("local", "/storage/emulated/0")
    private val card = VPath.of("local", "/storage/1A2B-3C4D")
    private val granted = VPath.of("saf", "/tree/primary:Shared")
    private val system = VPath.of("root", "/")
    private val data = VPath.of("root", "/data")
    private val nas = VPath.of("smb", "/n1a2b3c")
    private val tablet = VPath.of("sftp", "/n9z8y7")
    private val archive = VPath.of("zip", "/x/y.zip")

    @Test
    fun `local volumes are crawled`() {
        assertTrue(Indexable.allowed(internal))
        assertTrue(Indexable.allowed(card))
        assertTrue(Indexable.allowed(granted))
    }

    @Test
    fun `the superuser volumes are never crawled, granted or not`() {
        // Not a staleness decision and not a budget decision - there is no amount of battery
        // that makes indexing /data by filename worth it, and the volume sheet is how a
        // deliberate search of it is asked for.
        assertFalse(Indexable.allowed(system))
        assertFalse(Indexable.allowed(data))
        // Not even if something managed to put it on an opt-in list meant for shares.
        assertFalse(Indexable.allowed(data, setOf(data.toString())))
    }

    @Test
    fun `a share is not crawled unless that share was turned on`() {
        assertFalse(Indexable.allowed(nas))
        assertFalse(Indexable.allowed(tablet))
        assertTrue(Indexable.allowed(nas, setOf(nas.toString())))
        // One share being opted in says nothing about another.
        assertFalse(Indexable.allowed(tablet, setOf(nas.toString())))
    }

    @Test
    fun `every remote scheme defaults to off`() {
        // The property that matters more than any single case: a provider added later is off
        // until somebody opts a share in, rather than quietly joining the crawl.
        for (scheme in VolumeKey.REMOTE_SCHEMES) {
            assertFalse(scheme, Indexable.allowed(VPath.of(scheme, "/n1")))
        }
    }

    @Test
    fun `a container is not a volume`() {
        // What is inside an archive is indexed when the archive itself is reached, on its own
        // budget. Crawling it as a volume would read every member of every zip on the device.
        assertFalse(Indexable.allowed(archive))
        assertFalse(Indexable.allowed(VPath.of("apk", "/x/y.apk")))
    }

    @Test
    fun `the reported launch is the one this would have stopped`() {
        // Every volume the phone had at the time: internal, a card, a granted tree, two shares
        // and the root mounts. What ran was all of it, unbounded.
        val everything = listOf(internal, card, granted, system, data, nas, tablet)
        assertEquals(listOf(internal, card, granted), Indexable.volumes(everything))
    }

    @Test
    fun `with one share opted in it joins the list and nothing else does`() {
        val everything = listOf(internal, system, nas, tablet)
        assertEquals(
            listOf(internal, nas),
            Indexable.volumes(everything, setOf(nas.toString())),
        )
    }

    @Test
    fun `an empty list in is an empty list out`() {
        assertEquals(emptyList<VPath>(), Indexable.volumes(emptyList()))
    }

    @Test
    fun `the old behaviour is written down, so the fix cannot be mistaken for a tidy-up`() {
        // The negative control: what `crawlFully(roots)` was handed on every open.
        val everything = listOf(internal, card, granted, system, data, nas, tablet)
        assertEquals(7, everything.size)
        assertTrue(
            "the shares and the root mounts were in it",
            everything.any { it.scheme == "smb" } && everything.any { it.scheme == "root" },
        )
        assertEquals(3, Indexable.volumes(everything).size)
    }
}
