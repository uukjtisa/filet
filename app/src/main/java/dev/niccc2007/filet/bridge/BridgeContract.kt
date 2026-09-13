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
 * Access is guarded by `dev.niccc2007.filet.permission.BRIDGE`, declared
 * `protectionLevel="signature"`. Both apps are signed with the same key, so no third app can
 * spoof provenance or inject a job. No server, no account, works offline.
 */
object BridgeContract {

    const val AUTHORITY_RELEASE = "dev.niccc2007.filet.bridge"
    const val AUTHORITY_DEBUG = "dev.niccc2007.filet.debug.bridge"
    const val PERMISSION = "dev.niccc2007.filet.permission.BRIDGE"

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
