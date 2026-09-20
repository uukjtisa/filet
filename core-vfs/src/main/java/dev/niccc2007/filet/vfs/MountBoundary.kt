package dev.niccc2007.filet.vfs

/**
 * Going up out of a mounted container, rather than through the hole in its side.
 *
 * Bug identified: browsing inside an APK or an archive and pressing back at the top of it left
 * the whole container and landed in the folder it lives in, skipping every level in between -
 * and worse, the intermediate paths it produced were nonsense.
 *
 * A mount is addressed `apk:///storage/Download/app.apk!/com/example`, with `!` marking where
 * the real filesystem stops and the contents begin. [VPath.parent] is a plain string walk with
 * no idea that boundary exists, so from the container's own root it happily produced
 * `apk:///storage/Download` - an archive scheme pointing at a directory, which no provider can
 * list. Whatever that failed into was what the reader saw.
 *
 * Going up has three cases and only the first two were ever handled:
 *
 *  - **Inside the container.** Ordinary parent, staying in the mount.
 *  - **Outside a container.** Ordinary parent.
 *  - **Standing at the container's root.** Leave the mount and land on the FOLDER the
 *    container file sits in, back on the real filesystem. That is the level above it, and it
 *    is the one case a string walk cannot express.
 */
object MountBoundary {

    /** Schemes addressed as `<scheme>:///real/path!/inner/path`. */
    val MOUNTED_SCHEMES = setOf("zip", "apk")

    private const val SEP = '!'

    /** Whether this path is inside a mounted container. */
    fun isMounted(path: VPath): Boolean =
        path.scheme in MOUNTED_SCHEMES && path.path.indexOf(SEP) >= 0

    /**
     * Split a mounted path into the container file and the path inside it.
     *
     * @return null when [path] is not a mount.
     */
    fun split(path: VPath): Pair<String, String>? {
        if (!isMounted(path)) return null
        val i = path.path.indexOf(SEP)
        val host = path.path.substring(0, i)
        val inner = path.path.substring(i + 1).trim('/')
        return host to inner
    }

    /** Whether this path is the top level of a mounted container. */
    fun isContainerRoot(path: VPath): Boolean = split(path)?.second?.isEmpty() == true

    /**
     * One level up from [path], crossing out of a container when standing at its root.
     *
     * @param hostScheme the scheme the container file itself lives under. Local storage in
     *   every case Filet can currently mount from, and named rather than assumed so a mount of
     *   a file on a network share does not silently come back as a local path.
     * @return the path above, or null when there is nowhere above to go.
     */
    fun up(path: VPath, hostScheme: String = "local"): VPath? {
        val parts = split(path)
        if (parts == null) return path.parent

        val (host, inner) = parts
        if (inner.isNotEmpty()) {
            // Still inside. Drop the last segment and stay in the mount.
            val above = inner.trimEnd('/').substringBeforeLast('/', "")
            return VPath(path.scheme, "$host$SEP/$above")
        }
        // At the container's root. The level above is the folder the container FILE is in, on
        // the real filesystem - not the same string with a scheme that cannot read it.
        val folder = host.trimEnd('/').substringBeforeLast('/', "")
        return VPath(hostScheme, if (folder.isEmpty()) "/" else folder)
    }
}
