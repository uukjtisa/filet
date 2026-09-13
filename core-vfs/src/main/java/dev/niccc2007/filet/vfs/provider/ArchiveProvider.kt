package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A zip (or apk, jar, or anything else with a zip container) browsed as a directory tree.
 *
 * Addressing: `zip:///path/to/archive.zip!/inner/path`. The `!` separator is the JAR-URL
 * convention and keeps both halves readable in a breadcrumb.
 *
 * Read-only for M1. Writing into an archive means rewriting it, which is a different
 * operation with different failure modes and belongs with the APK work in M6.
 *
 * Only the **central directory** is read - `ZipFile` gives every entry's name, size and time
 * without inflating a byte, which is why opening a 4 GB archive is instant.
 */
class ArchiveProvider : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(Capability.READ)

    override suspend fun roots(): List<VNode> = emptyList()

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        openZip(archive, path).use { zip ->
            val prefix = if (inner.isEmpty()) "" else inner.trimEnd('/') + "/"
            val direct = LinkedHashMap<String, VNode>()
            for (e in zip.entries()) {
                val name = e.name
                if (!name.startsWith(prefix) || name == prefix) continue
                val rest = name.substring(prefix.length).trimEnd('/')
                if (rest.isEmpty()) continue
                val cut = rest.indexOf('/')
                if (cut < 0) {
                    direct[rest] = node(path.child(rest), e)
                } else {
                    // An implicit directory: many zips omit explicit folder entries, so a
                    // listing built only from real entries silently loses whole subtrees.
                    val dir = rest.substring(0, cut)
                    direct.getOrPut(dir) {
                        VNode(path.child(dir), isDir = true, size = -1L, mtime = e.time)
                    }
                }
            }
            direct.values.toList()
        }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        if (inner.isEmpty()) {
            val f = File(archive)
            return@withContext if (f.isFile) VNode(path, true, -1L, f.lastModified()) else null
        }
        openZip(archive, path).use { zip ->
            zip.getEntry(inner)?.let { return@use node(path, it) }
            zip.getEntry("$inner/")?.let { return@use node(path, it) }
            // implicit directory: it exists if anything is filed under it
            val prefix = "$inner/"
            if (zip.entries().asSequence().any { it.name.startsWith(prefix) }) {
                VNode(path, isDir = true, size = -1L, mtime = 0L)
            } else null
        }
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        val zip = openZip(archive, path)
        val entry = zip.getEntry(inner) ?: run { zip.close(); throw VfsException.NotFound(path) }
        if (entry.isDirectory) { zip.close(); throw VfsException.IsADirectory(path) }
        // The stream owns the ZipFile: closing the stream must close the archive, or every
        // preview leaks a file handle until GC decides otherwise.
        object : InputStream() {
            private val src = zip.getInputStream(entry)
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); zip.close() }
        }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream =
        throw VfsException.Unsupported("archives are read-only (M6 adds writing)")

    override suspend fun create(path: VPath, isDir: Boolean): VNode =
        throw VfsException.Unsupported("archives are read-only (M6 adds writing)")

    override suspend fun delete(path: VPath, recursive: Boolean): Unit =
        throw VfsException.Unsupported("archives are read-only (M6 adds writing)")

    override suspend fun rename(path: VPath, newName: String): VNode =
        throw VfsException.Unsupported("archives are read-only (M6 adds writing)")

    private fun openZip(archive: String, path: VPath): ZipFile =
        try { ZipFile(File(archive)) }
        catch (e: Exception) { throw VfsException.Io(path, e) }

    private fun node(p: VPath, e: ZipEntry) = VNode(
        path = p,
        isDir = e.isDirectory,
        size = if (e.isDirectory) -1L else e.size,
        mtime = e.time,
        writable = false,
    )

    companion object {
        const val SCHEME = "zip"
        private const val SEP = "!"

        /** `zip:///a/b.zip!/x/y` -> ("/a/b.zip", "x/y") */
        fun split(path: VPath): Pair<String, String> {
            val i = path.path.indexOf(SEP)
            val archive = if (i < 0) path.path else path.path.substring(0, i)
            val inner = if (i < 0) "" else path.path.substring(i + 1).trim('/')
            return osPath(archive) to inner
        }

        /**
         * Undo the forced leading separator on hosts that do not use one.
         *
         * Only ever strips it in front of a `X:` drive letter, so a genuine POSIX path such
         * as `/storage/emulated/0/a.zip` is returned untouched.
         */
        private fun osPath(p: String): String =
            if (p.length > 2 && p[0] == '/' && p[2] == ':') p.substring(1) else p

        /**
         * Build the VPath that opens [archivePath] as a directory.
         *
         * The leading separator is forced: a VPath is absolute by contract, and a host whose
         * paths start with a drive letter would otherwise produce an invalid one.
         */
        fun mount(archivePath: String): VPath {
            val abs = if (archivePath.startsWith("/")) archivePath else "/$archivePath"
            return VPath(SCHEME, "$abs$SEP/")
        }

        /** Extensions treated as browsable containers. */
        val EXTENSIONS = setOf("zip", "apk", "jar", "aar", "apks", "xapk", "apkm", "epub")
    }
}
