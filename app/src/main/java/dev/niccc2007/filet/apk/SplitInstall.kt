package dev.niccc2007.filet.apk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.DisplayMetrics
import java.io.InputStream

/**
 * Installing a split app bundle - `.xapk`, `.apkm`, `.apks`.
 *
 * Bug identified: these three were listed in the zip family, so Filet opened them and showed
 * what was inside, and `FileKind` mapped only `apk` to the installable kind. The result read as
 * support and was not: a bundle handed to the system installer by intent is refused, because a
 * base plus its splits cannot be installed as a file. They go through a `PackageInstaller`
 * session with every chosen piece written into it and the whole thing committed at once.
 *
 * The pieces are chosen by [SplitPackage], which is where the decision that can be wrong lives
 * and where it is tested. This file is the plumbing around it.
 *
 * ## Streamed, never unpacked
 *
 * Each piece is copied from inside the archive straight into the session. A 900 MB game bundle
 * would otherwise be extracted to cache first and need twice its own size in free space, on the
 * device least likely to have it.
 */
object SplitInstall {

    /** What the caller needs to know, in words that say what to do next. */
    sealed interface Result {
        /** The system's confirmation screen is up. Nothing more to do here. */
        data object Handed : Result

        data class Failed(val why: String) : Result
    }

    /**
     * The device's own answers, for [SplitPackage.pick].
     *
     * Read from the running device rather than guessed, because every one of these being wrong
     * produces an app that installs and then misbehaves.
     */
    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    fun deviceDensity(dpi: Int): String = when {
        dpi <= DisplayMetrics.DENSITY_LOW -> "ldpi"
        dpi <= DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
        dpi <= DisplayMetrics.DENSITY_HIGH -> "hdpi"
        dpi <= DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
        dpi <= DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
        else -> "xxxhdpi"
    }

    /**
     * Write every chosen piece into one session and commit it.
     *
     * @param open called once per chosen entry to get a stream on it, and the size if known.
     *   A size of -1 is allowed: `openWrite` takes it and the session sizes itself as it goes,
     *   which matters because an archive member's size is not always known before it is read.
     * @return [Result.Handed] once the system has taken over, or a sentence saying why not.
     */
    suspend fun install(
        context: Context,
        label: String,
        entries: List<String>,
        open: suspend (String) -> Pair<InputStream, Long>?,
    ): Result {
        if (entries.isEmpty()) return Result.Failed("There are no APKs inside this bundle to install.")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppLabel(label)

        val sessionId = runCatching { installer.createSession(params) }
            .getOrElse { return Result.Failed("Android would not start an install session: ${it.message}") }

        val session = runCatching { installer.openSession(sessionId) }
            .getOrElse {
                runCatching { installer.abandonSession(sessionId) }
                return Result.Failed("Android would not open the install session: ${it.message}")
            }

        try {
            for ((i, entry) in entries.withIndex()) {
                val opened = open(entry)
                    ?: return abandon(installer, session, sessionId, "Could not read $entry out of the bundle.")
                val (input, size) = opened
                // The name only has to be unique within the session; the installer reads the
                // manifest inside each piece to work out what it actually is.
                val name = "$i-" + entry.substringAfterLast('/')
                runCatching {
                    input.use { source ->
                        session.openWrite(name, 0, size).use { out ->
                            source.copyTo(out, DEFAULT_BUFFER_SIZE)
                            session.fsync(out)
                        }
                    }
                }.getOrElse {
                    return abandon(installer, session, sessionId, "Writing $entry failed: ${it.message}")
                }
            }

            session.commit(statusIntent(context, sessionId).intentSender)
            session.close()
            return Result.Handed
        } catch (t: Throwable) {
            return abandon(installer, session, sessionId, t.message ?: "the install session failed")
        }
    }

    /**
     * A half-written session is not harmless.
     *
     * Abandoned sessions hold their staged bytes until the platform gets round to them, so a
     * few failed attempts on a large bundle can quietly occupy gigabytes.
     */
    private fun abandon(
        installer: PackageInstaller,
        session: PackageInstaller.Session,
        id: Int,
        why: String,
    ): Result {
        runCatching { session.close() }
        runCatching { installer.abandonSession(id) }
        return Result.Failed(why)
    }

    private fun statusIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(ACTION_STATUS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            // MUTABLE is required: the platform writes the status extras into this intent
            // before delivering it, and an immutable one arrives empty.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    const val ACTION_STATUS = "dev.niccc2007.filet.SPLIT_INSTALL_STATUS"

    /**
     * Turn one status broadcast into a sentence, or into the confirmation screen.
     *
     * `STATUS_PENDING_USER_ACTION` is not a failure and is the common case: the platform is
     * asking for the same confirmation a plain APK gets, and the intent it hands over has to be
     * started or the install simply never happens and nothing says so.
     */
    fun reading(intent: Intent): Pair<Intent?, String?> {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        return when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm to null
            }
            PackageInstaller.STATUS_SUCCESS -> null to "Installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> null to "Install cancelled"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                null to "Already installed with a different signature. Uninstall the existing copy first."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                null to "This build is not compatible with this device."
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                null to "Not enough space to install this."
            PackageInstaller.STATUS_FAILURE_INVALID ->
                null to "The pieces in this bundle do not form a valid app: ${message ?: "invalid package"}"
            else -> null to (message ?: "The install failed.")
        }
    }
}

/**
 * Receives the session's verdict.
 *
 * Registered at runtime rather than in the manifest: it is only useful while an install this
 * process started is in flight, and a manifest-declared receiver would be woken by the platform
 * long after anybody cared.
 */
class SplitInstallReceiver(private val onResult: (String) -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val (confirm, message) = SplitInstall.reading(intent)
        if (confirm != null) {
            runCatching { context.startActivity(confirm) }
                .onFailure { onResult("Android would not show the install confirmation.") }
            return
        }
        message?.let(onResult)
    }

    companion object {
        fun filter() = IntentFilter(SplitInstall.ACTION_STATUS)
    }
}
