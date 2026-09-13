package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode

/**
 * Pulls the facts that live *inside* a file out so the index can search them.
 *
 * > `pkg:` `label:` `perm:` via ARSCLib; `class:` as deduped package prefixes via dexlib2
 * > (~2 KB per APK, not 2 MB). **Nothing else on Android does this.** (FEATURES.md F59)
 *
 * An interface rather than code in the crawler because the parsers live above L0: ARSCLib and
 * dexlib2 are `:app` dependencies, and `:core-index` must not reach up to get them. The
 * crawler asks whoever is registered; if nobody is, the fields simply find nothing - and
 * `SqliteIndex` reports that so the query surface can stay honest about it (R1).
 *
 * Implementations must be cheap and must never inflate a file: an archive contributes its
 * central directory, an APK its manifest strings and class-name prefixes.
 */
interface FactExtractor {

    /**
     * File extensions, lowercase and without the dot, this extractor wants to look at.
     *
     * Checked before [facts] is called, so the crawler never opens a file for nothing.
     */
    val extensions: Set<String>

    /**
     * @return kind -> values, where kind is one of the `inner_fact` kinds the query grammar
     *   knows: `pkg`, `label`, `perm`, `class`, `entry`, `member`, `tag`. An empty map is a
     *   perfectly good answer for a file that turned out to have nothing to say.
     *
     * Failures are the extractor's to swallow: a corrupt archive is a fact about that file,
     * not a reason to abandon a crawl.
     */
    suspend fun facts(node: VNode): Map<String, List<String>>
}
