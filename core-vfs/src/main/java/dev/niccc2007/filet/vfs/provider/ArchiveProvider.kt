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

/**
 * An archive browsed as a directory tree: zip, tar, the tar-plus-compressor family, 7z, and a
 * single compressed file.
 *
 * Addressing: `zip:///path/to/archive.tar.gz!/inner/path`. The `!` separator is the JAR-URL
 * convention and keeps both halves readable in a breadcrumb. The scheme stays `zip` even for a
 * tar, because it is the *mount* that is being named and every pinned shortcut and saved tab
 * already spells it that way - renaming it to be tidier would break all of them.
 *
 * Which formats, and what each one costs, is [Archives] and `ArchiveReaders.kt`. Read-only:
 * writing INTO an archive means rewriting it, which is a different operation with different
 * failure modes. Creating a NEW archive is a different thing again and lives in the app layer,
 * because it reads from the VFS and writes to the VFS rather than editing one in place.
 */
class ArchiveProvider : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(Capability.READ)

    override suspend fun roots(): List<VNode> = emptyList()

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        val members = membersOf(archive, path)
        val prefix = if (inner.isEmpty()) "" else inner.trimEnd('/') + "/"
        val direct = LinkedHashMap<String, VNode>()
        for (m in members) {
            if (!m.name.startsWith(prefix) || m.name == prefix.trimEnd('/')) continue
            val rest = m.name.substring(prefix.length).trimEnd('/')
            if (rest.isEmpty()) continue
            val cut = rest.indexOf('/')
            if (cut < 0) {
                if (m.isDir) {
                    direct.getOrPut(rest) { VNode(path.child(rest), true, -1L, m.mtime, writable = false) }
                } else {
                    direct[rest] = VNode(path.child(rest), false, m.size, m.mtime, writable = false)
                }
            } else {
                // An implicit directory. Most zips omit explicit folder entries and most tars
                // include them, so a listing built only from real entries loses whole subtrees
                // on one format and duplicates them on the other.
                val dir = rest.substring(0, cut)
                direct.getOrPut(dir) { VNode(path.child(dir), true, -1L, m.mtime, writable = false) }
            }
        }
        direct.values.toList()
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        if (inner.isEmpty()) {
            val f = File(archive)
            return@withContext if (f.isFile) VNode(path, true, -1L, f.lastModified()) else null
        }
        val members = membersOf(archive, path)
        members.firstOrNull { it.name == inner }?.let {
            return@withContext VNode(path, it.isDir, it.size, it.mtime, writable = false)
        }
        val prefix = "$inner/"
        if (members.any { it.name.startsWith(prefix) }) {
            VNode(path, isDir = true, size = -1L, mtime = 0L, writable = false)
        } else null
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        val reader = readerOf(archive, path)
        val stream = runCatching { reader.open(inner) }.getOrElse { throw VfsException.Io(path, it) }
        stream ?: throw VfsException.NotFound(path)
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream =
        throw VfsException.Unsupported(WRITE_REFUSAL)

    override suspend fun create(path: VPath, isDir: Boolean): VNode =
        throw VfsException.Unsupported(WRITE_REFUSAL)

    override suspend fun delete(path: VPath, recursive: Boolean): Unit =
        throw VfsException.Unsupported(WRITE_REFUSAL)

    override suspend fun rename(path: VPath, newName: String): VNode =
        throw VfsException.Unsupported(WRITE_REFUSAL)

    /**
     * The reader for an archive, or a refusal that names the reason.
     *
     * A format Filet deliberately does not read - RAR - fails with its own sentence rather
     * than with "not found", because "not found" on a file you can see is the least useful
     * error an app can produce.
     */
    private fun readerOf(archive: String, path: VPath): ArchiveReader {
        val asked = File(archive)

        // Tapping any part of a multi-part set opens the whole archive. Which file actually
        // does the opening differs per scheme, and for a numbered set the parts have to be
        // joined first - see MultiPartOpen.
        val set = MultiPartOpen.resolve(asked)
        set.refusal?.let { throw VfsException.Unsupported(it) }

        val file = when {
            set.join != null -> JoinedSets.materialise(set.setName ?: asked.name, set.join)
            else -> set.openWith
        }

        val format = Archives.of(file.name)
        format?.refusal?.let { throw VfsException.Unsupported(it) }
        if (format == null) throw VfsException.Unsupported("${asked.name} is not an archive Filet knows.")

        // A zip volume set needs a reader that follows volumes; the ordinary one looks for the
        // central directory in the file it was handed and a set puts it in the last part.
        if (set.style == PartStyle.ZIP_VOLUMES) return SplitZipReader(file)

        return readerFor(file, format) ?: throw VfsException.Unsupported(format.label + " is not readable here.")
    }

    private fun membersOf(archive: String, path: VPath): List<Member> =
        runCatching { readerOf(archive, path).members() }.getOrElse {
            if (it is VfsException) throw it else throw VfsException.Io(path, it)
        }

    companion object {
        const val SCHEME = "zip"
        private const val SEP = "!"

        private const val WRITE_REFUSAL =
            "Filet does not edit an archive in place. Extract it, change it, and compress it again."

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

        /**
         * Extensions treated as browsable containers.
         *
         * Kept for callers that only have an extension to hand. **Prefer
         * [Archives.canList] with the whole name** - this set cannot see a `.tar.gz`, because
         * by the time a name is reduced to its last dot that archive is a "gz".
         */
        val EXTENSIONS: Set<String> =
            Archives.ALL.filter { it.canList }.flatMapTo(HashSet()) { it.extensions }
    }
}
