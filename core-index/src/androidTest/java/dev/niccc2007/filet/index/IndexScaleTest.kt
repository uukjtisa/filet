package dev.niccc2007.filet.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * M3's exit criterion, measured rather than asserted.
 *
 * > A 100k-file device is searched in under one second (PLAN.md §8, M3).
 *
 * The number that matters is the one a person feels: time from "query string" to "list of
 * verified hits". So this measures [IndexSearchSource] end to end - SQL candidates, fuzzy
 * rerank, frecency, and the `stat` verification pass that SEARCH.md §4.3 requires before
 * anything is painted - not a bare `SELECT`.
 *
 * The corpus is a hundred thousand **real files on the device's own filesystem**. Stuffing
 * rows straight into SQLite would measure the database and nothing else; the crawler, the
 * path reassembly and the verification `stat` all have to face the real thing.
 */
@RunWith(AndroidJUnit4::class)
class IndexScaleTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var index: SqliteIndex
    private lateinit var source: IndexSearchSource

    /** 1,000 directories x 100 files. Deep enough that path reassembly is exercised. */
    private val dirs = 1_000
    private val perDir = 100

    private lateinit var needleDir: File
    private var crawlMs = 0L
    private var buildMs = 0L

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    private fun bucketFor(d: Int) = File(tmp, "bucket-%02d/set-%03d".format(d % 20, d))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // filesDir, not cacheDir: the system is free to delete a cache directory at any
        // moment, and a corpus this size takes long enough to build that a clear-out
        // mid-crawl would read as an index bug rather than the platform doing its job.
        tmp = File(context.filesDir, "index-scale-" + System.nanoTime())
        assertTrue("could not create the corpus root", tmp.mkdirs())

        val stat = android.os.StatFs(tmp.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        assumeTrue(
            "not enough free space for a %d-file corpus (%d MB free)".format(dirs * perDir, freeBytes / 1024 / 1024),
            freeBytes > 900L * 1024 * 1024,
        )

        val built = System.currentTimeMillis()
        val names = arrayOf(
            "invoice", "holiday", "screenshot", "backup", "lecture",
            "receipt", "podcast", "roster", "manifest", "sample",
        )
        val exts = arrayOf(".txt", ".jpg", ".pdf", ".mp3", ".apk")
        for (d in 0 until dirs) {
            val dir = bucketFor(d)
            check(dir.mkdirs() || dir.isDirectory) { "could not create $dir" }
            for (f in 0 until perDir) {
                val n = d * perDir + f
                // The two cycles are deliberately co-prime-ish: 10 names against 5
                // extensions taken every 7th file, so `ext:pdf invoice` actually has
                // matches. Indexing both by n directly couples them and the combination
                // never occurs - a filter test that can only ever return nothing.
                val name = "${names[n % names.size]}-$n${exts[(n / 7) % exts.size]}"
                // createNewFile beats writeText by a wide margin at this count, and size is
                // irrelevant here - the index stores metadata, never content.
                val f = File(dir, name)
                try {
                    f.createNewFile()
                } catch (e: java.io.IOException) {
                    // Name the file and the count. A bare "No such file or directory" from
                    // inside a loop that has already written tens of thousands of files says
                    // nothing about whether the problem is the path, the quota or the inodes.
                    throw AssertionError("corpus build failed at file #" + n + " (" + f + "): " + e.message, e)
                }
            }
        }
        // One needle with a distinctive name, so a hit can be asserted and not just counted,
        // and one file big enough to satisfy a `size:` filter without writing 4 MB of zeroes.
        // The folder is derived, never spelled out - writing the bucket by hand gets it wrong
        // the moment the arithmetic above changes.
        needleDir = bucketFor(137)
        File(needleDir, NEEDLE).createNewFile()
        RandomAccessFile(File(needleDir, "big-export.csv"), "rw").use { it.setLength(4L * 1024 * 1024) }

        buildMs = System.currentTimeMillis() - built
        android.util.Log.i(TAG, "built ${dirs * perDir + 2} files in $buildMs ms")

        vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        index = SqliteIndex(context, vfs)
        source = IndexSearchSource(index, vfs)
        runBlocking { index.clear() }

        val crawled = System.currentTimeMillis()
        val result = runBlocking { index.crawl(listOf(vpath(tmp)), budgetMs = 300_000) }
        crawlMs = System.currentTimeMillis() - crawled
        android.util.Log.i(
            TAG,
            "crawled ${result.seenFiles} files in ${System.currentTimeMillis() - crawled} ms " +
                "(complete=${result.complete})",
        )
        assertTrue(
            "the corpus must be fully indexed before latency means anything - saw ${result.seenFiles}",
            result.complete && result.seenFiles >= dirs * perDir,
        )
    }

    @After fun tearDown() {
        if (::index.isInitialized) {
            runCatching { runBlocking { index.clear() } }
            runCatching { index.close() }
        }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun timeSearch(query: String): Pair<Long, Int> {
        val started = System.nanoTime()
        val hits = runBlocking {
            source.search(SearchRequest(query, SearchScope.DEVICE, origin = null, limit = 500)).toList()
        }
        return (System.nanoTime() - started) / 1_000_000 to hits.size
    }

    /**
     * The gate. Every query shape SEARCH.md calls out, over the full corpus, each under a
     * second - including the two-character case that trigram cannot accelerate.
     */
    @Test fun a_hundred_thousand_files_are_searched_in_under_a_second() {
        val queries = listOf(
            "quarterly",          // long, distinctive, one hit
            "invoice",            // long, tens of thousands of hits - the worst retrieval case
            "in",                 // 2 chars: prefix index, not trigram
            "rec",                // 3 chars: the first query trigram can touch
            "recon 2026",         // multi-token
            "ext:pdf invoice",    // filtered on a field the parser has to push into SQL
            "size:>1mb export",   // filtered on a fact, not the name
        )

        val timings = LinkedHashMap<String, Long>()
        val counts = LinkedHashMap<String, Int>()
        for (q in queries) {
            timeSearch(q)                                     // warm the statement cache
            val (ms, count) = timeSearch(q)
            timings[q] = ms
            counts[q] = count
            android.util.Log.i(TAG, "query %-16s %5d ms  %d hits".format(q, ms, count))
        }

        // A filtered query that can only ever return nothing proves nothing.
        assertTrue("ext:pdf invoice matched no files - the fixture, not the filter", counts["ext:pdf invoice"]!! > 0)

        val worst = timings.maxByOrNull { it.value }!!
        android.util.Log.i(TAG, "WORST ${worst.key} = ${worst.value} ms over ${dirs * perDir} files")

        // Write the measurement out. A gate that says "measured, not asserted" needs an
        // artifact somebody can read afterwards - logcat has rolled over by the time a
        // hundred-thousand-file crawl finishes.
        val report = buildString {
            appendLine("M3 - search latency over a real corpus")
            appendLine("device:   " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " (API " + android.os.Build.VERSION.SDK_INT + ")")
            appendLine("corpus:   " + (dirs * perDir) + " files in " + dirs + " directories")
            appendLine("indexed:  " + index.status.value.files + " rows, fts trigram=" +
                index.status.value.ftsAccelerated)
            appendLine("built in:  " + buildMs + " ms")
            appendLine("crawled:   " + crawlMs + " ms (complete pass)")
            appendLine()
            appendLine("query                 warm ms   hits")
            for ((q, ms) in timings) appendLine("%-20s %7d   %s".format(q, ms, counts[q]))
            appendLine()
            appendLine("slowest: " + worst.key + " at " + worst.value + " ms (gate: < 1000 ms)")
        }
        // Written where the HOST can still read it after the run. The test package is
        // uninstalled the moment the suite finishes, so anything under its own data directory
        // is gone before it can be collected; Download survives, and every app may create its
        // own files there without a permission. Overwriting a file another package left there
        // is what fails on API 30+, so the name carries the writer.
        File(tmp.parentFile, "m3-latency.txt").writeText(report)
        runCatching {
            // Download is the one shared folder any app may create its own files in without a
            // permission - but it may not overwrite a file some other uid left there, and a
            // reinstall changes this package's uid. So: remove, then write.
            val shared = File("/storage/emulated/0/Download/filet-gates")
            shared.mkdirs()
            val out = File(shared, "m3-latency-index.txt")
            out.delete()
            out.writeText(report)
        }
        for (line in report.lines()) android.util.Log.i(TAG, line)
        assertTrue(
            "M3 gate: slowest query was ${worst.key} at ${worst.value} ms over ${dirs * perDir} files",
            worst.value < 1_000,
        )
    }

    /** Fast and wrong is not a pass. The corpus must actually be findable. */
    @Test fun the_needle_is_found_in_the_haystack() {
        val hits = runBlocking {
            source.search(SearchRequest("quarterly recon", SearchScope.DEVICE, origin = null, limit = 500)).toList()
        }
        assertTrue(
            "expected the distinctive file, got ${hits.take(5).map { it.node.name }}",
            hits.any { it.node.name == NEEDLE },
        )
    }

    /** SEARCH.md §4.3: a row that no longer exists on disk must never reach the screen. */
    @Test fun a_file_deleted_after_indexing_is_not_returned() {
        val victim = File(needleDir, NEEDLE)
        assertTrue(victim.delete())
        val hits = runBlocking {
            source.search(SearchRequest("quarterly recon", SearchScope.DEVICE, origin = null, limit = 500)).toList()
        }
        assertTrue(
            "a deleted file was painted from the index: ${hits.map { it.node.name }}",
            hits.none { it.node.name == NEEDLE },
        )
    }

    private companion object {
        const val TAG = "FiletScale"
        const val NEEDLE = "quarterly-reconciliation-2026.xlsx"
    }
}
