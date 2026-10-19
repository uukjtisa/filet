package dev.niccc2007.filet.desktop

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * One folder offered to the network. Never a drive root - see SPEC.md §1.
 *
 * [tag] is the part of the share's access code that is not the account password, assigned once
 * when the share is added and never re-derived. It cannot come from [name] at serve time: renaming
 * a share would then move its endpoint, and a mapped drive stores the whole endpoint string, so a
 * rename would silently break every PC that had it mounted. Blank on a share that has not been
 * through [Shares.add] yet; [Shares.add] fills it.
 *
 * [code] is a hand-set or regenerated access code that replaces `password + tag` for this share
 * alone, or blank to use the account's. See [Account.codeFor].
 */
data class Share(
    val path: String,
    val name: String,
    val writable: Boolean,
    val tag: String = "",
    val code: String = "",
)

/**
 * The drive-root rule, as a pure function over the path string.
 *
 * Decision: a share is a folder and never the root of a volume. WebDAV stacks handle a drive root
 * badly - a listing of `C:\` answered 403 while every folder inside it listed fine, and the
 * distinction turned out to be that a junction to a FOLDER lists and a junction to a DRIVE ROOT
 * does not. Offering a whole drive means picking its top-level folders.
 *
 * Lexical rather than `Path.getParent() == null`, for two reasons. `Paths.get` parses with the
 * running OS's rules, so on Linux `C:\` is an ordinary relative name with a parent of null and
 * on Windows `/` means "the current drive" - the guard would answer differently per machine and
 * its tests would only prove one of them. And `..` is resolved here before asking: `D:\Docs\..`
 * IS a drive root, and a check that looks at the last segment alone lets it through.
 */
object ShareGuard {

    /** Why [raw] cannot be a share, or null when it can. */
    fun refusal(raw: String): String? {
        val p = raw.trim()
        if (p.isEmpty()) return "No folder was given."
        val parsed = parse(p) ?: return "Not a full path: $p"
        if (parsed.segments.isEmpty()) {
            return "A whole drive cannot be shared. Pick the folders inside it instead."
        }
        return null
    }

    /** True when [raw] names the root of a volume, after `.` and `..` are resolved. */
    fun isRoot(raw: String): Boolean = parse(raw.trim())?.segments?.isEmpty() == true

    private class Parsed(val segments: List<String>)

    /**
     * The path's segments below its volume prefix, or null for a path with no volume prefix.
     *
     * The prefixes are a drive (`C:`), a UNC share (`\\server\share`, whose own root is a root for
     * the same reason a drive's is) and POSIX `/`. The `\\?\` long-path form is unwrapped first,
     * since `\\?\C:\` is `C:\` spelled so that Windows skips its own normalisation.
     */
    private fun parse(raw: String): Parsed? {
        var s = raw.replace('\\', '/')
        if (s.startsWith("//?/UNC/", ignoreCase = true)) s = "//" + s.substring(8)
        else if (s.startsWith("//?/")) s = s.substring(4)
        val rest: String = when {
            DRIVE.matches(s.take(2)) && (s.length == 2 || s[2] == '/') -> s.drop(2)
            s.startsWith("//") -> {
                val parts = s.drop(2).split('/').filter { it.isNotEmpty() }
                // `\\server` alone is not a folder at all, and `\\server\share` is the share's root.
                if (parts.size < 2) return Parsed(emptyList())
                parts.drop(2).joinToString("/")
            }
            s.startsWith("/") -> s
            // `C:Docs` is relative to the current directory ON drive C, and anything else with
            // no prefix is relative to wherever the process was started. Neither is a folder.
            else -> return null
        }
        val out = ArrayDeque<String>()
        for (seg in rest.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return Parsed(out.toList())
    }

    private val DRIVE = Regex("[A-Za-z]:")
}

/** The rules about a list of shares. Pure, so the window and the headless entry share them. */
object Shares {

    /** What [add] decided. */
    sealed interface Added {
        data class Ok(val shares: List<Share>, val share: Share) : Added
        data class Refused(val reason: String) : Added
    }

