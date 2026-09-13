package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Every member name in an archive, without inflating a byte of it.
 *
 * > Zip central directory only - every member name without inflating a byte. Capped at 5,000
 * > members + honest `truncated` flag. (FEATURES.md F60)
 *
 * A zip's central directory is a table at the end of the file: reading it costs one seek and
 * one small read, whatever the archive weighs. That is what makes `inzip:` affordable on a
 * folder full of multi-gigabyte backups.
 *
 * The cap is real and it is recorded: an archive past [MAX_ENTRIES] members gets a `tag` of
 * `truncated`, so a search can say "and more" instead of quietly lying about completeness.
 */
class ArchiveFacts(private val vfs: Vfs) : FactExtractor {

    override val extensions: Set<String> = setOf("zip", "jar", "aar", "epub", "cbz", "apks", "xapk")

    override suspend fun facts(node: VNode): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val os = vfs.osPath(node.path) ?: return@withContext emptyMap()
        val names = ArrayList<String>(64)
        var truncated = false
        runCatching {
            ZipFile(File(os)).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (names.size >= MAX_ENTRIES) { truncated = true; break }
                    names += e.name
                }
            }
        }.getOrElse { return@withContext emptyMap() }

        if (names.isEmpty()) return@withContext emptyMap()
        buildMap {
            put("entry", names)
            if (truncated) put("tag", listOf("truncated"))
        }
    }

    private companion object { const val MAX_ENTRIES = 5_000 }
}
