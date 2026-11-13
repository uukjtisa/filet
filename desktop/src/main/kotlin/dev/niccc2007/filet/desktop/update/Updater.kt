package dev.niccc2007.filet.desktop.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Checked, and nothing newer. Shown only after a check that was asked for. */
    data object Current : UpdateState
    data class Available(val offer: Offer) : UpdateState
    data class Downloading(val offer: Offer, val done: Long, val total: Long) : UpdateState
    /** Downloaded and verified; the window closes and the installer runs. */
    data class Installing(val offer: Offer) : UpdateState
    data class Failed(val message: String, val offer: Offer?) : UpdateState
}

/**
 * Updates from the repository's GitHub releases.
 *
 * Decision: install after exit, through a helper, never in place. Windows will not replace the
 * files of a running program, so the installer runs once this process has gone: a small
 * PowerShell command waits for it, runs the MSI quietly, and opens the app again - sharing again
 * if it was sharing. The MSI is a per-user install, so none of it asks for an administrator.
 *
 * Nothing is installed that does not match the SHA-256 GitHub gives for the release's file; a
 * release without one is never offered ([Releases]). Checks on their own are silent when they
 * fail; one that was asked for says why.
 */
class Updater(
    private val scope: CoroutineScope,
    private val current: String = Version.current,
    private val api: String = "https://api.github.com/repos/$REPO/releases?per_page=40",
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** A line about the last update, from the arguments the helper reopened the app with. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val canInstall: Boolean get() = Version.launcher != null

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Reads `--updated` and `--update-failed=N`, which only the helper passes. */
    fun noteArgs(args: Array<String>) {
        _notice.value = when {
            "--updated" in args -> "Updated to $current."
            else -> args.firstOrNull { it.startsWith("--update-failed=") }?.let { a ->
                "The update did not install (code ${a.substringAfter('=')}). This version is still installed. The installer's log is ${File(workDir(), LOG).path}."
            }
        }
    }

    fun dismissNotice() { _notice.value = null }

    /** Back to quiet after a result was read. Never while a download or install is running. */
    fun reset() {
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return
        _state.value = UpdateState.Idle
    }

    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI(api)).timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Filet-Desktop-Network-Tool/$current")
                        .GET().build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                    if (res.statusCode() != 200) error("GitHub answered ${res.statusCode()}.")
                    Releases.newest(res.body(), current)
                }
            }
            _state.value = r.fold(
                onSuccess = { o -> if (o != null) UpdateState.Available(o) else if (manual) UpdateState.Current else UpdateState.Idle },
                onFailure = { e -> if (manual) UpdateState.Failed("Could not check for updates: ${e.message ?: "no connection"}", null) else UpdateState.Idle },
            )
        }
    }

    /**
     * Downloads and verifies [offer], starts the helper, then calls [quit] so the window closes and
     * the helper can install. [share] reopens the app sharing, as it was.
     */
    fun install(offer: Offer, share: Boolean, quit: () -> Unit) {
        val app = Version.launcher ?: return
        _state.value = UpdateState.Downloading(offer, 0, offer.installer.size)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = workDir().apply { mkdirs() }
                    val msi = File(dir, offer.installer.name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                    download(offer, msi)
                    val script = InstallScript.build(ownPids(app), msi.path, File(dir, LOG).path, app, share)
                    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", InstallScript.encode(script))
                        .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "helper.out")).start()
                }
            }
            r.fold(
                onSuccess = { _state.value = UpdateState.Installing(offer); quit() },
                onFailure = { e -> _state.value = UpdateState.Failed(e.message ?: "The update could not be downloaded.", offer) },
            )
        }
    }

    private fun download(offer: Offer, to: File) {
        val a = offer.installer
        val req = HttpRequest.newBuilder(URI(a.url)).timeout(Duration.ofMinutes(10))
            .header("User-Agent", "Filet-Desktop-Network-Tool/$current").GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) error("The download answered ${res.statusCode()}.")
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        res.body().use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    done += n
                    _state.value = UpdateState.Downloading(offer, done, a.size)
                }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (got != a.sha256) {
            to.delete()
            error("The download did not match the release, so nothing was installed.")
        }
    }

    companion object {
        const val REPO = "uukjtisa/filet"

        /**
         * This JVM, and the launcher that started it. The installed app is two processes: the
         * jpackage launcher, which holds `Filet Desktop Network Tool.exe` open, and this JVM under it. The
         * installer has to wait for both, or it finds the exe in use.
         */
        fun ownPids(launcher: String): List<Long> {
            val me = ProcessHandle.current()
            val parent = me.parent().orElse(null)?.takeIf { p ->
                p.info().command().map { File(it).canonicalPath.equals(File(launcher).canonicalPath, ignoreCase = true) }.orElse(false)
            }
            return listOfNotNull(me.pid(), parent?.pid())
        }
        const val LOG = "install.log"

        /** Where downloads, the helper's output and the installer's log go. */
        fun workDir(): File = File(System.getProperty("java.io.tmpdir"), "filet-desktop-network-tool-update")
    }
}

/** The helper that installs after this process exits. Pure text, so its quoting is tested. */
object InstallScript {

    fun build(pids: List<Long>, msi: String, log: String, app: String, share: Boolean): String = buildString {
        val ids = pids.joinToString(",")
        appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
        // Bounded: if the app never exits, the install is abandoned rather than run under it.
        appendLine("Wait-Process -Id $ids -Timeout 90")
        appendLine("if (Get-Process -Id $ids) { exit 1 }")
        appendLine(
            "\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ${q("\"$msi\"")}, '/qn', '/norestart', '/l*v', ${q("\"$log\"")}) -Wait -PassThru",
        )
        appendLine("\$code = if (\$p) { \$p.ExitCode } else { -1 }")
        // 3010 is success that wants a restart; the app itself needs none.
        appendLine("\$a = if (\$code -eq 0 -or \$code -eq 3010) { @('--updated') } else { @(\"--update-failed=\$code\") }")
        if (share) appendLine("\$a = @('--share') + \$a")
        appendLine("Start-Process -FilePath ${q(app)} -ArgumentList \$a")
    }

    /** As `-EncodedCommand` takes it: UTF-16LE, base64. No quoting survives a command line better. */
    fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    /** A PowerShell single-quoted literal: nothing inside is expanded, and a quote is doubled. */
    fun q(s: String): String = "'" + s.replace("'", "''") + "'"
}
