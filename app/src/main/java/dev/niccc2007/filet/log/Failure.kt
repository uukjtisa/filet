package dev.niccc2007.filet.log

import java.util.Collections
import java.util.IdentityHashMap

/**
 * A failure as the chain of things that went wrong, one line each, ending on where it started.
 *
 * Bug identified here: the logger used to append `Log.getStackTraceString` after a failure - for
 * an error under Compose that is ninety frames of framework, and the one line naming the file
 * that failed is somewhere in the middle. A wrapped error was worse: "could not stage" on top,
 * the socket reset that caused it four screens down. The pattern copied is `RemoteProbe`'s,
 * which walks the cause chain because every provider wraps; here every link is kept rather than
 * classified.
 *
 * What is written, for `IOException("stage") <- SocketException("reset")`:
 * ```
 *   failure   IOException: stage @ RemoteStaging.kt:80 RemoteStaging.stage
 *   caused by java.net.SocketException: reset @ SmbFile.java:210 com.x.SmbFile.read
 *   trail     (root cause, this app's frames, innermost first)
 *             RemoteStaging.kt:80 RemoteStaging.stage
 *             BrowserViewModel.kt:960 BrowserViewModel.openArchive
 * ```
 * The site on each link is that throwable's first frame in this app, else its first frame at
 * all. The trail is the root cause's own frames in this app - the path through our code to the
 * line that failed - capped at [TRAIL], so framework and library frames never bury it.
 */
object Failure {

    /** One link of a cause chain. [site] is null when the throwable has no stack. */
    data class Link(val type: String, val message: String?, val site: String?)

    /** Links walked before giving up. A real chain is three or four deep. */
    const val DEPTH = 12

    /** App frames listed in the trail. */
    const val TRAIL = 8

    const val APP = "dev.niccc2007.filet."

    /** The cause chain, outermost first, with cycles cut and depth capped. */
    fun chain(t: Throwable): List<Link> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val out = ArrayList<Link>()
        var at: Throwable? = t
        while (at != null && out.size < DEPTH && seen.add(at)) {
            out += Link(typeName(at), at.message?.takeIf { it.isNotBlank() }, site(at))
            at = at.cause
        }
        return out
    }

    /** The deepest cause reachable, which is where the failure actually began. */
    fun root(t: Throwable): Throwable {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var at = t
        while (seen.add(at) && seen.size < DEPTH) at = at.cause ?: break
        return at
    }

    /** `File.kt:123 Class.method` for the first app frame, else the first frame, else null. */
    fun site(t: Throwable): String? {
        val frames = t.stackTrace ?: return null
        val f = frames.firstOrNull { isApp(it) } ?: frames.firstOrNull() ?: return null
        return frame(f)
    }

    /** The root cause's frames in this app, innermost first. */
    fun trail(t: Throwable): List<String> =
        (root(t).stackTrace ?: emptyArray()).asSequence().filter { isApp(it) }.take(TRAIL).map { frame(it) }.toList()

    /** The whole failure as indented lines, ready to follow the message line. */
    fun render(t: Throwable): String = buildString {
        val links = chain(t)
        links.forEachIndexed { i, link ->
            append(if (i == 0) "  failure   " else "  caused by ")
            append(link.type).append(": ").append(link.message ?: "(no message)")
            if (link.site != null) append(" @ ").append(link.site)
            append('\n')
        }
        if (links.size == DEPTH) append("  (chain cut at $DEPTH links)\n")
        t.suppressed.take(3).forEach { s ->
            append("  suppressed ").append(typeName(s)).append(": ").append(s.message ?: "(no message)").append('\n')
        }
        val trail = trail(t)
        if (trail.isNotEmpty()) {
            append("  trail     (root cause, this app's frames, innermost first)\n")
            trail.forEach { append("            ").append(it).append('\n') }
        }
    }

    /**
     * The JDK, Kotlin and Android by simple name, everything else in full - `SocketException`
     * is unambiguous, `SMBRuntimeException` needs its package to say which library threw it.
     */
    fun typeName(t: Throwable): String {
        val n = t.javaClass.name
        return if (n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("android.") ||
            n.startsWith(APP)
        ) n.substringAfterLast('.') else n
    }

    // The logger's own frames count as the app's: if one is in a failure's chain, the logger
    // is part of what failed.
    private fun isApp(f: StackTraceElement) = f.className.startsWith(APP)

    private fun frame(f: StackTraceElement): String {
        val cls = f.className.substringAfterLast('.')
        val where = if (f.fileName != null && f.lineNumber >= 0) f.fileName + ":" + f.lineNumber else (f.fileName ?: "?")
        return "$where $cls.${f.methodName}"
    }
}
