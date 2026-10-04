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

    /**
     * The answer to "is there a trusted peer", worked out once per process.
     *
     * It was worked out per call, and the calls are not rare: the Source chip asks for a file's
     * provenance once per row of the Home feed, and each answer cost `getPackageInfo` plus
     * `checkSignatures` for every candidate package - two binder round trips to the package
     * manager, per row, for a fact that cannot change while the process lives. A package being
     * installed or removed DOES change it, and that is what [forget] is for; the app already
     * listens for those broadcasts.
     *
     * `Optional`-by-sentinel rather than a nullable field, because "not looked up yet" and
     * "looked up, and there is nobody" are different states and collapsing them means re-asking
     * forever in the common case of no peer installed - which is the case on most devices.
     */
    @Volatile
    private var cachedAuthority: String? = null

    @Volatile
    private var lookedUp = false

    /** @return the authority to talk to, or null when no trusted peer is installed. */
    fun peerAuthority(): String? {
        if (lookedUp) return cachedAuthority
        synchronized(this) {
            if (lookedUp) return cachedAuthority
            val pm = context.packageManager
            var found: String? = null
            for (pkg in PEER_PACKAGES) {
                runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                if (!trusted(pkg)) continue
                found = "$pkg.bridge"
                break
            }
            cachedAuthority = found
            lookedUp = true
            dev.niccc2007.filet.log.FiletLog.d("bridge", "peer authority: " + (found ?: "none installed"))
            return found
        }
    }

    /**
     * Forget the cached answer, because a package was installed or removed.
     *
     * The one thing that genuinely changes it. Without this the cache would be a correctness bug
     * rather than an optimisation: installing the companion app would leave Filet convinced for
     * the rest of the session that it was not there.
     */
    fun forgetPeer() {
        synchronized(this) {
            lookedUp = false
            cachedAuthority = null
        }
    }

    /**
     * Whether the peer's provider actually answered the last time it was asked.
     *
     * Separate from whether the peer is installed, because the two disagree in practice and the
     * disagreement is noisy: a companion app can be installed at a version that does not export
     * the provider at all, and every query then fails deep in the platform with *Failed to find
     * provider info* - twenty-five of them in one cold start, each a failed binder resolution. One
     * failure is enough to stop asking for the rest of the session.
     */
    @Volatile
    private var providerMissing = false

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
        if (providerMissing) return@withContext null
        val authority = peerAuthority() ?: return@withContext null
        val uri = BridgeContract.provenanceUri(authority)
            .buildUpon().appendPath(android.net.Uri.encode(path)).build()
        // A provider that is not there resolves to null rather than throwing, so the absence has
        // to be noticed here. Noticed once: the Home feed asks per row.
        val answer = runCatching {
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
            } ?: run {
                providerMissing = true
                dev.niccc2007.filet.log.FiletLog.i("bridge", "peer is installed but exports no provenance provider; not asking again")
                null
            }
        }
        answer.getOrNull()
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
