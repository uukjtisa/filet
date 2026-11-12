package dev.niccc2007.filet.desktop.update

import org.json.JSONArray
import org.json.JSONObject

/** A release newer than this copy, with the installer that brings it. */
data class Offer(
    val version: String,
    val tag: String,
    /** The release's page, for what changed. */
    val page: String,
    val notes: String,
    val installer: Asset,
)

/** [sha256] is GitHub's own digest of the uploaded file, lower-case hex. */
data class Asset(val name: String, val url: String, val size: Long, val sha256: String)

/**
 * Which GitHub release, if any, this copy should update to. Pure, so every rule is tested.
 *
 * Only the desktop tool's own line counts: tags shaped `filet-desktop-mount-tool-v1.2.3`, the
 * shape `docs/RELEASES.md` sets and the phone's `ReleaseTags` keeps out of the app's updater. The
 * same repository carries the app's `v0.1.11` tags, and a bare version search would offer a phone
 * release here. Anchored at both ends for the same reason the phone's is: the prefix is the only
 * thing telling the two lines apart.
 *
 * Drafts, pre-releases and tags with a suffix (`-rc1`) are never offered: an updater that installs
 * by itself has to be sure what it installs. A release with no `.msi` is skipped rather than
 * offered and failed, because the MSI is what installs per user without an administrator. So is
 * one GitHub gives no SHA-256 for: without it a download cannot be told from a substitute, and an
 * updater that installs by itself does not install what it cannot check.
 */
object Releases {

    const val PREFIX = "filet-desktop-mount-tool-"

    private val TAG = Regex("""^${Regex.escape(PREFIX)}v?(\d+(?:\.\d+){1,2})$""")
    private val PLAIN = Regex("""^v?(\d+(?:\.\d+){1,2})$""")

    /** The version a desktop tag names, or null for any other tag. */
    fun versionOf(tag: String?): String? = TAG.matchEntire(tag.orEmpty().trim())?.groupValues?.get(1)

    /** Numeric, part by part, missing parts as zero: 0.1.10 is newer than 0.1.9. Null for a non-version. */
    fun compare(a: String, b: String): Int? {
        val x = parts(a) ?: return null
        val y = parts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The newest release in [json] (GitHub's `/releases` list) that is newer than [current]. */
    fun newest(json: String, current: String): Offer? {
        if (parts(current) == null) return null
        val list = runCatching { JSONArray(json) }.getOrNull() ?: return null
        return (0 until list.length())
            .mapNotNull { list.optJSONObject(it)?.let(::offerOf) }
            .filter { (compare(it.version, current) ?: 0) > 0 }
            .maxWithOrNull { a, b -> compare(a.version, b.version) ?: 0 }
    }

    private fun offerOf(r: JSONObject): Offer? {
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val tag = r.optString("tag_name")
        val version = versionOf(tag) ?: return null
        val assets = r.optJSONArray("assets") ?: return null
        val msi = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".msi", ignoreCase = true) && it.optString("browser_download_url").startsWith("https://") }
            ?: return null
        val digest = msi.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            ?.takeIf { d -> d.length == 64 && d.all { it in "0123456789abcdef" } } ?: return null
        return Offer(
            version = version,
            tag = tag,
            page = r.optString("html_url"),
            notes = r.optString("body"),
            installer = Asset(msi.optString("name"), msi.optString("browser_download_url"), msi.optLong("size", -1), digest),
        )
    }

    private fun parts(v: String): List<Int>? =
        PLAIN.matchEntire(v.trim())?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: return null }
}
