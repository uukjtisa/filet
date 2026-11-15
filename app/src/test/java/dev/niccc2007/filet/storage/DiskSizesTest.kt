package dev.niccc2007.filet.storage

import dev.niccc2007.filet.browser.FolderTotal
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The copy of the walk a folder is drawn from: what each row reads, and the `+` on a total. */
class DiskSizesTest {

    private val here = VPath.of("file", "/storage/emulated/0")
    private fun dir(name: String) = VNode(here.child(name), isDir = true, size = -1L, mtime = 0L)
    private fun file(name: String, size: Long) = VNode(here.child(name), isDir = false, size = size, mtime = 0L)

    private val music = dir("Music")
    private val movies = dir("Movies")

    private fun map() = SizeMap(here.toString()).apply {
        addFolders(listOf(music.path.toString(), movies.path.toString()))
        addFiles(listOf(SizeMap.Big(music.path.child("a.mp3").toString(), 300, SizeKind.AUDIO)))
        addFiles(listOf(SizeMap.Big(movies.path.child("b.mkv").toString(), 700, SizeKind.VIDEOS)))
        settle(music.path.toString())
    }

    private fun read(walking: Boolean, rows: List<VNode> = listOf(music, movies, file("x.txt", 5))): DiskSizes {
        val dirs = rows.filter { it.isDir }.map { it.path to it.path.toString() }
        return DiskSizes.read(map(), here, here.toString(), dirs, walking)
    }

    @Test fun `a folder row reads the walk, a file row its own size`() {
        val d = read(walking = true)
        assertEquals(300L, d.sizeOf(music))
        assertEquals(700L, d.sizeOf(movies))
        assertEquals(5L, d.sizeOf(file("x.txt", 5)))
        assertNull(d.sizeOf(file("y.txt", -1)))
        assertEquals(1_000L, d.total)
        assertEquals(2, d.filesRead)
        assertEquals(2, d.filesHere)
    }

    @Test fun `a folder not among the rows read is not known, even if the walk has it`() {
        val d = read(walking = true, rows = listOf(music))
        assertNull(d.sizeOf(movies))
        assertEquals(FolderTotal.Unknown, d.totalOf(movies.path))
    }

    @Test fun `a total is settled when its folder is finished, otherwise a floor that says whether it is still moving`() {
        val running = read(walking = true)
        assertEquals(FolderTotal.Settled(300), running.totalOf(music.path))
        assertEquals(FolderTotal.Confirming(700), running.totalOf(movies.path))
        val stopped = read(walking = false)
        assertEquals(FolderTotal.Settled(300), stopped.totalOf(music.path))
        assertEquals(FolderTotal.AtLeast(700), stopped.totalOf(movies.path))
    }

    @Test fun `each row's share is of this folder's total`() {
        val d = read(walking = false)
        assertEquals(0.3f, d.share(music), 0.0001f)
        assertEquals("70%", d.percent(movies))
    }

    @Test fun `a row reads its figure through the totals, and nothing before the first copy`() = runBlocking {
        val flow = MutableStateFlow<DiskSizes?>(null)
        val totals = DiskTotals(flow)
        assertEquals(FolderTotal.Unknown, totals.of(movies.path))
        assertEquals(FolderTotal.Unknown, totals.watch(movies.path).first())
        flow.value = read(walking = true)
        assertEquals(FolderTotal.Confirming(700), totals.watch(movies.path).first())
        assertEquals(FolderTotal.Settled(300), totals.of(music.path))
    }
}
