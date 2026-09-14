package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveMembers
import dev.niccc2007.filet.vfs.provider.Archives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every member name in an archive, for `inzip:`.
 *
 * > Zip central directory only - every member name without inflating a byte. Capped at 5,000
 * > members + honest `truncated` flag. (FEATURES.md F60)
 *
 * Round 7 widened it past zip. The reading is [ArchiveMembers], beside the provider that does
 * it, so the index and the browser agree about what is in an archive rather than each having
 * their own loop - which is why `inzip:` used to find things in a zip and nothing at all in a
 * `.tar.gz`.
 *
 * **The cost is not the same for every format, and that is the honest part.** A zip and a 7z
 * carry an index, so this is a seek and a small read whatever the archive weighs. A tar has
 * none: the crawler has to read it through its compressor to know what is inside. That is
 * affordable because the crawler only looks when the file is new or has changed, and because
 * the cap below bounds the worst case.
 */
class ArchiveFacts(private val vfs: Vfs) : FactExtractor {

    /** Every format the table says can be listed. RAR is not one, and says why elsewhere. */
    override val extensions: Set<String> =
        Archives.ALL.filter { it.canList }.flatMapTo(HashSet()) { it.extensions }

    override suspend fun facts(node: VNode): Map<String, List<String>> = withContext(Dispatchers.IO) {
        // A real path or nothing. An archive nested inside another has none, and spooling a
        // gigabyte into the cache during a background crawl is not a trade worth making.
        val os = vfs.osPath(node.path) ?: return@withContext emptyMap()
        val listing = ArchiveMembers.of(os, MAX_ENTRIES) ?: return@withContext emptyMap()
        buildMap {
            put("entry", listing.names)
            if (listing.truncated) put("tag", listOf("truncated"))
        }
    }

    private companion object { const val MAX_ENTRIES = 5_000 }
}
