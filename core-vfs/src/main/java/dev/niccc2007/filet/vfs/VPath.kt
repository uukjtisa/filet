package dev.niccc2007.filet.vfs

/**
 * A location in the VFS: a provider [scheme] plus an absolute, normalised path.
 *
 * Nothing above L0 constructs a platform path. Everything addresses storage through
 * this type, which is what makes PLAN.md R3 enforceable rather than aspirational.
 *
 * Rendered as `scheme://path`, e.g. `local:///storage/emulated/0/Download`.
 */
data class VPath(val scheme: String, val path: String) {

    init {
        require(scheme.isNotBlank()) { "scheme must not be blank" }
        require(path.startsWith("/")) { "path must be absolute: $path" }
    }

    /** Last segment, or "" at a root. */
    val name: String get() = path.substringAfterLast('/', "")

    val isRoot: Boolean get() = path == "/"

    /** The containing directory, or null at a root. */
    val parent: VPath?
        get() {
            if (isRoot) return null
            val cut = path.trimEnd('/').substringBeforeLast('/', "")
            return VPath(scheme, if (cut.isEmpty()) "/" else cut)
        }

    /** Path segments with empties removed. Root yields an empty list. */
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }

    /**
     * Append one segment.
     *
     * Rejects separators and traversal outright rather than normalising them away —
     * a caller passing "../.." is a bug at the call site, and silently absorbing it is
     * how a traversal reaches a provider.
     */
    fun child(segment: String): VPath {
        require(segment.isNotEmpty()) { "empty segment" }
        require(!segment.contains('/')) { "segment must not contain a separator: $segment" }
        require(segment != "." && segment != "..") { "traversal segment: $segment" }
        val base = path.trimEnd('/')
        return VPath(scheme, "$base/$segment")
    }

    /** True when [other] is this path or lies beneath it. Used to refuse copying a tree into itself. */
    fun contains(other: VPath): Boolean {
        if (other.scheme != scheme) return false
        if (other.path == path) return true
        val base = if (path.endsWith("/")) path else "$path/"
        return other.path.startsWith(base)
    }

    override fun toString(): String = "$scheme://$path"

    companion object {
        /**
         * Parse `scheme://path`, normalising `.`, `..`, duplicate and trailing separators.
         *
         * `..` cannot escape the root: it is clamped there, so no parse result ever
         * addresses something above the volume it names.
         */
        fun parse(raw: String): VPath {
            val sep = raw.indexOf("://")
            require(sep > 0) { "not a VPath: $raw" }
            val scheme = raw.substring(0, sep)
            val rest = raw.substring(sep + 3)
            return of(scheme, rest)
        }

        /** Build from a scheme and a possibly-messy path, applying the same normalisation. */
        fun of(scheme: String, rawPath: String): VPath = VPath(scheme, normalise(rawPath))

        internal fun normalise(rawPath: String): String {
            val out = ArrayDeque<String>()
            for (part in rawPath.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeLast()   // clamp at root
                    else -> out.addLast(part)
                }
            }
            return if (out.isEmpty()) "/" else out.joinToString("/", prefix = "/")
        }
    }
}
