package dev.niccc2007.filet.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.niccc2007.filet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A published release, as much of it as matters here. */
data class Release(
    val tag: String,
    val name: String,
    val notes: String,
    val url: String,
    val apkUrl: String?,
    val apkBytes: Long,
    val preRelease: Boolean,
) {
    val version: Version get() = Version.parse(tag)
}

/**
 * A semantic version, comparable, tolerant of the ways a tag gets written.
 *
 * `v0.1.3`, `0.1.3`, `0.2.0-beta.1` all parse. Anything unparseable sorts lowest rather than
 * throwing: a malformed tag on the server must not stop the check, it must just lose.
 */
data class Version(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    /** Empty for a stable release. A pre-release sorts BELOW the same numbers without one. */
    val pre: String = "",
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // 1.0.0 beats 1.0.0-rc.2, and 1.0.0-rc.2 beats 1.0.0-rc.1.
        return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            other.pre.isEmpty() -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-$pre"

    companion object {
        private val RE = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+](.+))?""")

        fun parse(raw: String?): Version {
            val m = RE.find(raw.orEmpty().trim()) ?: return Version()
            return Version(
                major = m.groupValues[1].toIntOrNull() ?: 0,
                minor = m.groupValues[2].toIntOrNull() ?: 0,
                patch = m.groupValues[3].toIntOrNull() ?: 0,
                pre = m.groupValues[4],
            )
        }
    }
}

/** What the UI is showing while an update is being fetched. */
sealed interface Download {
    data class Progress(val percent: Int, val bytes: Long, val total: Long) : Download
    data class Finished(val bytes: Long) : Download
    data class Failed(val reason: String) : Download
}

/**
 * The in-app updater, GitHub Releases only.
 *
 * ## Why it exists at all, and only on one flavour
 *
 * There is no store listing, so without this the only way to learn a fix shipped is to go and
 * look. The `fdroid` flavour compiles it out entirely (`BuildConfig.UPDATER_ENABLED`) because
 * F-Droid forbids an app that updates itself, and their build would be rejected for it.
 *
 * ## No new dependencies
 *
 * `HttpURLConnection` and `org.json`, both already in the app. An updater is a few hundred
 * lines and one endpoint; adding an HTTP stack and a serialisation library to the APK so that
 * those lines read slightly better is a poor trade in a file manager.
 *
 * ## It never installs anything by itself
 *
 * The download is explicit, and installing hands off to the system package installer, which
 * asks the user in its own UI. Filet cannot and should not install silently.
 */
object Updater {

    private const val OWNER = "uukjtisa"
    private const val REPO = "filet"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases"

    /**
     * Where the last successful download landed.
     *
     * Held here rather than handed up through the UI state so no layer above this one has to
     * name a `java.io.File` (PLAN.md R3). Cleared by [sweep]; null after a process restart,
     * which is correct - a download that did not survive the process should not be offered
     * for install as though it had.
     */
    @Volatile private var downloaded: File? = null

    /** The version running right now. */
    val installed: Version get() = Version.parse(BuildConfig.VERSION_NAME)

    /**
     * The newest release on the channel, newer than installed or not.
     *
     * Returning it either way is deliberate: a page whose job is to show what changed still
     * has to work for somebody already up to date, and "you are current" is only believable
     * beside the notes it refers to.
     *
     * @param includePreRelease follow the pre-release channel as well as stable.
     */
    suspend fun latest(includePreRelease: Boolean = false): Result<Release?> =
        withContext(Dispatchers.IO) {
            // Result, not a nullable Release. "Nothing is published yet" and "the network is
            // down" are different answers and a null cannot tell them apart - reporting the
            // first as the second is a lie the user would act on by checking their wifi.
            runCatching {
                val body = get(API + "?per_page=20") ?: error("GitHub did not answer")
                val arr = JSONArray(body)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .filter { !it.optBoolean("draft", false) }
                    .filter { includePreRelease || !it.optBoolean("prerelease", false) }
                    .map { it.toRelease() }
                    .maxByOrNull { it.version }
            }
        }

    /** The newest release if it is newer than this build, otherwise null. */
    suspend fun check(includePreRelease: Boolean = false): Release? {
        val r = latest(includePreRelease).getOrNull() ?: return null
        return if (r.version > installed) r else null
    }

    /**
     * Fetch the APK, reporting progress.
     *
     * Downloaded into `getExternalFilesDir("update")` rather than app-private internal
     * storage: the package installer is a different process and has to be able to read the
     * file through the FileProvider, and this location is the one already declared in
     * `file_paths.xml`.
     */
    fun download(context: Context, release: Release): Flow<Download> = flow {
        val url = release.apkUrl
        if (url == null) {
            emit(Download.Failed("That release has no APK attached."))
            return@flow
        }
        val target = File(context.getExternalFilesDir("update"), "filet-${release.tag}.apk")
        // A part-file from a previous attempt is worse than no file: the installer would
        // reject it with a parse error that reads like a corrupt release.
        target.delete()
        target.parentFile?.mkdirs()

        var ok = false
        try {
            val conn = open(url)
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        // Emitting every 64 KB is thousands of recompositions on a 30 MB APK.
                        if (percent != lastPercent) {
                            lastPercent = percent
                            emit(Download.Progress(percent, done, total))
                        }
                    }
                    if (total > 0 && done != total) throw IllegalStateException("truncated download")
                }
            }
            ok = true
            downloaded = target
            emit(Download.Finished(target.length()))
        } catch (e: Throwable) {
            emit(Download.Failed(e.message ?: "download failed"))
        } finally {
            if (!ok) target.delete()
        }
    }.flowOn(Dispatchers.IO)

    /** Hand the downloaded APK to the system installer. It asks the user; Filet does not. */
    fun install(context: Context): Intent? = runCatching {
        val apk = downloaded ?: return null
        if (!apk.isFile) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    /** Clear anything left in the update folder. Called after a successful hand-off. */
    fun sweep(context: Context) {
        downloaded = null
        runCatching { context.getExternalFilesDir("update")?.listFiles()?.forEach { it.delete() } }
    }

    // ── wire ──

    private fun JSONObject.toRelease(): Release {
        val assets = optJSONArray("assets")
        var apkUrl: String? = null
        var apkBytes = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                // The github flavour's APK, not the fdroid one, and not a mapping file.
                if (name.endsWith(".apk", true) && !name.contains("fdroid", true)) {
                    apkUrl = a.optString("browser_download_url").ifEmpty { null }
                    apkBytes = a.optLong("size", 0L)
                    break
                }
            }
        }
        val tag = optString("tag_name").ifEmpty { optString("name") }
        return Release(
            tag = tag,
            name = optString("name").ifEmpty { tag },
            notes = optString("body"),
            url = optString("html_url"),
            apkUrl = apkUrl,
            apkBytes = apkBytes,
            preRelease = optBoolean("prerelease", false),
        )
    }

    private fun get(url: String): String? = runCatching {
        val conn = open(url)
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // GitHub rate-limits by User-Agent and rejects requests without one.
            setRequestProperty("User-Agent", "Filet/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
}
