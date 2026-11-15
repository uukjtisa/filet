package dev.niccc2007.filet.portal

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.jobs.JobKind
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.link.PortalLink
import dev.niccc2007.filet.nearby.PushRules
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** One file of a send, and how it went. */
data class SentFile(val name: String, val path: String, val size: Long, val hosts: List<String>, val failed: Boolean)

/** A Portal send as the Portal page lists it. [link] is empty while it runs and when it failed. */
data class PortalSend(
    val id: Long,
    val at: Long,
    val expires: Long,
    val link: String,
    val files: List<SentFile>,
    val running: Boolean = false,
    val error: String = "",
    val doneBytes: Long = 0,
) {
    val total: Long get() = files.sumOf { it.size }
    val failed: List<SentFile> get() = files.filter { it.failed }
}

/**
 * Filet Portal: files sent over the internet. Each send is sealed with its own key, spread over
 * the free hosts that are on, and listed in a sealed manifest; the link carries the manifest's
 * address and the key. The other side opens the link in Filet, sees the files, and downloads.
 */
class Portal(
    private val context: Context,
    private val vfs: Vfs,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val received: VPath,
    private val ledger: JobLedger,
    private val files: dev.niccc2007.filet.data.AppFiles,
    private val selfName: () -> String,
) {
    private val _hosts = MutableStateFlow(readHosts())
    val hosts: StateFlow<Set<PortalHost>> = _hosts.asStateFlow()

    private val _keep = MutableStateFlow(prefs.getString(KEY_KEEP)?.toLongOrNull()?.takeIf { it in KEEPS } ?: 12 * HOUR)
    val keep: StateFlow<Long> = _keep.asStateFlow()

    private val _scatter = MutableStateFlow(prefs.getString(KEY_SCATTER) == "1")
    val scatter: StateFlow<Boolean> = _scatter.asStateFlow()

    private val _sends = MutableStateFlow(readSends())
    val sends: StateFlow<List<PortalSend>> = _sends.asStateFlow()

    fun setHost(host: PortalHost, on: Boolean) {
        _hosts.value = if (on) _hosts.value + host else _hosts.value - host
        prefs.putString(KEY_HOSTS, _hosts.value.joinToString(",") { it.id }.ifEmpty { NONE })
    }

    fun setKeep(ms: Long) {
        _keep.value = ms
        prefs.putString(KEY_KEEP, ms.toString())
    }

    fun setScatter(on: Boolean) {
        _scatter.value = on
        prefs.putString(KEY_SCATTER, if (on) "1" else "")
    }

    /** The hosts a send would use now: on, and keeping files at least as long as asked. */
    fun usable(): List<PortalHost> = PortalHost.entries.filter { it in _hosts.value && it.keeps(_keep.value) }

    /** Read the sends again: a running one is kept as it is, and the list is drawn afresh. */
    fun reload() {
        val running = _sends.value.filter { it.running }
        _sends.value = running + readSends().filter { s -> running.none { it.id == s.id } }
    }

    fun forget(id: Long) {
        _sends.value = _sends.value.filterNot { it.id == id }
        saveSends()
    }

    // ── sending ──

    /** Send [nodes] in the background; the send shows on the Portal page as it runs. Returns its id. */
    fun send(nodes: List<VNode>): Long {
        val id = System.currentTimeMillis()
        val keepFor = _keep.value
        val hosts = usable()
        val start = PortalSend(id, id, 0, "", nodes.map { SentFile(it.name + if (it.isDir) ".zip" else "", it.path.toString(), it.size, emptyList(), false) }, running = true)
        _sends.value = listOf(start) + _sends.value
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val temps = ArrayList<VPath>()
            var jobId = -1L
            try {
                if (hosts.isEmpty()) error("No host is on that keeps files that long. Turn one on, or keep files for less time.")
                // A folder goes as a zip made first, because a host is told the size before the upload.
                val items = nodes.map { n ->
                    if (n.isDir) {
                        val zip = files.portalStage("$id-${n.name}.zip")
                        temps += zip
                        vfs.openWrite(zip).buffered().use { zipFolder(n.path, it) }
                        Item(n.name + ".zip", zip, vfs.stat(zip)?.size ?: 0L, n.path.toString())
                    } else {
                        val size = if (n.size >= 0) n.size else vfs.stat(n.path)?.size ?: 0L
                        Item(n.name, n.path, size, n.path.toString())
                    }
                }
                val total = items.sumOf { it.size }
                update(id) { s -> s.copy(files = items.map { SentFile(it.name, it.origin, it.size, emptyList(), false) }) }
                jobId = ledger.start(
                    title = if (items.size == 1) "Sending ${items.first().name}" else "Sending ${items.size} files",
                    subtitle = "with Filet Portal",
                    source = "Portal",
                    cancel = job,
                    kind = JobKind.UPLOAD,
                    totalItems = items.size,
                )
                val key = PortalCrypto.newKey()
                val plan = PortalPlan.plan(items.map { it.size }, hosts, _scatter.value)
                val parts = HashMap<Int, MutableList<ManifestPart>>()
                val used = HashMap<Int, MutableSet<PortalHost>>()
                val failed = HashSet<Int>()
                var done = 0L
                var shown = 0L
                for (part in plan) {
                    if (!coroutineContext.isActive) error("stopped")
                    if (part.file in failed) { done += part.length; continue }
                    val item = items[part.file]
                    var url: String? = null
                    for (host in part.hosts) {
                        val before = done
                        url = runCatching {
                            // Straight from where the file is, a network drive too: no copy first.
                            vfs.openReadAt(item.path, part.offset).use { input ->
                                upload(host, keepFor, PortalCrypto.sealedSize(part.length)) { out ->
                                    PortalCrypto.seal(key, input, part.length, out) { n ->
                                        done += n
                                        if (done - shown >= SHOW_EVERY || done >= total) {
                                            shown = done
                                            ledger.progress(jobId, if (total > 0) done.toFloat() / total else null, item.name, doneItems = part.file, doneBytes = done, totalBytes = total)
                                            _sends.value = _sends.value.map { if (it.id == id) it.copy(doneBytes = done) else it }
                                        }
                                    }
                                }
                            }
                        }.getOrNull()
                        if (url != null) { used.getOrPut(part.file) { HashSet() } += host; break }
                        done = before
                    }
                    if (url == null) { failed += part.file; done += part.length } else parts.getOrPut(part.file) { ArrayList() } += ManifestPart(url, part.length)
                }
                val okFiles = items.indices.filter { it !in failed }
                if (okFiles.isEmpty()) error("No host took the files. Check the connection, or turn on another host.")
                val now = System.currentTimeMillis()
                val manifest = PortalManifest(
                    selfName(), now, now + keepFor,
                    okFiles.map { i -> ManifestFile(items[i].name, items[i].size, parts[i].orEmpty()) },
                )
                val sealed = PortalCrypto.sealBytes(key, manifest.json().toByteArray())
                var where: String? = null
                var manifestHost: PortalHost? = null
                for (host in hosts) {
                    where = runCatching { upload(host, keepFor, sealed.size.toLong()) { it.write(sealed) } }.getOrNull()
                    if (where != null) { manifestHost = host; break }
                }
                if (where == null) error("No host took the list of files, so there is no link. Try again.")
                val lasts = (used.values.flatten() + listOfNotNull(manifestHost)).toSet().minOf { it.lifetime(keepFor) }
                val link = PortalLink(where, PortalCrypto.keyText(key)).link().web()
                update(id) { s ->
                    s.copy(
                        at = now, expires = now + lasts, link = link, running = false,
                        files = items.mapIndexed { i, it -> SentFile(it.name, it.origin, it.size, used[i].orEmpty().map { h -> h.label }, i in failed) },
                    )
                }
                ledger.finish(jobId, if (failed.isEmpty()) "Link ready on the Portal page" else "${failed.size} of ${items.size} did not go · Portal page")
            } catch (e: Throwable) {
                val why = e.message ?: "could not be sent"
                update(id) { it.copy(running = false, error = why) }
                if (jobId >= 0) ledger.fail(jobId, why)
            } finally {
                withContext(NonCancellable) { temps.forEach { runCatching { vfs.delete(it) } } }
            }
        }
        job.start()
        return id
    }

    /**
     * Send again, as a new send with its own link: the files of [send] that failed, or [all] of
     * them when the send never got a link. The failed send leaves the list.
     */
    fun resend(send: PortalSend, all: Boolean = false) {
        if (all) forget(send.id)
        scope.launch(Dispatchers.IO) {
            val nodes = (if (all) send.files else send.failed).mapNotNull { f -> runCatching { vfs.stat(VPath.parse(f.path)) }.getOrNull() }
            if (nodes.isNotEmpty()) send(nodes)
        }
    }

    private data class Item(val name: String, val path: VPath, val size: Long, val origin: String)

    private suspend fun zipFolder(root: VPath, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(out).use { zos ->
            suspend fun walk(dir: VPath, prefix: String, depth: Int) {
                if (depth > 24) return
                for (kid in vfs.list(dir)) {
                    val entry = prefix + kid.name
                    if (kid.isDir) {
                        walk(kid.path, "$entry/", depth + 1)
                    } else {
                        zos.putNextEntry(ZipEntry(entry))
                        vfs.openRead(kid.path).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            walk(root, "", 0)
        }
    }

    private fun upload(host: PortalHost, keepFor: Long, length: Long, body: (java.io.OutputStream) -> Unit): String {
        val text = PortalHttp.upload(host.request(keepFor), randomName(), length, body)
        return host.answer(text) ?: throw IOException("${host.label} gave no link")
    }

    /** A name that says nothing: the host sees this and ciphertext, never the file's own name. */
    private fun randomName(): String =
        PortalCrypto.keyText(PortalCrypto.newKey()).take(12).replace('-', 'x').replace('_', 'y') + ".bin"

    private fun update(id: Long, change: (PortalSend) -> PortalSend) {
        _sends.value = _sends.value.map { if (it.id == id) change(it) else it }
        saveSends()
    }

    // ── receiving ──

    /** Fetch and open the manifest [link] points at. Throws with a sentence to show. */
    suspend fun look(link: PortalLink): PortalManifest = withContext(Dispatchers.IO) {
        if (!PortalHost.anyOwns(link.manifest)) throw IOException("This link points somewhere Filet Portal does not use.")
        val key = PortalCrypto.keyOf(link.key) ?: throw IOException("This link is damaged.")
        val sealed = PortalHttp.fetchBytes(link.manifest, MAX_MANIFEST)
            ?: throw IOException("These files are gone: they expired, or the host took them down.")
        val plain = runCatching { PortalCrypto.openBytes(key, sealed) }.getOrElse {
            throw IOException("This link's key does not open these files. Ask for the link again.")
        }
        PortalManifest.of(String(plain)) ?: throw IOException("This send is from a newer Filet, or damaged.")
    }

    /**
     * Download [manifest]'s files into [into] as one Activity job. A file that fails is removed and
     * named at the end; the rest still land.
     */
    fun download(link: PortalLink, manifest: PortalManifest, into: VPath = received) {
        val key = PortalCrypto.keyOf(link.key) ?: return
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var id = -1L
            try {
                runCatching { if (vfs.stat(into) == null) vfs.create(into, isDir = true) }
                val there = runCatching { vfs.list(into).map { it.name }.toHashSet() }.getOrElse { hashSetOf() }
                val targets = manifest.files.map { f -> into.child(PushRules.landName(f.name) { it in there }.also { there += it }) }
                val total = manifest.total
                id = ledger.start(
                    title = if (targets.size == 1) "Receiving ${targets.first().name}" else "Receiving ${targets.size} files",
                    subtitle = "from ${manifest.from.ifEmpty { "a Filet" }} by Portal",
                    source = "Portal",
                    cancel = job,
                    opened = (targets.singleOrNull() ?: into).toString(),
                    kind = JobKind.RECEIVED,
                    to = into.toString(),
                    totalItems = targets.size,
                )
                var done = 0L
                var shown = 0L
                val missed = ArrayList<String>()
                manifest.files.forEachIndexed { i, f ->
                    val target = targets[i]
                    val start = done
                    val ok = runCatching {
                        vfs.openWrite(target).use { out ->
                            for (p in f.parts) {
                                if (!isActive) error("stopped")
                                val wrote = PortalHttp.fetch(p.url) { input ->
                                    PortalCrypto.open(key, input, out) { n ->
                                        done += n
                                        if (done - shown >= SHOW_EVERY || done >= total) {
                                            shown = done
                                            ledger.progress(id, if (total > 0) done.toFloat() / total else null, target.name, doneItems = i, doneBytes = done, totalBytes = total)
                                        }
                                    }
                                }
                                if (wrote != p.length) throw IOException("a part is the wrong size")
                            }
                        }
                    }.isSuccess
                    if (!ok) {
                        if (!isActive) error("stopped")
                        runCatching { vfs.delete(target) }
                        missed += f.name
                        done = start + f.size
                    }
                }
                val place = if (into == received) "Filet/Received" else into.name
                when {
                    missed.isEmpty() -> ledger.finish(id, place)
                    missed.size == targets.size -> ledger.fail(id, "None arrived: the host no longer has them, or the connection dropped")
                    else -> ledger.finish(id, "$place · did not arrive: ${missed.joinToString()}")
                }
            } catch (e: Throwable) {
                if (id >= 0) ledger.fail(id, e.message ?: "could not be downloaded")
            }
        }
        job.start()
    }

    /** Filet/Received, where Portal downloads land unless another folder is chosen. */
    val inbox: VPath get() = received

    // ── storage ──

    private fun readHosts(): Set<PortalHost> {
        val stored = prefs.getString(KEY_HOSTS) ?: return PortalHost.entries.toSet()
        if (stored == NONE) return emptySet()
        return stored.split(',').mapNotNull { PortalHost.of(it) }.toSet()
    }

    private fun readSends(): List<PortalSend> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SENDS) ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val fs = o.getJSONArray("files")
            PortalSend(
                o.getLong("id"), o.getLong("at"), o.getLong("exp"), o.optString("link"),
                (0 until fs.length()).map { j ->
                    val f = fs.getJSONObject(j)
                    val hs = f.optJSONArray("hosts") ?: JSONArray()
                    SentFile(f.getString("n"), f.optString("path"), f.optLong("s"), (0 until hs.length()).map { hs.getString(it) }, f.optBoolean("bad"))
                },
                // A send that was running when Filet closed did not finish.
                running = false,
                error = o.optString("err").ifEmpty { if (o.optString("link").isEmpty()) "Stopped when Filet closed" else "" },
            )
        }
    }.getOrDefault(emptyList())

    private fun saveSends() {
        val arr = JSONArray()
        _sends.value.take(MAX_SENDS).forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("at", s.at); put("exp", s.expires); put("link", s.link); put("err", s.error)
                put("files", JSONArray().apply {
                    s.files.forEach { f ->
                        put(JSONObject().put("n", f.name).put("path", f.path).put("s", f.size).put("bad", f.failed).put("hosts", JSONArray(f.hosts)))
                    }
                })
            })
        }
        prefs.putString(KEY_SENDS, arr.toString())
    }

    companion object {
        const val KEY_HOSTS = "portal.hosts"
        const val KEY_KEEP = "portal.keep"
        const val KEY_SCATTER = "portal.scatter"
        /** Holds every send's link, key included, so it never leaves the phone in an export. */
        const val KEY_SENDS = "portal.sends.v1"
        private const val NONE = "-"
        private const val MAX_SENDS = 50
        private const val MAX_MANIFEST = 8L * 1024 * 1024
        private const val SHOW_EVERY = 512L * 1024

        /** The lifetimes offered, shortest first. */
        val KEEPS = listOf(1 * HOUR, 12 * HOUR, 1 * DAY, 3 * DAY)

        fun keepLabel(ms: Long): String = when (ms) {
            1 * HOUR -> "1 hour"
            12 * HOUR -> "12 hours"
            1 * DAY -> "1 day"
            3 * DAY -> "3 days"
            else -> "${ms / HOUR} hours"
        }
    }
}
