package dev.niccc2007.filet.desktop.mount

import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Linux: `mount.davfs` where it can work unprivileged, `gio` where it cannot.
 *
 * `mount.davfs` only mounts for an ordinary user when `/etc/fstab` already has a line for that
 * URL and mountpoint with the `user` option - anything else needs root, and this tool never runs
 * sudo silently. So the order is: davfs when the fstab line exists, then `gio mount` (GVfs, no root,
 * present on most desktops), and when neither can work, a refusal that says exactly what to add.
 * See [LinuxMountErrors] for why Linux has no API to call.
 *
 * Neither path is verified on a Linux machine yet.
 */
class LinuxMounter(
    private val fstab: () -> String = { readOrEmpty("/etc/fstab") },
    private val procMounts: () -> String = { readOrEmpty("/proc/mounts") },
) : Mounter {
    override val platform = Platform.LINUX

    private val davfs: String? get() = listOf("/sbin/mount.davfs", "/usr/sbin/mount.davfs", "/usr/bin/mount.davfs").firstOrNull { File(it).canExecute() }
    private val gio: String? get() = listOf("/usr/bin/gio", "/bin/gio").firstOrNull { File(it).canExecute() }

    override fun unavailable(): String? =
        if (davfs == null && gio == null)
            "Mounting needs davfs2 or GVfs. Install one, for example: sudo apt install davfs2 (or gvfs-backends)."
        else null

    override fun attached(): List<Attached> {
        val dav = LinuxMounts.davfs(procMounts()).map { (url, dir) -> Attached(url, dir) }
        val gvfs = gvfsRoot()?.listFiles().orEmpty().mapNotNull { f ->
            LinuxMounts.parseGvfsName(f.name)?.let { Attached(it, f.path) }
        }
        return dav + gvfs
    }

    override fun mount(target: DavTarget, label: String): MountResult {
        attached().firstOrNull { it.target == target }?.let { return MountResult.Ok(it.where) }
        val d = davfs
        val line = LinuxMounts.fstabLine(fstab(), target.url)
        if (d != null && line != null) {
            val st = run(listOf("mount", line))
            return if (st.first == 0) MountResult.Ok(line) else MountResult.Failed(detail(LinuxMountErrors.message("mount.davfs", st.first), st.second), st.first)
        }
        val g = gio ?: return MountResult.Failed(
            "mount.davfs needs a line in /etc/fstab before it will mount without root:\n" +
                LinuxMounts.suggestedFstab(target.url, label) +
                "\nand membership of the davfs2 group (sudo usermod -aG davfs2 \$USER). Or install GVfs for a mount that needs neither.",
        )
        // The server asks for no sign-in, but gio still prompts for one on some setups; two empty
        // lines on stdin answer user and password and keep it from waiting forever.
        val st = run(listOf(g, "mount", target.gioUrl), stdin = "\n\n")
        if (st.first != 0) return MountResult.Failed(detail(LinuxMountErrors.message("gio", st.first), st.second), st.first)
        val where = attached().firstOrNull { it.target == target }?.where ?: (gvfsRoot()?.path ?: target.gioUrl)
        return MountResult.Ok(where)
    }

    override fun unmount(mount: Attached, force: Boolean): MountResult {
        val inGvfs = gvfsRoot()?.let { mount.where.startsWith(it.path) } == true
        val st = if (inGvfs) run(listOf(gio ?: "gio", "mount", "-u", mount.target.gioUrl))
        else run(listOf("umount") + (if (force) listOf("-l") else emptyList()) + mount.where)
        // Gone already counts as done; the list is re-read from the OS either way.
        if (st.first == 0 || attached().none { it.where == mount.where }) return MountResult.Ok(mount.where)
        return MountResult.Failed(detail(LinuxMountErrors.message(if (inGvfs) "gio" else "umount", st.first), st.second), st.first, canForce = !inGvfs)
    }

    private fun gvfsRoot(): File? {
        val uid = runCatching { File("/proc/self").canonicalFile.let { java.nio.file.Files.getAttribute(it.toPath(), "unix:uid") as Int } }.getOrNull()
            ?: return null
        return File("/run/user/$uid/gvfs").takeIf { it.isDirectory }
    }

    private fun detail(message: String, text: String) = if (text.isBlank()) message else "$message\n${text.trim().take(300)}"

    /** Exit status and combined output. `LC_ALL=C` so the detail is the same on every locale. */
    private fun run(cmd: List<String>, stdin: String = ""): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return 124 to "timed out" }
        p.exitValue() to text
    }.getOrElse { 127 to (it.message ?: "could not start ${cmd.first()}") }

    private companion object {
        fun readOrEmpty(path: String) = runCatching { File(path).readText() }.getOrDefault("")
    }
}

/** The pure part of the Linux mounter: reading `/proc/mounts`, `/etc/fstab` and GVfs names. */
object LinuxMounts {
    /** davfs mounts whose source is a Filet share, as (target, mountpoint). */
    fun davfs(procMounts: String): List<Pair<DavTarget, String>> = procMounts.lineSequence().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size < 3 || !f[2].contains("davfs")) return@mapNotNull null
        val t = parseUrl(unescape(f[0])) ?: return@mapNotNull null
        t to unescape(f[1])
    }.toList()

    /** The mountpoint of the fstab line for [url] with the `user` option, or null. */
    fun fstabLine(fstab: String, url: String): String? = fstab.lineSequence().mapNotNull { l ->
        val f = l.substringBefore('#').trim().split(Regex("\\s+"))
        if (f.size < 4 || f[2] != "davfs") return@mapNotNull null
        val sameUrl = unescape(f[0]).trimEnd('/') == url.trimEnd('/')
        val user = f[3].split(',').any { it == "user" || it == "users" }
        if (sameUrl && user) unescape(f[1]) else null
    }.firstOrNull()

    fun suggestedFstab(url: String, label: String): String {
        val dir = "/media/filet/" + label.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "phone" }
        return "$url  $dir  davfs  user,noauto,rw  0  0"
    }

    /** `http://host:port/a/code/` back to a target, or null when it is not a Filet share. */
    fun parseUrl(url: String): DavTarget? {
        val m = Regex("^(https?)://(\\[[^]]+]|[^/:]+):(\\d+)/a/([A-Za-z0-9]+)/?$").find(url) ?: return null
        val (scheme, host, port, code) = m.destructured
        return DavTarget(host.trim('[', ']'), port.toInt(), scheme == "https", "/a/", code)
    }

    /** A GVfs directory name such as `dav:host=10.0.0.5,port=8321,ssl=false,prefix=%2Fa%2Fxyz`. */
    fun parseGvfsName(name: String): DavTarget? {
        if (!name.startsWith("dav:") && !name.startsWith("davs:")) return null
        val kv = name.substringAfter(':').split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val host = kv["host"] ?: return null
        val ssl = kv["ssl"] == "true" || name.startsWith("davs:")
        val port = kv["port"]?.toIntOrNull() ?: if (ssl) 443 else 80
        val prefix = URLDecoder.decode(kv["prefix"] ?: return null, Charsets.UTF_8).trim('/')
        if (!prefix.startsWith("a/")) return null
        val code = prefix.removePrefix("a/").takeIf { it.isNotEmpty() && '/' !in it } ?: return null
        return DavTarget(host, port, ssl, "/a/", code)
    }

    /** `/proc/mounts` and fstab escape spaces and tabs as octal, `\040`. */
    private fun unescape(s: String) = Regex("\\\\([0-7]{3})").replace(s) { it.groupValues[1].toInt(8).toChar().toString() }
}
