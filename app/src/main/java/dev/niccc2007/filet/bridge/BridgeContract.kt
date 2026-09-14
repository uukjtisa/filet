package dev.niccc2007.filet.bridge

import android.net.Uri

/**
 * The contract between Filet and any app that wants to tell it where a file came from.
 *
 * > Trawl acquires. Filet operates. The contract between them is a file's biography.
 * > (PLAN.md §5)
 *
 * This is deliberately **generic**. Trawl is simply the first writer: publish the contract and
 * a camera app, a torrent client or a share-sheet target can write provenance too, and every
 * one of them gets `from:` search for free.
 *
 * Access is guarded by a `protectionLevel="signature"` permission, so no third app can spoof
 * provenance or inject a job. No server, no account, works offline.
 *
 * Two things about that permission are easy to get wrong, and both were:
 *
 * 1. **The name carries the application id.** A hardcoded name is declared identically by the
 *    debug and the release build, and Android refuses to install an app that redefines
 *    another app's permission. The two builds could not coexist on one phone at all.
 * 2. **Signature means the same signing key, not the same developer.** Filet and Trawl are
 *    signed with different keys today, so a `signature` permission would not be granted
 *    between them. Whoever implements the writer side has to settle that first: either the
 *    two apps share a key, or this moves to `signature|knownSigner` with the other app's
 *    certificate digest (API 31+). Writing the record here so it is decided rather than
 *    discovered.
 */
object BridgeContract {

    const val AUTHORITY_RELEASE = "dev.niccc2007.filet.bridge"
    const val AUTHORITY_DEBUG = "dev.niccc2007.filet.debug.bridge"

    const val PERMISSION_RELEASE = "dev.niccc2007.filet.permission.BRIDGE"
    const val PERMISSION_DEBUG = "dev.niccc2007.filet.debug.permission.BRIDGE"

    /**
     * The permission that guards [authority].
     *
     * A writer that found a provider has to ask for the permission belonging to *that* build,
     * not to whichever one it was compiled against. One constant for both was the bug.
     */
    fun permissionFor(authority: String): String =
        if (authority == AUTHORITY_DEBUG) PERMISSION_DEBUG else PERMISSION_RELEASE

    /**
     * Both package names are enumerated, always.
     *
     * `applicationIdSuffix ".debug"` means a development build is a *different package* and
     * cannot see the release app's provider. Checking only one name is why a bridge works in
     * production and is invisible during development (PLAN.md §5.4).
     */
    val AUTHORITIES = listOf(AUTHORITY_RELEASE, AUTHORITY_DEBUG)

    fun provenanceUri(authority: String): Uri = Uri.parse("content://$authority/provenance")
    fun jobsUri(authority: String): Uri = Uri.parse("content://$authority/jobs")

    /** Columns of the `provenance` table. A writer supplies PATH plus whatever it knows. */
    object Provenance {
        const val PATH = "path"
        const val SOURCE_APP = "source_app"
        const val ORIGIN = "origin"
        const val PAGE_TITLE = "page_title"
        const val UPLOADER = "uploader"
        const val FORMAT = "format"
        const val AT = "at"
        const val EXTRA = "extra"

        val ALL = arrayOf(PATH, SOURCE_APP, ORIGIN, PAGE_TITLE, UPLOADER, FORMAT, AT, EXTRA)
    }

    /** Columns of the shared job ledger. */
    object Jobs {
        const val ID = "_id"
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
        const val STATE = "state"
        const val PROGRESS = "progress"
        const val DETAIL = "detail"
        const val SOURCE = "source"
        const val STARTED_AT = "started_at"

        val ALL = arrayOf(ID, TITLE, SUBTITLE, STATE, PROGRESS, DETAIL, SOURCE, STARTED_AT)
    }

    /**
     * "Get this again, at a different quality."
     *
     * Filet sends this to Trawl; Trawl decides what to do with it. Filet never downloads
     * anything itself - that is the whole point of there being two apps.
     */
    object Redownload {
        const val ACTION = "dev.niccc2007.filet.action.REDOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_SUGGESTED_NAME = "name"
    }
}
