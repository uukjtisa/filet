package dev.niccc2007.filet.log

/**
 * Timed spans that nest, written as a tree when the outermost one ends.
 *
 * A slow launch is slow somewhere specific. Separate lines per span say what each took but not
 * which contained which, so a 900ms "graph build" and a 700ms "vfs.roots" inside it read as
 * 1.6 seconds of work. As a tree it is one number with its parts underneath:
 * ```
 * launch/onFirstScreen 1204ms
 *   vfs.roots 702ms
 *   home.refresh 311ms
 *     index.query 280ms
 * ```
 *
 * ## Nesting across threads
 *
 * The parent is whatever span is open on the starting thread. A span that suspends may end on
 * another thread; ending only resets the thread's current span when it is still this one, and a
 * span whose parent has already ended is treated as a root. The worst case is therefore a span
 * printed on its own line instead of inside its tree - never a wrong number, never a leak.
 */
class SpanNode(val tag: String, val what: String, val parent: SpanNode?) {
    val began: Long = System.nanoTime()

    /** Milliseconds, or -1 while running. */
    @Volatile
    var ms: Long = -1

    @Volatile
    var ended: Boolean = false

    val children = ArrayList<SpanNode>()
}

object Spans {

    private val current = ThreadLocal<SpanNode?>()

    fun begin(tag: String, what: String): SpanNode {
        val p = current.get()?.takeIf { !it.ended }
        val n = SpanNode(tag, what, p)
        current.set(n)
        return n
    }

    /**
     * End [n]. Returns the node to write - itself when it is a root or its parent has already
     * ended - or null when it was folded into a parent still running.
     */
    fun end(n: SpanNode): SpanNode? {
        n.ms = (System.nanoTime() - n.began) / 1_000_000
        n.ended = true
        if (current.get() === n) current.set(n.parent?.takeIf { !it.ended })
        val p = n.parent
        if (p != null && !p.ended) {
            synchronized(p.children) { p.children += n }
            return null
        }
        return n
    }

    /** The tree under [root], one line per node, children indented two spaces per level. */
    fun render(root: SpanNode): String = buildString { walk(root, 0, this) }

    private fun walk(n: SpanNode, depth: Int, out: StringBuilder) {
        repeat(depth) { out.append("  ") }
        if (depth == 0) out.append(n.tag).append('/')
        out.append(n.what).append(' ').append(n.ms).append("ms")
        if (depth == 0 && n.parent != null) out.append(" (finished after its parent)")
        out.append('\n')
        val kids = synchronized(n.children) { n.children.toList() }
        for (k in kids.sortedBy { it.began }) walk(k, depth + 1, out)
    }
}
