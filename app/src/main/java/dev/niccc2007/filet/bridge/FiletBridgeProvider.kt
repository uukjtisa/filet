package dev.niccc2007.filet.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.index.Provenance
import dev.niccc2007.filet.jobs.Job
import dev.niccc2007.filet.jobs.JobState
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking

/**
 * Filet's half of the bridge.
 *
 * Another app writes a row here when it finishes acquiring a file, and Filet indexes it as
 * that file's biography. A file on Android otherwise has no memory of where it came from -
 * there is no mark-of-the-web and no `kMDItemWhereFroms` - and nothing else fills that gap.
 *
 * Two layers of defence, because a `ContentProvider` with `exported="true"` is an attack
 * surface no matter what it does:
 *
 * 1. The manifest requires a **signature** permission, so only apps signed with the same key
 *    can bind at all.
 * 2. [assertTrusted] re-checks the *calling package's signature* against this app's own on
 *    every call. Belt and braces: a permission is only as good as the platform's enforcement,
 *    and the cost of being wrong here is forged provenance.
 */
class FiletBridgeProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val authority = context?.packageName + ".bridge"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "provenance", CODE_PROVENANCE)
            addURI(authority, "provenance/*", CODE_PROVENANCE_ONE)
            addURI(authority, "jobs", CODE_JOBS)
        }
        return true
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_PROVENANCE, CODE_JOBS -> "vnd.android.cursor.dir/vnd.filet.bridge"
        CODE_PROVENANCE_ONE -> "vnd.android.cursor.item/vnd.filet.bridge"
        else -> "vnd.android.cursor.dir/vnd.filet.bridge"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        assertTrusted()
        val ctx = context ?: return null
        val graph = FiletApp.graphOf(ctx)
        return when (matcher.match(uri)) {
            CODE_PROVENANCE_ONE -> {
                val path = uri.lastPathSegment?.let { Uri.decode(it) } ?: return null
                val vpath = runCatching { VPath.parse(path) }.getOrNull() ?: return null
                val record = runBlocking { graph.index.provenanceOf(vpath) }
                MatrixCursor(BridgeContract.Provenance.ALL).apply {
                    if (record != null) addRow(rowOf(path, record))
                }
            }
            CODE_JOBS -> MatrixCursor(BridgeContract.Jobs.ALL).apply {
                for (job in graph.ledger.jobs.value) addRow(rowOf(job))
            }
            else -> null
        }
    }

    /**
     * A writer telling us where a file came from.
     *
     * The record is attached to the file by path, and the index will index the file first if
     * it has never seen it - which is the normal case, since this arrives moments after a
     * download finished.
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        assertTrusted()
        val ctx = context ?: return null
        val v = values ?: return null
        val graph = FiletApp.graphOf(ctx)

        when (matcher.match(uri)) {
            CODE_PROVENANCE -> {
                val raw = v.getAsString(BridgeContract.Provenance.PATH) ?: return null
                val vpath = normalisePath(raw) ?: return null
                val record = Provenance(
                    sourceApp = v.getAsString(BridgeContract.Provenance.SOURCE_APP) ?: callingPackage.orEmpty(),
                    origin = v.getAsString(BridgeContract.Provenance.ORIGIN),
                    pageTitle = v.getAsString(BridgeContract.Provenance.PAGE_TITLE),
                    uploader = v.getAsString(BridgeContract.Provenance.UPLOADER),
                    format = v.getAsString(BridgeContract.Provenance.FORMAT),
                    at = v.getAsLong(BridgeContract.Provenance.AT) ?: System.currentTimeMillis(),
                    extra = v.getAsString(BridgeContract.Provenance.EXTRA),
                )
                runBlocking { graph.index.putProvenance(vpath, record) }
                graph.home.refresh()
                return uri.buildUpon().appendPath(Uri.encode(vpath.toString())).build()
            }
            CODE_JOBS -> {
                // The other app's work, shown in Filet's Activity sheet. One ledger answering
                // "what is this phone doing" beats two notification stacks with no history.
                graph.ledger.publishExternal(
                    Job(
                        id = -(v.getAsLong(BridgeContract.Jobs.ID) ?: System.currentTimeMillis()),
                        title = v.getAsString(BridgeContract.Jobs.TITLE) ?: "Job",
                        subtitle = v.getAsString(BridgeContract.Jobs.SUBTITLE) ?: "",
                        state = runCatching {
                            JobState.valueOf(v.getAsString(BridgeContract.Jobs.STATE) ?: "RUNNING")
                        }.getOrDefault(JobState.RUNNING),
                        progress = v.getAsFloat(BridgeContract.Jobs.PROGRESS),
                        detail = v.getAsString(BridgeContract.Jobs.DETAIL) ?: "",
                        source = v.getAsString(BridgeContract.Jobs.SOURCE) ?: callingPackage ?: "another app",
                        startedAt = v.getAsLong(BridgeContract.Jobs.STARTED_AT) ?: System.currentTimeMillis(),
                    )
                )
                return uri
            }
        }
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        // An update is an insert: both tables are keyed, and a writer correcting a record it
        // just wrote should not have to know which verb we filed it under.
        return if (insert(uri, values) != null) 1 else 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * Refuse anyone not signed with our key.
     *
     * `checkSignatures` compares the calling package's certificates against ours. This runs
     * even though the manifest already demands a signature permission, because provenance is
     * a trust claim and a forged one is worse than a missing one.
     */
    private fun assertTrusted() {
        val ctx: Context = context ?: throw SecurityException("no context")
        val caller = callingPackage ?: throw SecurityException("no calling package")
        if (caller == ctx.packageName) return
        val match = ctx.packageManager.checkSignatures(caller, ctx.packageName)
        if (match != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("bridge is limited to apps signed with the same key")
        }
    }

    /** Accept either a VPath or a plain filesystem path, so a writer need not learn our scheme. */
    private fun normalisePath(raw: String): VPath? = when {
        raw.contains("://") -> runCatching { VPath.parse(raw) }.getOrNull()
        raw.startsWith("/") -> VPath.of("local", raw)
        else -> null
    }

    private fun rowOf(path: String, r: Provenance): Array<Any?> = arrayOf(
        path, r.sourceApp, r.origin, r.pageTitle, r.uploader, r.format, r.at, r.extra,
    )

    private fun rowOf(job: Job): Array<Any?> = arrayOf(
        job.id, job.title, job.subtitle, job.state.name, job.progress,
        job.detail, job.source, job.startedAt,
    )

    private companion object {
        const val CODE_PROVENANCE = 1
        const val CODE_PROVENANCE_ONE = 2
        const val CODE_JOBS = 3
    }
}
