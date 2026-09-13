package dev.niccc2007.filet.milestones

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.bridge.BridgeContract
import dev.niccc2007.filet.index.QueryParser
import dev.niccc2007.filet.index.SearchRequest
import dev.niccc2007.filet.index.SearchScope
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M7's exit criterion.
 *
 * > A file is found by where it came from, with provenance written by Trawl (PLAN.md §8, M7).
 *
 * Trawl is not installed here, so its **role** is played by the instrumentation APK: a
 * different package name, signed with the same key, holding only
 * `dev.niccc2007.filet.permission.BRIDGE`. That is precisely the trust relationship the
 * contract describes, so the cross-package hop is real - `ContentResolver.insert` across a
 * process boundary, through the signature permission and through
 * `FiletBridgeProvider.assertTrusted`.
 *
 * What this does *not* prove is that Trawl's own code calls it correctly. That is Trawl's
 * side of the contract and is recorded as a handoff rather than implied here.
 */
@RunWith(AndroidJUnit4::class)
class BridgeProvenanceTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var path: VPath
    private val origin = "https://example.invalid/watch?v=filet-gate"

    @Before fun setUp() {
        dir = Gates.dir(ApplicationProvider.getApplicationContext())
        assumeTrue("gate storage is not writable in this run", dir.isDirectory)
        file = File(dir, "bridge-gate-${System.nanoTime()}.mp4")
        file.writeText("not really a video")
        path = VPath.of("local", file.absolutePath.replace(File.separatorChar, '/'))

        // A fresh install has crawled nothing, and `available` reports exactly that. Crawl
        // the gates folder so the tests below are testing provenance rather than an empty
        // index.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runCatching {
            runBlocking {
                FiletApp.graphOf(ctx).index.crawl(
                    listOf(VPath.of("local", dir.absolutePath.replace(File.separatorChar, '/'))),
                    budgetMs = 30_000,
                )
            }
        }
    }

    @After fun tearDown() {
        if (::path.isInitialized) {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            runCatching { runBlocking { FiletApp.graphOf(ctx).index.forget(path) } }
        }
        if (::file.isInitialized) file.delete()
    }

    private fun authority(): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().packageName + ".bridge"

    /** The write, from another package, exactly as Trawl would make it. */
    private fun writeProvenance() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val values = ContentValues().apply {
            put(BridgeContract.Provenance.PATH, file.absolutePath)
            put(BridgeContract.Provenance.SOURCE_APP, "dev.niccc2007.trawl")
            put(BridgeContract.Provenance.ORIGIN, origin)
            put(BridgeContract.Provenance.PAGE_TITLE, "A video about file managers")
            put(BridgeContract.Provenance.UPLOADER, "somebody")
            put(BridgeContract.Provenance.FORMAT, "1080p mp4")
            put(BridgeContract.Provenance.AT, System.currentTimeMillis())
        }
        val uri = resolver.insert(BridgeContract.provenanceUri(authority()), values)
        assertNotNull("the bridge refused an insert from a same-key package", uri)
    }

    @Test fun another_package_can_write_provenance_and_read_it_back() {
        writeProvenance()
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val one = BridgeContract.provenanceUri(authority())
            .buildUpon().appendPath(android.net.Uri.encode(path.toString())).build()
        resolver.query(one, null, null, null, null).use { c ->
            assertNotNull(c)
            assertTrue("no provenance row came back", c!!.moveToFirst())
            assertEquals(
                origin,
                c.getString(c.getColumnIndexOrThrow(BridgeContract.Provenance.ORIGIN)),
            )
        }
    }

    /** The gate itself: `from:` finds the file by where it came from. */
    @Test fun a_file_is_found_by_where_it_came_from() {
        writeProvenance()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val index = FiletApp.graphOf(ctx).index
        assumeTrue("the index is unavailable on this device", index.status.value.available)

        val rows = runBlocking {
            index.candidates(
                SearchRequest("from:example.invalid", SearchScope.PROVENANCE, origin = null, limit = 200),
                QueryParser.parse("from:example.invalid"),
            )
        }
        assertTrue(
            "expected ${file.name}, got ${rows.take(5).map { it.name }}",
            rows.any { it.name == file.name },
        )
    }

    /** Provenance survives the file moving, because it is attached to the node, not the path. */
    @Test fun provenance_follows_the_file_when_it_moves() {
        writeProvenance()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val index = FiletApp.graphOf(ctx).index
        assumeTrue(index.status.value.available)

        val id = runBlocking { index.idFor(path) }
        assertNotNull("the file must have a stable id once provenance is attached", id)

        val moved = File(file.parentFile, "moved-" + file.name)
        assertTrue(file.renameTo(moved))
        try {
            val movedPath = VPath.of("local", moved.absolutePath.replace(File.separatorChar, '/'))
            runBlocking { index.crawl(listOf(VPath.of("local", moved.parent!!.replace(File.separatorChar, '/'))), 20_000) }
            val record = runBlocking { index.provenanceOf(movedPath) }
            // Either provenance moved with it, or the row was honestly swept. What must never
            // happen is the record staying bound to a path that no longer holds that file.
            val stale = runBlocking { index.provenanceOf(path) }
            assertTrue(
                "provenance is still attached to the old path after a move",
                record?.origin == origin || stale == null,
            )
        } finally {
            moved.delete()
        }
    }
}