    /**
     * Add [path] under [name], or say why not.
     *
     * The name defaults to the folder's own and is made unique, because a row is how one share is
     * told from another. Two shares of the same folder are refused rather than deduplicated: the
     * second would be a second endpoint onto the same files under different write rules, which
     * reads as a mistake every time it happens.
     */
    fun add(shares: List<Share>, path: String, name: String?, writable: Boolean): Added {
        ShareGuard.refusal(path)?.let { return Added.Refused(it) }
        val clean = path.trim().trimEnd('/', '\\').ifEmpty { path.trim() }
        if (shares.any { samePath(it.path, clean) }) return Added.Refused("That folder is already shared.")
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: folderName(clean)
        val label = freeName(shares, wanted)
        val share = Share(clean, label, writable, freeTag(shares, label))
        return Added.Ok(shares + share, share)
    }

    /** Remove the share called [name]. */
    fun remove(shares: List<Share>, name: String): List<Share> = shares.filterNot { it.name == name }

    /** Every share with a tag, assigning one to any that arrived without. */
    fun withTags(shares: List<Share>): List<Share> {
        val out = ArrayList<Share>()
        for (s in shares) out += if (s.tag.isNotBlank() && s.tag.all(::isCodeChar)) s
            else s.copy(tag = freeTag(out + shares.filter { it.tag.isNotBlank() }, s.name))
        return out
    }

    /**
     * A tag for [name]: its lowercase ASCII letters and digits, unique across [shares].
     *
     * Lowercase because it is typed on a phone keyboard that capitalises the first letter of a
     * box, and the code is compared exactly. Digits are appended on a clash rather than a random
     * reroll, so the tag still reads as the share it belongs to.
     */
    fun freeTag(shares: List<Share>, name: String): String {
        val base = name.lowercase().filter(::isCodeChar).take(MAX_TAG).ifEmpty { "share" }
        val taken = shares.map { it.tag }.toSet()
        if (base !in taken) return base
        for (n in 2..999) if ("$base$n" !in taken) return "$base$n"
        return base + Account.generate(4)
    }

    /** True for the characters a code may hold: what `WebDavServer.start` accepts, narrowed to ASCII. */
    fun isCodeChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

    /**
     * The guard's second half, which needs the disk: the folder exists, and what it really points
     * at is not a drive root either.
     *
     * The real path is the case the lexical guard cannot see - a junction or symlink named like
     * a folder whose target is `D:\` is exactly the shape that failed in the original incident.
     */
    fun checkOnDisk(path: String): String? {
        ShareGuard.refusal(path)?.let { return it }
        val p: Path = runCatching { Paths.get(path) }.getOrNull() ?: return "Not a valid path: $path"
        if (!Files.isDirectory(p)) return "Not a folder: $path"
        val real = runCatching { p.toRealPath() }.getOrNull()
            ?: return "That folder cannot be read: $path"
        if (real.parent == null || ShareGuard.isRoot(real.toString())) {
            return "That folder points at a whole drive. Pick the folders inside it instead."
        }
        if (!Files.isReadable(real)) return "That folder cannot be read: $path"
        return null
    }

    /** Whether [path] is still there to serve. Read on every start, because drives get unplugged. */
    fun present(path: String): Boolean =
        runCatching { Files.isDirectory(Paths.get(path), LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(Paths.get(path)) }
            .getOrDefault(false)

    private fun folderName(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { "Share" }

    internal fun freeName(shares: List<Share>, wanted: String): String {
        val taken = shares.map { it.name }.toSet()
        if (wanted !in taken) return wanted
        for (n in 2..999) if ("$wanted $n" !in taken) return "$wanted $n"
        return wanted
    }

    /**
     * Path equality as the filesystem sees it, approximately.
     *
     * Case-insensitive for a drive-letter path, since NTFS is; exact elsewhere. Separators are
     * folded so `D:/Docs` and `D:\Docs\` are the same folder.
     */
    internal fun samePath(a: String, b: String): Boolean {
        val x = a.replace('\\', '/').trimEnd('/')
        val y = b.replace('\\', '/').trimEnd('/')
        val windows = x.length >= 2 && x[1] == ':'
        return if (windows) x.equals(y, ignoreCase = true) else x == y
    }

    private const val MAX_TAG = 12
}
