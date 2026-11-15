package dev.niccc2007.filet.locker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What is in the Locker: every folder and file by its path inside the Locker, where each came
 * from, and the short history of what went in and came out. Pure; the vault seals it whole.
 *
 * ## Why the names live here and not on the disk
 *
 * A file in the Locker is a blob named by a random id in Filet's private storage. Its name, its
 * folder and where it came from exist only in this index, which is sealed under the same key as
 * the files. So the private folder, read by anything with root, is a heap of random names of
 * random sizes - nothing in it says `bank statement.pdf` or `Camera/`.
 *
 * Paths are `/`-rooted strings inside the Locker: `/`, `/Trips`, `/Trips/a.jpg`.
 */
class LockerIndex {

    /**
     * One file or folder.
     *
     * @param blob the id of the sealed file on disk; empty for a folder.
     * @param origin where it was before it came in, as a full path, when it came in from outside.
     *   Only the item that was moved carries it: a file inside a folder that came in is found
     *   through the folder - see [originOf].
     */
    data class Entry(
        val path: String,
        val dir: Boolean,
        val blob: String = "",
        val size: Long = 0,
        val mtime: Long = 0,
        val added: Long = 0,
        val origin: String? = null,
    ) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** One line of the history: what, where to or from, when. */
    data class Event(val name: String, val place: String, val at: Long, val released: Boolean)

    private val entries = HashMap<String, Entry>()
    private val events = ArrayList<Event>()

    init {
        entries[ROOT] = Entry(ROOT, dir = true)
    }

    fun get(path: String): Entry? = entries[norm(path)]

    /** The folder's own entries, unsorted: the list sorts. */
    fun children(path: String): List<Entry> {
        val p = norm(path)
        val prefix = if (p == ROOT) ROOT else "$p/"
        return entries.values.filter { it.path != ROOT && it.path.startsWith(prefix) && it.path.indexOf('/', prefix.length) < 0 }
    }

    /** Puts [e] in, replacing what was at its path. Its folder must already be there. */
    fun put(e: Entry) {
        val p = norm(e.path)
        val parent = parentOf(p) ?: throw IllegalArgumentException("no parent for $p")
        require(entries[parent]?.dir == true) { "no folder at $parent" }
        entries[p] = e.copy(path = p)
    }

    /**
     * Takes [path] out, and everything inside it. Returns the blobs that are no longer referred
     * to, for the vault to delete.
     */
    fun remove(path: String): List<String> {
        val p = norm(path)
        if (p == ROOT) return emptyList()
        val gone = entries.values.filter { it.path == p || it.path.startsWith("$p/") }
        gone.forEach { entries.remove(it.path) }
        return gone.mapNotNull { it.blob.takeIf { b -> b.isNotEmpty() } }
    }

    /**
     * [from] and everything inside it now at [to]. No bytes move: a blob does not know its name.
     * The item keeps its origin, so a renamed file is still released to where it came from.
     */
    fun move(from: String, to: String): Entry {
        val f = norm(from)
        val t = norm(to)
        require(f != ROOT) { "the Locker itself cannot move" }
        require(entries[f] != null) { "nothing at $f" }
        require(entries[t] == null) { "already something at $t" }
        require(!t.startsWith("$f/")) { "a folder cannot go inside itself" }
        val parent = parentOf(t) ?: throw IllegalArgumentException("no parent for $t")
        require(entries[parent]?.dir == true) { "no folder at $parent" }
        val moving = entries.values.filter { it.path == f || it.path.startsWith("$f/") }
        moving.forEach { entries.remove(it.path) }
        for (e in moving) {
            val np = t + e.path.substring(f.length)
            entries[np] = e.copy(path = np)
        }
        return entries.getValue(t)
    }

    /**
     * Where [path] goes back to when released: its own origin, else the nearest folder above it
     * that has one, with the rest of the path below it. Null for something made in the Locker.
     */
    fun originOf(path: String): String? {
        var p = norm(path)
        var tail = ""
        while (true) {
            val e = entries[p] ?: return null
            e.origin?.let { return it + tail }
            if (p == ROOT) return null
            tail = "/" + e.name + tail
            p = parentOf(p) ?: return null
        }
    }

    /** Everything, for the sizes on the card. */
    fun files(): List<Entry> = entries.values.filter { !it.dir }

    fun bytes(): Long = entries.values.sumOf { if (it.dir) 0L else it.size }

    /** The newest first, at most [HISTORY] of them. */
    fun history(): List<Event> = events.toList()

    fun note(e: Event) {
        events.add(0, e)
        while (events.size > HISTORY) events.removeAt(events.lastIndex)
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeInt(VERSION)
            val list = entries.values.filter { it.path != ROOT }.sortedBy { it.path }
            o.writeInt(list.size)
            for (e in list) {
                o.writeUTF(e.path)
                o.writeBoolean(e.dir)
                o.writeUTF(e.blob)
                o.writeLong(e.size)
                o.writeLong(e.mtime)
                o.writeLong(e.added)
                o.writeBoolean(e.origin != null)
                if (e.origin != null) o.writeUTF(e.origin)
            }
            o.writeInt(events.size)
            for (ev in events) {
                o.writeUTF(ev.name)
                o.writeUTF(ev.place)
                o.writeLong(ev.at)
                o.writeBoolean(ev.released)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val ROOT = "/"
        const val VERSION = 1

        /** How many lines of history are kept. */
        const val HISTORY = 200

        fun decode(bytes: ByteArray): LockerIndex {
            val index = LockerIndex()
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                val v = i.readInt()
                require(v == VERSION) { "Locker index version $v" }
                repeat(i.readInt()) {
                    val path = i.readUTF()
                    val dir = i.readBoolean()
                    val blob = i.readUTF()
                    val size = i.readLong()
                    val mtime = i.readLong()
                    val added = i.readLong()
                    val origin = if (i.readBoolean()) i.readUTF() else null
                    // Sorted on the way out, so a folder always arrives before what is in it.
                    index.entries[path] = Entry(path, dir, blob, size, mtime, added, origin)
                }
                repeat(i.readInt()) {
                    index.events += Event(i.readUTF(), i.readUTF(), i.readLong(), i.readBoolean())
                }
            }
            return index
        }

        fun norm(path: String): String {
            val p = if (path.startsWith("/")) path else "/$path"
            return if (p.length > 1 && p.endsWith("/")) p.dropLast(1) else p
        }

        fun parentOf(path: String): String? {
            val p = norm(path)
            if (p == ROOT) return null
            val cut = p.lastIndexOf('/')
            return if (cut <= 0) ROOT else p.substring(0, cut)
        }

        fun childOf(parent: String, name: String): String {
            val p = norm(parent)
            return if (p == ROOT) "/$name" else "$p/$name"
        }
    }
}
