package dev.niccc2007.filet.portal

import org.json.JSONArray
import org.json.JSONObject

/** One stretch of one file, sealed as its own blob, and the hosts to try for it in order. */
data class PlanPart(val file: Int, val offset: Long, val length: Long, val hosts: List<PortalHost>)

/**
 * How a send is spread over the hosts.
 *
 * Every file is cut into parts no bigger than the largest host takes, and the parts are dealt out
 * round the hosts in turn, so one host is never asked for everything and a busy host's refusal
 * moves only its own part. Scatter cuts every file small and deals the pieces over all hosts, so
 * no host holds even the ciphertext of a whole file.
 */
object PortalPlan {
    /** A scattered piece: small enough that every host takes it, large enough not to be thousands. */
    const val SCATTER_PART = 8L * 1024 * 1024

    fun plan(sizes: List<Long>, hosts: List<PortalHost>, scatter: Boolean): List<PlanPart> {
        if (hosts.isEmpty()) return emptyList()
        val biggest = hosts.maxOf { PortalCrypto.plainFitting(it.maxBytes) }
        val smallest = hosts.minOf { PortalCrypto.plainFitting(it.maxBytes) }
        val cut = if (scatter) minOf(SCATTER_PART, smallest) else biggest
        var turn = 0
        val out = ArrayList<PlanPart>()
        sizes.forEachIndexed { file, size ->
            var offset = 0L
            do {
                val length = minOf(cut, (size - offset).coerceAtLeast(0))
                val sealed = PortalCrypto.sealedSize(length)
                // This turn's host first, then the rest in turn; only hosts the part fits on.
                val order = hosts.indices.map { hosts[(turn + it) % hosts.size] }.filter { it.maxBytes >= sealed }
                out += PlanPart(file, offset, length, order)
                turn++
                offset += length
            } while (offset < size)
        }
        return out
    }
}

data class ManifestPart(val url: String, val length: Long)

data class ManifestFile(val name: String, val size: Long, val parts: List<ManifestPart>)

/**
 * What a Portal link opens: the files, and where each part of each file is. Sealed with the
 * send's key before it is uploaded, so names and sizes are as private as the files.
 */
data class PortalManifest(val from: String, val at: Long, val expires: Long, val files: List<ManifestFile>) {

    fun json(): String = JSONObject().apply {
        put("v", 1)
        put("from", from)
        put("at", at)
        put("exp", expires)
        put("files", JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("n", f.name)
                    put("s", f.size)
                    put("p", JSONArray().apply { f.parts.forEach { put(JSONObject().put("u", it.url).put("l", it.length)) } })
                })
            }
        })
    }.toString()

    val total: Long get() = files.sumOf { it.size }

    companion object {
        const val MAX_FILES = 5000
        const val MAX_PARTS = 20000

        /**
         * The manifest in [text], or null where anything is off. Strict, because once the key
         * opens it every link in it is fetched: each must be https on a Portal host, and each
         * file's parts must add up to its size.
         */
        fun of(text: String): PortalManifest? = runCatching {
            val o = JSONObject(text)
            if (o.optInt("v") != 1) return null
            val arr = o.getJSONArray("files")
            if (arr.length() > MAX_FILES) return null
            var parts = 0
            val files = (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                val p = f.getJSONArray("p")
                parts += p.length()
                val list = (0 until p.length()).map { j ->
                    val x = p.getJSONObject(j)
                    ManifestPart(x.getString("u"), x.getLong("l"))
                }
                val size = f.getLong("s")
                if (size < 0 || list.isEmpty() || list.any { it.length < 0 || !PortalHost.anyOwns(it.url) }) return null
                if (list.sumOf { it.length } != size) return null
                ManifestFile(f.getString("n").take(240), size, list)
            }
            if (files.isEmpty() || parts > MAX_PARTS) return null
            PortalManifest(o.optString("from").take(80), o.optLong("at"), o.optLong("exp"), files)
        }.getOrNull()
    }
}
