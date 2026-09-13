package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.niccc2007.filet.apk.ApkFacts
import dev.niccc2007.filet.apk.ApkTools
import dev.niccc2007.filet.index.ArchiveFacts
import dev.niccc2007.filet.index.QueryParser
import dev.niccc2007.filet.index.SearchRequest
import dev.niccc2007.filet.index.SearchScope
import dev.niccc2007.filet.index.SqliteIndex
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Searching for what is *inside* a file (FEATURES.md F59, F60, F62).
 *
 * > `pkg:` `label:` `perm:` via ARSCLib; `class:` as deduped package prefixes via dexlib2.
 * > **Nothing else on Android does this.**
 *
 * These query fields parsed long before anything populated them, which is the exact shape of
 * a dead switch: a user types `pkg:whatsapp`, gets an empty list, and concludes the search is
 * broken. So each field here is asserted to find a real file *and* to reject one that does
 * not match - a filter that always says yes is no better than one that always says no.
 */
@RunWith(AndroidJUnit4::class)
class ContainerSearchTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var index: SqliteIndex

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "container-" + System.nanoTime()).apply { mkdirs() }

        // A real, installable APK - the same 8 KB fixture the M6 gate rebuilds.
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.apk").use { input ->
            File(tmp, "sample.apk").outputStream().use { input.copyTo(it) }
        }

        ZipOutputStream(File(tmp, "bundle.zip").outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("docs/contract-2026.pdf")); zos.write("x".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("src/Main.kt")); zos.write("y".toByteArray()); zos.closeEntry()
        }
        ZipOutputStream(File(tmp, "other.zip").outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("notes/readme.txt")); zos.write("z".toByteArray()); zos.closeEntry()
        }

        vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        index = SqliteIndex(
            context, vfs,
            extractors = listOf(ApkFacts(vfs, ApkTools(context, vfs, JobLedger())), ArchiveFacts(vfs)),
        )
        runBlocking { index.clear() }
    }

    @After fun tearDown() {
        if (::index.isInitialized) {
            runCatching { runBlocking { index.clear() } }
            runCatching { index.close() }
        }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun crawl() = runBlocking { index.crawl(listOf(vpath(tmp)), budgetMs = 60_000) }

    private fun names(query: String): List<String> = runBlocking {
        index.candidates(
            SearchRequest(query, SearchScope.DEVICE, origin = null, limit = 200),
            QueryParser.parse(query),
        ).map { it.name }
    }

    @Test fun the_crawl_reports_what_it_looked_inside() {
        val result = crawl()
        assertTrue("nothing was parsed: ${result.factsWritten}", result.factsWritten >= 3)
        assertTrue("the index must admit it can answer container queries", index.status.value.containerFacts)
    }

    // ── F59: inside an APK ──

    @Test fun an_apk_is_found_by_its_package_name() {
        crawl()
        assertEquals(listOf("sample.apk"), names("pkg:dev.niccc2007.fixture"))
        assertTrue("a package that is not there must not match", names("pkg:com.whatsapp").isEmpty())
    }

    @Test fun an_apk_is_found_by_a_class_it_contains() {
        crawl()
        assertTrue("got ${names("class:niccc2007")}", names("class:niccc2007").contains("sample.apk"))
        assertTrue(names("class:com.squareup.okhttp3").isEmpty())
    }

    /** The prefix rule: a whole package, not a hundred thousand descriptors. */
    @Test fun class_facts_are_stored_as_package_prefixes_not_every_class() {
        crawl()
        val id = runBlocking { index.idFor(vpath(File(tmp, "sample.apk"))) }!!
        val classes = runBlocking { index.innerOf(id, "class") }
        assertTrue("expected prefixes, got $classes", classes.isNotEmpty())
        assertTrue(
            "a full class descriptor leaked into the index: $classes",
            classes.none { it.endsWith("MainActivity") || it.endsWith("Fixture") },
        )
    }

    // ── F60: inside an archive ──

    @Test fun an_archive_is_found_by_a_file_inside_it() {
        crawl()
        assertEquals(listOf("bundle.zip"), names("inzip:contract-2026"))
        assertEquals(listOf("other.zip"), names("inzip:readme"))
        assertTrue(names("inzip:nothing-is-called-this").isEmpty())
    }

    /** Never inflated: the fact comes from the central directory, so the bytes are untouched. */
    @Test fun archive_facts_do_not_read_the_contents() {
        crawl()
        val id = runBlocking { index.idFor(vpath(File(tmp, "bundle.zip"))) }!!
        val entries = runBlocking { index.innerOf(id, "entry") }
        assertEquals(listOf("docs/contract-2026.pdf", "src/Main.kt"), entries.sorted())
    }

    // ── F62: duplicates ──

    @Test fun dup_finds_identical_files_and_leaves_the_rest_alone() {
        val body = ByteArray(8192) { (it % 251).toByte() }
        File(tmp, "photo-original.bin").writeBytes(body)
        File(tmp, "photo-copy.bin").writeBytes(body)
        // Same size, different content: the case a size-only "duplicate finder" gets wrong.
        File(tmp, "photo-lookalike.bin").writeBytes(ByteArray(8192) { ((it + 7) % 251).toByte() })
        crawl()

        val groups = runBlocking { index.duplicates(minSize = 1024) }
        val found = groups.map { g -> g.map { it.name }.sorted() }
        assertTrue(
            "expected the two identical files as one group, got $found",
            found.any { it == listOf("photo-copy.bin", "photo-original.bin") },
        )
        assertTrue(
            "a same-size-different-content file was called a duplicate: $found",
            found.none { it.contains("photo-lookalike.bin") },
        )

        val hits = names("dup:1")
        assertTrue("dup: returned $hits", hits.containsAll(listOf("photo-copy.bin", "photo-original.bin")))
        assertTrue("dup: must not return every file: $hits", !hits.contains("photo-lookalike.bin"))
    }

    /** A crawl that finds nothing new must not re-parse - that is what makes this affordable. */
    @Test fun an_unchanged_file_is_not_parsed_twice() {
        val first = crawl()
        assertTrue(first.factsWritten > 0)
        val second = crawl()
        assertEquals("nothing changed, so nothing should have been re-parsed", 0, second.factsWritten)
    }
}
