package dev.niccc2007.filet.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The index, against the real bundled SQLite on a real device.
 *
 * These cannot be JVM tests: the point is to prove the *bundled* SQLite loads its native
 * library and that FTS5 with the trigram tokenizer is actually present - which is the exact
 * thing SEARCH.md §2.1 refuses to assume.
 */
@RunWith(AndroidJUnit4::class)
class IndexOnDeviceTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var index: SqliteIndex

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "index-test-" + System.nanoTime())
        File(tmp, "Download").mkdirs()
        File(tmp, "DCIM/Camera").mkdirs()
        File(tmp, "Download/report-final.txt").writeText("x")
        File(tmp, "Download/MP-Manager-1.4.apk").writeText("x")
        File(tmp, "DCIM/Camera/IMG_20260913_holiday.jpg").writeText("x")
        File(tmp, "DCIM/Camera/Screenshot_2026.png").writeText("x")

        vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        index = SqliteIndex(context, vfs)
        runBlocking { index.clear() }
    }

    @After fun tearDown() {
        // Defensive: a failure in setUp must surface as ITSELF, not as an uninitialized-property
        // error from teardown running against a half-built fixture.
        if (::index.isInitialized) {
            runCatching { runBlocking { index.clear() } }
            runCatching { index.close() }
        }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun crawl() = runBlocking { index.crawl(listOf(vpath(tmp)), budgetMs = 20_000) }

    @Test fun a_crawl_finds_every_file() {
        val result = crawl()
        assertTrue(result.complete)
        assertTrue("saw ${result.seenFiles}", result.seenFiles >= 6)
        assertTrue(index.status.value.files > 0)
    }

    /** SEARCH.md §2.1: FTS5 with the trigram tokenizer is the reason SQLite is bundled. */
    @Test fun the_bundled_sqlite_has_fts5_trigram() {
        assertTrue(
            "the bundled SQLite must provide FTS5 trigram, or retrieval falls back to a scan",
            index.status.value.ftsAccelerated,
        )
    }

    @Test fun a_query_finds_a_file_by_a_fragment() {
        crawl()
        val hits = runBlocking {
            index.candidates(
                SearchRequest("report", SearchScope.DEVICE, origin = null, limit = 200),
                QueryParser.parse("report"),
            )
        }
        assertTrue(hits.any { it.name == "report-final.txt" })
    }

    /**
     * Words typed with a space must still match a name that joins them with a hyphen.
     *
     * Matching the query as a single FTS phrase is a literal substring test, so
     * `report final` finds nothing in `report-final.txt` - and a user who typed exactly the
     * words in the filename gets an empty list.
     */
    @Test fun a_multi_word_query_matches_a_hyphenated_name() {
        crawl()
        val hits = runBlocking {
            index.candidates(
                SearchRequest("report final", SearchScope.DEVICE, origin = null, limit = 200),
                QueryParser.parse("report final"),
            )
        }
        assertTrue("got ${hits.map { it.name }}", hits.any { it.name == "report-final.txt" })
    }

    /** Each word narrows. A word that is nowhere in the name must exclude the row. */
    @Test fun every_word_in_the_query_has_to_match() {
        crawl()
        val hits = runBlocking {
            index.candidates(
                SearchRequest("report holiday", SearchScope.DEVICE, origin = null, limit = 200),
                QueryParser.parse("report holiday"),
            )
        }
        assertTrue("got ${hits.map { it.name }}", hits.none { it.name == "report-final.txt" })
    }

    /** Trigram cannot accelerate under three characters, so short queries take the prefix index. */
    @Test fun a_two_character_query_still_answers() {
        crawl()
        val hits = runBlocking {
            index.candidates(
                SearchRequest("im", SearchScope.DEVICE, origin = null, limit = 200),
                QueryParser.parse("im"),
            )
        }
        assertTrue(hits.any { it.name.startsWith("IMG_") })
    }

    @Test fun a_scoped_search_only_returns_that_folder() {
        crawl()
        val dcim = vpath(File(tmp, "DCIM/Camera"))
        val hits = runBlocking {
            index.candidates(
                SearchRequest("", SearchScope.FOLDER, origin = dcim, limit = 200),
                QueryParser.parse(""),
            )
        }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { it.name == "report-final.txt" })
    }

    // ── stable identity: the rule pinned shortcuts depend on ──

    @Test fun a_file_has_an_id_that_resolves_back_to_its_path() {
        crawl()
        val path = vpath(File(tmp, "Download/report-final.txt"))
        val id = runBlocking { index.idFor(path) }
        assertNotNull(id)
        assertEquals(path, runBlocking { index.pathFor(id!!) })
    }

    /**
     * **The shortcut rule.** Move the file, the ID still resolves - to the new location.
     * This is the behaviour M4 is built on and the reason M4 could not precede M3.
     */
    @Test fun an_id_survives_a_move() {
        crawl()
        val before = vpath(File(tmp, "Download/report-final.txt"))
        val id = runBlocking { index.idFor(before) }!!

        File(tmp, "Archive").mkdirs()
        File(tmp, "Download/report-final.txt").renameTo(File(tmp, "Archive/report-final.txt"))
        crawl()

        // The SAME id, now pointing at the new location. Not "a valid id" - the same one,
        // because the shortcut on the launcher is still holding the old number.
        val after = runBlocking { index.pathFor(id) }
        assertNotNull("the id died with the move - every shortcut to this file would too", after)
        assertEquals(vpath(File(tmp, "Archive/report-final.txt")), after)
        assertEquals(id, runBlocking { index.idFor(vpath(File(tmp, "Archive/report-final.txt"))) })
        assertNull("the old path must not still resolve", runBlocking { index.idFor(before) })
    }

    /** Whatever attached itself to the file has to make the journey too. */
    @Test fun provenance_and_frecency_survive_a_move() {
        crawl()
        val before = vpath(File(tmp, "Download/report-final.txt"))
        runBlocking {
            index.putProvenance(before, Provenance(sourceApp = "dev.niccc2007.trawl", origin = "https://example.invalid/x"))
            index.recordOpen(before)
        }
        val id = runBlocking { index.idFor(before) }!!

        File(tmp, "Archive").mkdirs()
        File(tmp, "Download/report-final.txt").renameTo(File(tmp, "Archive/report-final.txt"))
        crawl()

        val after = vpath(File(tmp, "Archive/report-final.txt"))
        assertEquals(id, runBlocking { index.idFor(after) })
        assertEquals(
            "https://example.invalid/x",
            runBlocking { index.provenanceOf(after) }?.origin,
        )
    }

    /**
     * A deletion that *looks* like a move: an identical twin sitting in another folder, same
     * name, same size, same mtime. On a real filesystem the inodes differ and settle it
     * outright; on a backend with no inode the one-to-one rule is what stops it. Either way
     * the id must die rather than quietly re-point at the twin - a shortcut opening the
     * wrong file is worse than one that says the file is gone.
     */
    @Test fun an_ambiguous_move_is_not_guessed_at() {
        val twinA = File(tmp, "Download/twin.txt")
        val twinB = File(tmp, "DCIM/twin.txt")
        twinA.writeText("same")
        twinB.writeText("same")
        twinB.setLastModified(twinA.lastModified())
        crawl()

        val idA = runBlocking { index.idFor(vpath(twinA)) }!!
        twinA.delete()
        crawl()

        val resolved = runBlocking { index.pathFor(idA) }
        assertTrue(
            "a deleted file was silently relinked to its twin: $resolved",
            resolved == null || resolved == vpath(twinA),
        )
    }

    @Test fun a_deleted_file_is_swept_by_the_generation_pass() {
        crawl()
        val path = vpath(File(tmp, "Download/report-final.txt"))
        assertNotNull(runBlocking { index.idFor(path) })

        File(tmp, "Download/report-final.txt").delete()
        crawl()

        assertNull("a deleted file must not survive a complete crawl", runBlocking { index.idFor(path) })
    }

    // ── provenance: the thing nothing else on Android has ──

    @Test fun provenance_round_trips() {
        crawl()
        val path = vpath(File(tmp, "DCIM/Camera/IMG_20260913_holiday.jpg"))
        val record = Provenance(
            sourceApp = "dev.niccc2007.trawl",
            origin = "https://youtube.com/watch?v=abc",
            pageTitle = "A video",
            uploader = "someone",
            format = "1080p",
        )
        runBlocking { index.putProvenance(path, record) }
        val read = runBlocking { index.provenanceOf(path) }
        assertNotNull(read)
        assertEquals("https://youtube.com/watch?v=abc", read!!.origin)
        assertEquals("1080p", read.format)
    }

    @Test fun search_by_origin_finds_it() {
        crawl()
        val path = vpath(File(tmp, "DCIM/Camera/IMG_20260913_holiday.jpg"))
        runBlocking {
            index.putProvenance(path, Provenance("app", "https://youtube.com/watch?v=abc"))
        }
        val rows = runBlocking { index.byOrigin("youtube", 20) }
        assertTrue(rows.any { it.name.contains("holiday") })
    }

    @Test fun frecency_counts_real_opens_only() {
        crawl()
        val path = vpath(File(tmp, "Download/report-final.txt"))
        runBlocking { index.recordOpen(path); index.recordOpen(path) }
        val rows = runBlocking {
            index.candidates(
                SearchRequest("report", SearchScope.DEVICE, origin = null, limit = 50),
                QueryParser.parse("report"),
            )
        }
        val row = rows.first { it.name == "report-final.txt" }
        assertEquals(2, row.opens)
    }

    @Test fun clearing_the_index_empties_it() {
        crawl()
        assertTrue(index.status.value.files > 0)
        runBlocking { index.clear() }
        assertEquals(0L, index.status.value.files)
        assertFalse(index.status.value.available)
    }

    /** A crawl that runs out of budget must not sweep, or it would delete what it never saw. */
    @Test fun a_truncated_crawl_reports_incomplete_and_keeps_rows() {
        crawl()
        val before = index.status.value.files
        val result = runBlocking { index.crawl(listOf(vpath(tmp)), budgetMs = 0) }
        assertFalse(result.complete)
        assertTrue("a truncated crawl must not delete anything", index.status.value.files >= before)
    }
}
