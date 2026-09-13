package dev.niccc2007.filet.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.niccc2007.filet.index.Provenance
import dev.niccc2007.filet.jobs.Job
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.jobs.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filet's client side of the bridge: reading the *other* app's ledger, and asking it for work.
 *
 * Discovery enumerates both the release and `.debug` package names and verifies the signing
 * certificate before talking to anything. Checking only the release name is why a bridge
 * appears to work in production and is invisible during development.
 */
class TrawlBridge(private val context: Context, private val ledger: JobLedger) {

    /** @return the authority to talk to, or null when no trusted peer is installed. */
    fun peerAuthority(): String? {
        val pm = context.packageManager
        for (pkg in PEER_PACKAGES) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            if (!trusted(pkg)) continue
            return "$pkg.bridge"
        }
        return null
    }

    fun isPeerInstalled(): Boolean = peerAuthority() != null

    private fun trusted(pkg: String): Boolean =
        runCatching {
            context.packageManager.checkSignatures(pkg, context.packageName) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)

    /**
     * Pull the peer's running jobs into Filet's ledger.
     *
     * Read, never subscribe: a `ContentObserver` on another app's provider keeps that app's
     * process warm, which is a battery cost Filet has no right to impose.
     */
    suspend fun pullJobs(): Int = withContext(Dispatchers.IO) {
        val authority = peerAuthority() ?: return@withContext 0
        var count = 0
        runCatching {
            context.contentResolver.query(
                BridgeContract.jobsUri(authority), BridgeContract.Jobs.ALL, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    ledger.publishExternal(
                        Job(
                            id = -c.getLong(0),
                            title = c.getString(1) ?: "Job",
                            subtitle = c.getString(2) ?: "",
                            state = runCatching { JobState.valueOf(c.getString(3)) }
                                .getOrDefault(JobState.RUNNING),
                            progress = if (c.isNull(4)) null else c.getFloat(4),
                            detail = c.getString(5) ?: "",
                            source = c.getString(6) ?: "Trawl",
                            startedAt = c.getLong(7),
                        )
                    )
                    count++
                }
            }
        }
        count
    }

    /** Ask the peer for this file's biography, when the peer is the one who knows. */
    suspend fun provenanceOf(path: String): Provenance? = withContext(Dispatchers.IO) {
        val authority = peerAuthority() ?: return@withContext null
        val uri = BridgeContract.provenanceUri(authority)
            .buildUpon().appendPath(android.net.Uri.encode(path)).build()
        runCatching {
            context.contentResolver.query(uri, BridgeContract.Provenance.ALL, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                Provenance(
                    sourceApp = c.getString(1) ?: "",
                    origin = c.getString(2),
                    pageTitle = c.getString(3),
                    uploader = c.getString(4),
                    format = c.getString(5),
                    at = c.getLong(6),
                    extra = c.getString(7),
                )
            }
        }.getOrNull()
    }

    /**
     * "Get this again, at a different quality."
     *
     * Filet never downloads anything - that is the entire reason there are two apps. This
     * hands the decision back to the app whose job it is.
     */
    fun requestRedownload(url: String, format: String?, suggestedName: String?): Intent? {
        val pkg = PEER_PACKAGES.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess && trusted(it)
        } ?: return null
        return Intent(BridgeContract.Redownload.ACTION).apply {
            setPackage(pkg)
            putExtra(BridgeContract.Redownload.EXTRA_URL, url)
            format?.let { putExtra(BridgeContract.Redownload.EXTRA_FORMAT, it) }
            suggestedName?.let { putExtra(BridgeContract.Redownload.EXTRA_SUGGESTED_NAME, it) }
        }
    }

    private companion object {
        /** Release first, then debug: a development build must not be bridge-blind. */
        val PEER_PACKAGES = listOf("dev.niccc2007.trawl", "dev.niccc2007.trawl.debug")
    }
}
