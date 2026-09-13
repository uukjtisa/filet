package dev.niccc2007.filet.vfs.provider

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliFormatter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.util.zip.ZipFile

/**
 * An APK browsed as a tree, with **`classes.dex` as a folder of smali**.
 *
 * > The APK-as-a-filesystem provider is the whole product feel (PLAN.md L0). You browse into
 * > an APK like a folder, tap a dex, and it opens as smali.
 *
 * That is a provider, not a feature - which is why the VFS had to exist before any of it.
 * Everything above L0 is unchanged: the browser lists a dex the same way it lists a directory,
 * and the editor opens a `.smali` entry the same way it opens a file.
 *
 * Addressing: `apk:///path/to/app.apk!/classes.dex/com/example/Foo.smali`.
 *
 * Read-only. Writing means rebuilding the dex and re-signing the APK, which is a deliberate,
 * user-initiated operation with its own progress and failure modes, not a silent `openWrite`.
 */
class ApkProvider : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(Capability.READ)

    /**
     * One parsed dex per APK entry, keyed on the file's mtime so an edited APK is re-read.
     *
     * Parsing a 12 MB dex takes real time and every smali file in the tree needs the same
     * parse; without this, opening one class re-reads the whole dex.
     */
    private val dexCache = object : LinkedHashMap<String, DexBackedDexFile>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DexBackedDexFile>?) = size > 3
    }

    override suspend fun roots(): List<VNode> = emptyList()

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val loc = Location.of(path)
        if (loc.dexEntry == null) return@withContext listZip(path, loc)
        listDex(path, loc)
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val loc = Location.of(path)
        if (loc.inner.isEmpty()) {
            val f = File(loc.archive)
            return@withContext if (f.isFile) VNode(path, true, -1L, f.lastModified(), writable = false) else null
        }
        if (loc.dexEntry != null) return@withContext statDex(path, loc)
        openZip(loc.archive, path).use { zip ->
            val e = zip.getEntry(loc.inner) ?: zip.getEntry(loc.inner + "/")
            if (e != null) {
                // A .dex entry is reported as a DIRECTORY: that is what makes it walkable.
                val isDex = e.name.endsWith(".dex")
                return@use VNode(path, e.isDirectory || isDex, if (e.isDirectory || isDex) -1L else e.size, e.time, writable = false)
            }
            val prefix = loc.inner + "/"
            if (zip.entries().asSequence().any { it.name.startsWith(prefix) }) {
                VNode(path, isDir = true, size = -1L, mtime = 0L, writable = false)
            } else null
        }
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val loc = Location.of(path)
        if (loc.dexEntry != null && loc.classPath.endsWith(SMALI)) {
            val text = disassemble(loc, path)
            return@withContext ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        val zip = openZip(loc.archive, path)
        val entry = zip.getEntry(loc.inner) ?: run { zip.close(); throw VfsException.NotFound(path) }
        object : InputStream() {
            private val src = zip.getInputStream(entry)
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); zip.close() }
        }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream =
        throw VfsException.Unsupported("an APK is rebuilt and re-signed, not written to in place")

    override suspend fun create(path: VPath, isDir: Boolean): VNode =
        throw VfsException.Unsupported("an APK is rebuilt and re-signed, not written to in place")

    override suspend fun delete(path: VPath, recursive: Boolean): Unit =
        throw VfsException.Unsupported("an APK is rebuilt and re-signed, not written to in place")

    override suspend fun rename(path: VPath, newName: String): VNode =
        throw VfsException.Unsupported("an APK is rebuilt and re-signed, not written to in place")

    // ────────────────────────── zip side ──────────────────────────

    private fun listZip(path: VPath, loc: Location): List<VNode> =
        openZip(loc.archive, path).use { zip ->
            val prefix = if (loc.inner.isEmpty()) "" else loc.inner.trimEnd('/') + "/"
            val direct = LinkedHashMap<String, VNode>()
            for (e in zip.entries()) {
                val name = e.name
                if (!name.startsWith(prefix) || name == prefix) continue
                val rest = name.substring(prefix.length).trimEnd('/')
                if (rest.isEmpty()) continue
                val cut = rest.indexOf('/')
                if (cut < 0) {
                    val isDex = rest.endsWith(".dex")
                    direct[rest] = VNode(
                        path.child(rest),
                        isDir = e.isDirectory || isDex,
                        size = if (e.isDirectory || isDex) -1L else e.size,
                        mtime = e.time,
                        writable = false,
                    )
                } else {
                    val dir = rest.substring(0, cut)
                    direct.getOrPut(dir) { VNode(path.child(dir), true, -1L, e.time, writable = false) }
                }
            }
            direct.values.toList()
        }

    // ────────────────────────── dex side ──────────────────────────

    /**
     * The children of a point inside a dex.
     *
     * Class names are Java binary names (`com/example/Foo`), so the tree is built by grouping
     * on the next path segment - the same shape a source tree has, which is what makes
     * navigating a 9,000-class dex bearable.
     */
    private fun listDex(path: VPath, loc: Location): List<VNode> {
        val dex = dexOf(loc, path)
        val prefix = if (loc.classPath.isEmpty()) "" else loc.classPath.trimEnd('/') + "/"
        val dirs = LinkedHashSet<String>()
        val files = ArrayList<VNode>()
        for (classDef in dex.classes) {
            val binary = binaryName(classDef.type) ?: continue
            if (!binary.startsWith(prefix)) continue
            val rest = binary.substring(prefix.length)
            val cut = rest.indexOf('/')
            if (cut < 0) {
                files += VNode(path.child(rest + SMALI), false, -1L, 0L, writable = false)
            } else {
                dirs += rest.substring(0, cut)
            }
        }
        return dirs.sorted().map { VNode(path.child(it), true, -1L, 0L, writable = false) } +
            files.sortedBy { it.name }
    }

    private fun statDex(path: VPath, loc: Location): VNode? {
        if (loc.classPath.isEmpty()) return VNode(path, true, -1L, 0L, writable = false)
        val dex = dexOf(loc, path)
        if (loc.classPath.endsWith(SMALI)) {
            val wanted = loc.classPath.removeSuffix(SMALI)
            val found = dex.classes.any { binaryName(it.type) == wanted }
            return if (found) VNode(path, false, -1L, 0L, writable = false) else null
        }
        val prefix = loc.classPath.trimEnd('/') + "/"
        val any = dex.classes.any { binaryName(it.type)?.startsWith(prefix) == true }
        return if (any) VNode(path, true, -1L, 0L, writable = false) else null
    }

    private fun disassemble(loc: Location, path: VPath): String {
        val dex = dexOf(loc, path)
        val wanted = loc.classPath.removeSuffix(SMALI)
        val classDef: DexBackedClassDef = dex.classes.firstOrNull { binaryName(it.type) == wanted }
            ?: throw VfsException.NotFound(path)
        val options = BaksmaliOptions().apply {
            apiLevel = API_LEVEL
            parameterRegisters = true
            localsDirective = true
            sequentialLabels = true
            debugInfo = true
            implicitReferences = false
        }
        val out = StringWriter()
        BaksmaliFormatter().getWriter(out).use { writer ->
            ClassDefinition(options, classDef).writeTo(writer)
        }
        return out.toString()
    }

    @Synchronized
    private fun dexOf(loc: Location, path: VPath): DexBackedDexFile {
        val file = File(loc.archive)
        val key = "${loc.archive}!${loc.dexEntry}@${file.lastModified()}"
        dexCache[key]?.let { return it }
        val bytes = openZip(loc.archive, path).use { zip ->
            val entry = zip.getEntry(loc.dexEntry) ?: throw VfsException.NotFound(path)
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val dex = DexBackedDexFile(Opcodes.forApi(API_LEVEL), bytes)
        dexCache[key] = dex
        return dex
    }

    private fun openZip(archive: String, path: VPath): ZipFile =
        try { ZipFile(File(archive)) } catch (e: Exception) { throw VfsException.Io(path, e) }

    /** `Lcom/example/Foo;` -> `com/example/Foo`. Anything else is not a class we can show. */
    private fun binaryName(type: String): String? {
        if (!type.startsWith("L") || !type.endsWith(";")) return null
        return type.substring(1, type.length - 1)
    }

    /**
     * Where a path points: the APK, the entry inside it, and - when that entry is a dex - how
     * deep into the class tree.
     */
    private data class Location(val archive: String, val inner: String) {
        /** The `.dex` entry this path goes through, or null when it stays in the zip. */
        val dexEntry: String?
            get() {
                val idx = inner.indexOf(".dex")
                if (idx < 0) return null
                val end = idx + 4
                if (end < inner.length && inner[end] != '/') return null
                return inner.substring(0, end)
            }

        /** The part after the dex entry: a package path, or a class ending in `.smali`. */
        val classPath: String
            get() {
                val d = dexEntry ?: return ""
                return inner.removePrefix(d).trim('/')
            }

        companion object {
            fun of(path: VPath): Location {
                val i = path.path.indexOf(SEP)
                val archive = if (i < 0) path.path else path.path.substring(0, i)
                val inner = if (i < 0) "" else path.path.substring(i + 1).trim('/')
                return Location(osPath(archive), inner)
            }

            private fun osPath(p: String) =
                if (p.length > 2 && p[0] == '/' && p[2] == ':') p.substring(1) else p
        }
    }

    companion object {
        const val SCHEME = "apk"
        private const val SEP = "!"
        private const val SMALI = ".smali"

        /**
         * Disassemble at a high API level so modern opcodes are recognised.
         *
         * Too low and a dex built for a newer platform fails to parse; there is no downside
         * to reading with a newer opcode table.
         */
        const val API_LEVEL = 35

        fun mount(apkPath: String): VPath {
            val abs = if (apkPath.startsWith("/")) apkPath else "/$apkPath"
            return VPath(SCHEME, "$abs$SEP/")
        }

        /** The APK a path lives in, for the inspector and the rebuilder. */
        fun archiveOf(path: VPath): String = Location.of(path).archive

        fun innerOf(path: VPath): String = Location.of(path).inner
    }
}
