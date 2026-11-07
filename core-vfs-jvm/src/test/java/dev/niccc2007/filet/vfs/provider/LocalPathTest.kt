package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** A whole drive opens at its root, never at the folder the program happens to run from. */
class LocalPathTest {

    @Test
    fun `a drive root keeps its separator on Windows`() {
        val root = VPath.of(LocalProvider.SCHEME, "D:/").path
        assertEquals("/D:", root)
        assertEquals("D:\\", LocalProvider.fileString(root, windows = true))
        assertEquals("c:\\", LocalProvider.fileString("/c:", windows = true))
    }

    @Test
    fun `everything else is left alone`() {
        assertEquals("/D:/Pictures", LocalProvider.fileString("/D:/Pictures", windows = true))
        assertEquals("/storage/emulated/0", LocalProvider.fileString("/storage/emulated/0", windows = true))
        assertEquals("/D:", LocalProvider.fileString("/D:", windows = false))
        assertEquals("/", LocalProvider.fileString("/", windows = true))
    }
}
