package dev.niccc2007.filet.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A folder's listing added as one batch makes exactly the map that adding it a row at a time
 * makes. The walk adds by batch to keep its hold on the map's lock short; nothing it shows may
 * depend on that.
 */
class SizeMapBatchTest {

    private val root = "/storage/emulated/0"

    /** Folder path to its subfolders and files, walked in this order. */
    private val tree = linkedMapOf(
        root to (
            listOf("$root/DCIM", "$root/Download") to listOf(
                SizeMap.Big("$root/a.txt", 10, SizeKind.DOCUMENTS),
                SizeMap.Big("$root/b.jpg", 500, SizeKind.PICTURES),
            )
            ),
        "$root/DCIM" to (listOf("$root/DCIM/Camera") to emptyList()),
        "$root/DCIM/Camera" to (
            emptyList<String>() to (1..40).map {
                SizeMap.Big("$root/DCIM/Camera/IMG_$it.jpg", (it * 37L) % 11 * 100, if (it % 5 == 0) SizeKind.VIDEOS else SizeKind.PICTURES)
            }
            ),
        "$root/Download" to (
            listOf("$root/Download/x", "$root/Download/y") to listOf(
                SizeMap.Big("$root/Download/film.mkv", 9_000, SizeKind.VIDEOS),
                SizeMap.Big("$root/Download/broken.bin", -1, SizeKind.OTHER),
                SizeMap.Big("$root/Download/same.zip", 500, SizeKind.ARCHIVES),
            )
            ),
        "$root/Download/x" to (emptyList<String>() to listOf(SizeMap.Big("$root/Download/x/one.apk", 70, SizeKind.PACKAGES))),
        "$root/Download/y" to (emptyList<String>() to emptyList()),
    )

    private fun oneByOne(keep: Int) = SizeMap(root, keep).apply {
        for ((_, entry) in tree) {
            entry.first.forEach { addFolder(it) }
            entry.second.forEach { addFile(it.path, it.bytes, it.kind) }
        }
    }

    private fun batched(keep: Int) = SizeMap(root, keep).apply {
        for ((_, entry) in tree) {
            addFolders(entry.first)
            addFiles(entry.second)
        }
    }

    private fun assertSameMap(a: SizeMap, b: SizeMap) {
        assertEquals(a.filesRead, b.filesRead)
        for (folder in tree.keys) {
            assertEquals(folder, a.bytes(folder), b.bytes(folder))
            assertEquals(folder, a.files(folder), b.files(folder))
            assertEquals(folder, a.folders(folder), b.folders(folder))
            assertEquals(folder, a.kinds(folder), b.kinds(folder))
            assertEquals(folder, a.largestUnder(folder, 1_000), b.largestUnder(folder, 1_000))
        }
    }

    @Test fun `a batch makes the map a row at a time makes`() {
        assertSameMap(oneByOne(SizeMap.LARGEST), batched(SizeMap.LARGEST))
    }

    @Test fun `the largest files come out the same when only a few are kept, ties included`() {
        assertSameMap(oneByOne(5), batched(5))
    }

    @Test fun `a file from another folder in the batch is added to its own folders, not the batch's`() {
        val a = SizeMap(root).apply {
            addFolder("$root/A")
            addFolder("$root/B")
            addFile("$root/A/1.bin", 100, SizeKind.OTHER)
            addFile("$root/B/2.bin", 200, SizeKind.OTHER)
            addFile("/elsewhere/3.bin", 300, SizeKind.OTHER)
        }
        val b = SizeMap(root).apply {
            addFolders(listOf("$root/A", "$root/B"))
            addFiles(
                listOf(
                    SizeMap.Big("$root/A/1.bin", 100, SizeKind.OTHER),
                    SizeMap.Big("$root/B/2.bin", 200, SizeKind.OTHER),
                    SizeMap.Big("/elsewhere/3.bin", 300, SizeKind.OTHER),
                ),
            )
        }
        assertEquals(a.filesRead, b.filesRead)
        for (f in listOf(root, "$root/A", "$root/B")) {
            assertEquals(f, a.bytes(f), b.bytes(f))
            assertEquals(f, a.files(f), b.files(f))
            assertEquals(f, a.folders(f), b.folders(f))
        }
        assertEquals(100L, b.bytes("$root/A"))
        assertEquals(200L, b.bytes("$root/B"))
        assertEquals(300L, b.bytes(root))
    }

    @Test fun `an empty batch changes nothing`() {
        val m = SizeMap(root)
        m.addFiles(emptyList())
        m.addFolders(emptyList())
        assertEquals(0, m.filesRead)
        assertEquals(0L, m.bytes(root))
        assertEquals(0, m.folders(root))
    }
}
