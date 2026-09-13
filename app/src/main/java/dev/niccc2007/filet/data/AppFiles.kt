package dev.niccc2007.filet.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.niccc2007.filet.vfs.VPath
import java.io.File

/**
 * The one place in the app layer that holds a `java.io.File`.
 *
 * PLAN.md R3 says everything above L0 talks only to the VFS, and that rule has exactly two
 * kinds of genuine exception:
 *
 * 1. **App-private working storage** - a script file, an APK working copy, a cached decode.
 *    These are not user data and never came from a user path; they belong to the process.
 * 2. **Android APIs that only accept a `File`** - `FileProvider`, `apksig`, `smali`. Nothing
 *    can be done about those, and pretending otherwise would mean a wrapper that lies.
 *
 * Concentrating both here is what keeps the rule enforceable: `tools/check-r3.mjs` allows a
 * short, reasoned list of files, and this is the app layer's whole entry on it. Everything
 * else goes through [dev.niccc2007.filet.vfs.Vfs].
 */
class AppFiles(private val context: Context) {

    /** Where user scripts live. App-private, so uninstalling takes them with it. */
    val scriptsDir: File get() = File(context.filesDir, "scripts").apply { mkdirs() }

    /** A decompiled APK's working copy, named after the APK. */
    fun apkWorkDir(apkName: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "apk/" + apkName.substringBeforeLast('.'))

    /** A cached copy of a file that a seeking API refuses to read as a stream. */
    fun playCache(name: String): File = File(context.cacheDir, "play").apply { mkdirs() }.let { File(it, name) }

    /** Where a file shared INTO Filet lands before the user files it. */
    fun inboxCache(name: String): File = File(context.cacheDir, "inbox").apply { mkdirs() }.let { File(it, name) }

    fun scriptFile(id: String): File = File(scriptsDir, "$id.lua")

    /** The VPath for an app-private file, so the rest of the app can address it normally. */
    fun vpath(file: File): VPath = VPath.of("local", file.absolutePath.replace(File.separatorChar, '/'))

    fun exists(file: File): Boolean = file.isFile

    fun size(file: File): Long = if (file.isFile) file.length() else -1L

    /**
     * A grantable URI for a file, for handing out to another app.
     *
     * A raw `file://` URI throws `FileUriExposedException` past API 24 and cannot carry a read
     * grant, so everything that leaves the process goes through here.
     */
    fun shareUri(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrNull()

    /** A `file://` URI for in-process consumers such as `VideoView`, which cannot take a stream. */
    fun localUri(file: File): Uri = Uri.fromFile(file)

    fun fileAt(osPath: String): File = File(osPath)

    fun listLua(): List<File> =
        (scriptsDir.listFiles { f -> f.extension == "lua" } ?: emptyArray()).toList()

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun read(file: File): String = file.readText()

    fun delete(file: File): Boolean = file.delete()

    fun lastModified(file: File): Long = file.lastModified()

    fun nameWithoutExtension(file: File): String = file.nameWithoutExtension
}
