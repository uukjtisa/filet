package dev.niccc2007.filet.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SEARCH.md §13.7 and §13.8 against the real database.
 *
 * Two volumes whose roots are a string prefix of one another - `n1` and `n12`, the exact shape
 * connection ids would take - so the same fixture proves attribution (§13.8) and that sweeping
 * one drive leaves nothing of it and everything of the other (§13.7).
 *
 * Runs in the core-index test package, never Filet's own, so it cannot uninstall the app.
 */
@RunWith(AndroidJUnit4::class)
class VolumeSweepOnDeviceTest {

    private lateinit var tmp: File
    private lateinit var a: File
    private lateinit var b: File
    private lateinit var index: SqliteIndex

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "sweep-test-" + System.nanoTime())
        a = File(tmp, "n1").apply { mkdirs() }
        b = File(tmp, "n12").apply { mkdirs() }
        File(a, "one.txt").writeText("x")
        File(a, "sub").mkdirs(); File(a, "sub/two.txt").writeText("x")
        File(b, "three.txt").writeText("x")
        File(b, "deep/er").mkdirs(); File(b, "deep/er/four.txt").writeText("x")
        index = SqliteIndex(context, Vfs(listOf(LocalProvider(listOf(a, b)))))
        runBlocking {
            index.clear()
            index.crawl(listOf(vpath(a), vpath(b)), budgetMs = 20_000)
        }
    }

    @After fun tearDown() {
        if (::index.isInitialized) {
            runCatching { runBlocking { index.clear() } }
            runCatching { index.close() }
        }
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    @Test fun a_root_that_prefixes_another_does_not_take_its_rows() = runBlocking {
        // Before the fix, `n12/three.txt` was looked up under `n1` and not found.
        assertNotNull(index.idFor(vpath(File(b, "three.txt"))))
        assertNotNull(index.idFor(vpath(File(a, "one.txt"))))
    }

    @Test fun sweeping_a_volume_leaves_zero_of_its_rows_and_all_of_the_other() = runBlocking {
        val before = index.status.value.files
        val gone = index.forgetVolumes { uuid -> uuid != vpath(b).toString() }
        assertEquals(1, gone)
        for (f in listOf("three.txt", "deep", "deep/er", "deep/er/four.txt")) {
            assertNull("$f survived the sweep", index.idFor(vpath(File(b, f))))
        }
        assertNull(index.idFor(vpath(b)))
        for (f in listOf("one.txt", "sub", "sub/two.txt")) {
            assertNotNull("$f was swept with the wrong drive", index.idFor(vpath(File(a, f))))
        }
        assertEquals(before - 2, index.status.value.files)
    }

    @Test fun keeping_everything_sweeps_nothing() = runBlocking {
        assertEquals(0, index.forgetVolumes { true })
        assertNotNull(index.idFor(vpath(File(b, "three.txt"))))
    }
}
