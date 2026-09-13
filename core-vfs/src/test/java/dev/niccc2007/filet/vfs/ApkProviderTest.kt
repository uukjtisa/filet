package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ApkProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The zip half of [ApkProvider]: addressing, and the rule that makes the feature work -
 * **a `.dex` entry is reported as a directory, so the browser walks into it.**
 *
 * The disassembly half needs a real dex and is covered on-device, where a genuine APK is
 * available; a hand-rolled fake dex here would test the fake, not the provider.
 */
class ApkProviderTest {

    private lateinit var tmp: File
    private lateinit var apk: File
    private lateinit var vfs: Vfs

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-apk-" + System.nanoTime())
        check(tmp.mkdirs())
        apk = File(tmp, "sample.apk")
        ZipOutputStream(apk.outputStream()).use { z ->
            fun put(name: String, body: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry()
            }
            put("AndroidManifest.xml", ByteArray(64))
            put("classes.dex", ByteArray(256))
            put("classes2.dex", ByteArray(128))
            put("res/layout/main.xml", ByteArray(32))
            put("lib/arm64-v8a/libnative.so", ByteArray(16))
            put("META-INF/CERT.RSA", ByteArray(8))
        }
        vfs = Vfs(listOf(ApkProvider()))
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun root() = ApkProvider.mount(apk.absolutePath.replace(File.separatorChar, '/'))

    @Test fun lists_the_apk_as_a_folder() = runTest {
        val names = vfs.list(root()).map { it.name }.sorted()
        assertEquals(listOf("AndroidManifest.xml", "META-INF", "classes.dex", "classes2.dex", "lib", "res"), names)
    }

    /** The whole product feel: tap a dex, walk into it. */
    @Test fun a_dex_entry_is_a_directory() = runTest {
        val dex = vfs.list(root()).first { it.name == "classes.dex" }
        assertTrue("a dex must be walkable, not openable", dex.isDir)
    }

    @Test fun an_ordinary_entry_is_a_file() = runTest {
        val manifest = vfs.list(root()).first { it.name == "AndroidManifest.xml" }
        assertFalse(manifest.isDir)
        assertEquals(64L, manifest.size)
    }

    @Test fun implicit_directories_are_reconstructed() = runTest {
        val lib = vfs.list(root()).first { it.name == "lib" }
        assertTrue(lib.isDir)
        assertEquals(listOf("arm64-v8a"), vfs.list(lib.path).map { it.name })
    }

    @Test fun reads_an_ordinary_entry() = runTest {
        val bytes = vfs.openRead(root().child("AndroidManifest.xml")).use { it.readBytes() }
        assertEquals(64, bytes.size)
    }

    @Test fun stat_of_the_apk_root_is_a_directory() = runTest {
        val node = vfs.stat(root())
        assertNotNull(node)
        assertTrue(node!!.isDir)
    }

    @Test fun addressing_round_trips() {
        val host = apk.absolutePath.replace(File.separatorChar, '/')
        val mounted = ApkProvider.mount(host)
        assertEquals(host, ApkProvider.archiveOf(mounted))
        assertEquals("", ApkProvider.innerOf(mounted))
        assertEquals("classes.dex", ApkProvider.innerOf(mounted.child("classes.dex")))
    }

    @Test fun a_class_path_inside_a_dex_is_addressable() {
        val p = VPath(ApkProvider.SCHEME, "/a/b.apk!/classes.dex/com/example/Foo.smali")
        assertEquals("/a/b.apk", ApkProvider.archiveOf(p))
        assertEquals("classes.dex/com/example/Foo.smali", ApkProvider.innerOf(p))
    }

    @Test fun writing_is_refused_rather_than_corrupting_the_apk() = runTest {
        val e = runCatching { vfs.create(root().child("x.txt"), isDir = false) }.exceptionOrNull()
        assertTrue(e is VfsException.Unsupported)
    }
}
