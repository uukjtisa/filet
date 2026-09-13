package dev.niccc2007.filet.script

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.VfsException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * What a script is allowed to touch.
 *
 * > A script runner reachable by intent is remote code execution (PLAN.md L4).
 *
 * Two rules make that survivable, and both are enforced here rather than by convention:
 *
 * 1. A script declares its permissions in a header, and they are shown to the user in plain
 *    words *before* it runs.
 * 2. The permissions are enforced at the **VFS boundary** ([GuardedVfs]), so a script cannot
 *    escape them by being clever - there is no other way for it to reach storage.
 */
data class ScriptPermissions(
    /** Subtrees the script may read. Empty means nothing. */
    val read: List<VPath> = emptyList(),
    /** Subtrees the script may write, create in, or delete from. */
    val write: List<VPath> = emptyList(),
    val network: Boolean = false,
    /** Wall-clock ceiling. A runaway loop must end by itself, not by the user force-stopping. */
    val timeoutMs: Long = 15_000,
) {
    val isEmpty: Boolean get() = read.isEmpty() && write.isEmpty() && !network

    /** Plain words for the confirmation dialog. Never jargon: this is a consent screen. */
    fun describe(): List<String> = buildList {
        when {
            read.isEmpty() -> add("Cannot read any files")
            else -> read.forEach { add("Read ${it.path}") }
        }
        if (write.isEmpty()) add("Cannot change any files")
        else write.forEach { add("Create, change and delete inside ${it.path}") }
        if (network) add("Reach the network")
        add("Stops itself after ${timeoutMs / 1000} seconds")
    }

    companion object {
        /**
         * Parse the `-- @permission` header a script declares about itself.
         *
         * ```lua
         * -- @name    Tag and file
         * -- @read    local:///storage/emulated/0/Download
         * -- @write   local:///storage/emulated/0/Music
         * -- @network false
         * -- @timeout 30
         * ```
         *
         * A declaration is a *request*, never a grant: the user still confirms it.
         */
        fun parse(source: String): Pair<String, ScriptPermissions> {
            var name = ""
            val read = ArrayList<VPath>()
            val write = ArrayList<VPath>()
            var network = false
            var timeout = 15_000L
            for (raw in source.lineSequence().take(40)) {
                val line = raw.trim()
                if (!line.startsWith("--")) {
                    if (line.isNotEmpty()) break   // header ends at the first real statement
                    continue
                }
                val body = line.removePrefix("--").trim()
                if (!body.startsWith("@")) continue
                val key = body.drop(1).substringBefore(' ').lowercase()
                val value = body.drop(1).substringAfter(' ', "").trim()
                when (key) {
                    "name" -> name = value
                    "read" -> runCatching { VPath.parse(value) }.getOrNull()?.let { read += it }
                    "write" -> runCatching { VPath.parse(value) }.getOrNull()?.let { write += it }
                    "network" -> network = value.equals("true", true)
                    "timeout" -> timeout = (value.toLongOrNull() ?: 15L).coerceIn(1, 300) * 1000
                }
            }
            return name to ScriptPermissions(read, write, network, timeout)
        }

        /** Identity of a script *as written*. Approving one version never approves the next. */
        fun fingerprint(source: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(source.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}

/** Raised when a script reaches outside what the user granted. Not catchable from Lua. */
class ScriptDenied(val path: VPath, val what: String) :
    SecurityException("script denied: $what ${path.path}")

/**
 * The VFS a script actually gets.
 *
 * Same interface, every call checked against [permissions] first. Scripts bind to this rather
 * than to `java.io.File`, which is what makes them work over SMB, inside archives, and
 * through root for free - and what makes the permission check unavoidable.
 */
class GuardedVfs(private val real: Vfs, val permissions: ScriptPermissions) {

    private fun checkRead(path: VPath) {
        if (permissions.read.none { it.contains(path) } && permissions.write.none { it.contains(path) }) {
            throw ScriptDenied(path, "read")
        }
    }

    private fun checkWrite(path: VPath) {
        if (permissions.write.none { it.contains(path) }) throw ScriptDenied(path, "write")
    }

    suspend fun list(path: VPath): List<VNode> { checkRead(path); return real.list(path) }
    suspend fun stat(path: VPath): VNode? { checkRead(path); return real.stat(path) }
    suspend fun openRead(path: VPath): InputStream { checkRead(path); return real.openRead(path) }
    suspend fun openWrite(path: VPath, append: Boolean = false): OutputStream {
        checkWrite(path); return real.openWrite(path, append)
    }
    suspend fun create(path: VPath, isDir: Boolean): VNode { checkWrite(path); return real.create(path, isDir) }
    suspend fun delete(path: VPath) { checkWrite(path); real.delete(path, recursive = true) }
    suspend fun rename(path: VPath, newName: String): VNode { checkWrite(path); return real.rename(path, newName) }

    suspend fun copy(from: VPath, into: VPath): VNode {
        checkRead(from); checkWrite(into)
        return real.copy(from, into)
    }

    suspend fun move(from: VPath, into: VPath): VNode {
        // A move is a write on BOTH sides: it removes the source. Checking only the
        // destination would let a read-only grant delete the file it was allowed to read.
        checkWrite(from); checkWrite(into)
        return real.move(from, into)
    }

    fun requireNetwork() {
        if (!permissions.network) throw SecurityException("script denied: network")
    }
}
