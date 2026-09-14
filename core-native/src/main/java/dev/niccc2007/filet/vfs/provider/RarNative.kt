package dev.niccc2007.filet.vfs.provider

import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * The Kotlin side of the RAR reader.
 *
 * Three calls, no state, no handles crossing the boundary. Everything that could be decided in
 * Kotlin is decided in Kotlin, because every line of C in a file manager with access to the
 * whole device is a line that can corrupt its heap.
 *
 * ## Why there is native code here at all
 *
 * Every RAR decoder published for the JVM - `junrar`, `sevenzipjbinding` - descends from
 * RARLAB's UnRAR source. That licence forbids using the sources to build a RAR-compatible
 * archiver, which is a field-of-use restriction, and GPL-3 section 7 does not allow one to be
 * added to a GPL-3 work. Linking one into Filet would be a licence violation, not an oversight,
 * so RAR was refused outright until this module.
 *
 * libarchive's two RAR readers are not derived from that source: the RAR4 reader is Tim
 * Kientzle's and Andres Mejia's, the RAR5 reader is Grzegorz Antoniak's own implementation, and
 * both are BSD-2-Clause. GPL-3 can take BSD. That is the entire reason the answer changed.
 *
 * ## What it still will not do
 *
 * **Create a RAR.** Reading a format is not the same as writing one, and writing RAR is
 * precisely what RARLAB's licence protects. Filet creates zip, tar, tar.gz, tar.bz2, tar.xz and
 * 7z; it reads RAR and says so.
 */
object RarNative {

    /** Whether the library loaded. False on a build without the native module, never a crash. */
    val available: Boolean = runCatching { System.loadLibrary("filetrar") }.isSuccess

    /** One entry as libarchive described it. */
    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        /** Seconds since the epoch, as RAR stores it. */
        val modifiedAt: Long,
    )

    /**
     * Everything in [osPath], or an empty list if it is not a readable RAR.
     *
     * @param passphrase for an encrypted archive. Null is the normal case.
     */
    fun list(osPath: String, passphrase: String? = null): List<Entry> {
        if (!available) return emptyList()
        val lines = runCatching { nativeList(osPath, passphrase) }.getOrNull() ?: return emptyList()
        return lines.mapNotNull(::parse)
    }

    /**
     * Write one entry into [into].
     *
     * @return bytes written, or -1 if the entry was not found or could not be read.
     *
     * The destination is a stream the CALLER opened, never a path handed to C. That is R3 as it
     * applies to native code: a function that takes a destination path is one that can be
     * talked into writing outside the folder the user picked, which is the classic archive
     * traversal bug and the classic way an archive tool becomes an exploit.
     */
    fun extract(osPath: String, entry: String, into: FileOutputStream, passphrase: String? = null): Long {
        if (!available) return -1
        return runCatching { nativeExtract(osPath, entry, fdOf(into.fd), passphrase) }.getOrDefault(-1)
    }

    /** Whether this file really is a RAR, by reading its first header rather than its name. */
    fun canRead(osPath: String): Boolean =
        available && runCatching { nativeCanRead(osPath) }.getOrDefault(false)

    // ── wire ──

    /**
     * `isDir \t size \t mtime \t name`, with the name last.
     *
     * The name is last because it is the only field that can contain a tab, so the split is
     * bounded at three and a filename with a tab in it cannot shift the other columns. An
     * archive is untrusted input and its filenames are the most attacker-controlled part of it.
     */
    internal fun parse(line: String): Entry? {
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return null
        val name = parts[3]
        if (name.isEmpty()) return null
        return Entry(
            path = name,
            isDirectory = parts[0] == "1",
            size = parts[1].toLongOrNull() ?: 0L,
            modifiedAt = parts[2].toLongOrNull() ?: 0L,
        )
    }

    private fun fdOf(fd: FileDescriptor): Int {
        // FileDescriptor.getInt$ is hidden but stable, and the alternative is another JNI call
        // to convert one. Reflective, so a future platform change degrades to "cannot extract"
        // rather than to a crash.
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    @JvmStatic private external fun nativeList(path: String, passphrase: String?): Array<String>?
    @JvmStatic private external fun nativeExtract(path: String, entry: String, fd: Int, passphrase: String?): Long
    @JvmStatic private external fun nativeCanRead(path: String): Boolean
}
