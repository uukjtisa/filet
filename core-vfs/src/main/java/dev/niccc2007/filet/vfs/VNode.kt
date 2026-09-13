package dev.niccc2007.filet.vfs

/**
 * One entry as a provider reports it. A snapshot, never a live handle — by the time the
 * UI renders it the filesystem may have moved on, which is why SEARCH.md §4.3 re-stats
 * before anything is trusted.
 */
data class VNode(
    val path: VPath,
    val isDir: Boolean,
    /** Bytes. -1 when the provider cannot say cheaply. Directories report -1. */
    val size: Long,
    /** Epoch millis. 0 when unknown. */
    val mtime: Long,
    val readable: Boolean = true,
    val writable: Boolean = true,
    val hidden: Boolean = false,
    /**
     * The volume's own identifier for this file, or 0 when the backend has none.
     *
     * On a real filesystem this is the inode, and a rename inside that filesystem keeps it -
     * which is the only thing that tells a move apart from "deleted here, created there".
     * The index needs that distinction: without it, moving a file destroys its stable id and
     * takes every pinned shortcut and provenance record attached to it (PLAN.md L5).
     *
     * Archives and network shares have no such number and report 0, so anything reading this
     * must have a fallback rather than assuming it is there.
     */
    val inode: Long = 0,
) {
    val name: String get() = path.name

    /** Lowercased extension without the dot, or "" when there is none. */
    val extension: String
        get() {
            if (isDir) return ""
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }
}

/** What a provider can actually do. The UI asks rather than assuming. */
enum class Capability { READ, WRITE, RENAME, DELETE, CREATE_DIR, RANDOM_ACCESS }

/** Thrown by providers so callers handle one exception family regardless of backend. */
sealed class VfsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(path: VPath) : VfsException("not found: $path")
    class AlreadyExists(path: VPath) : VfsException("already exists: $path")
    class AccessDenied(path: VPath, cause: Throwable? = null) : VfsException("access denied: $path", cause)
    class NotADirectory(path: VPath) : VfsException("not a directory: $path")
    class IsADirectory(path: VPath) : VfsException("is a directory: $path")
    class Unsupported(what: String) : VfsException("unsupported: $what")
    class Io(path: VPath, cause: Throwable) : VfsException("io error: $path", cause)
}
