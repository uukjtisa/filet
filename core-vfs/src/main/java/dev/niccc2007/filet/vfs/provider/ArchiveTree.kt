package dev.niccc2007.filet.vfs.provider

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * An archive's members as a tree: each folder's direct children, found by the folder's path
 * rather than by scanning every member. Pure.
 *
 * A listing used to be a pass over the whole table of contents, keeping the members under the
 * folder asked for. That is one pass per folder opened, and a list row that counts what a
 * sub-folder holds asks for one listing per row - so a zip of fifty thousand members was walked
 * fifty thousand members at a time, once for the folder and once again for every folder in it.
 *
 * The rules are the ones that pass had, kept exactly:
 * - a folder that only appears inside member names ("a/b/c.txt" makes "a" and "a/b") is listed
 *   all the same - most zips store no folder entries, most tars do, and a listing built from
 *   real entries alone would lose whole subtrees of the one and duplicate them in the other;
 * - such a folder takes the time of the first member that showed it;
 * - a file replaces a folder of the same name listed before it, and keeps its place;
 * - [member] is the FIRST member stored under a name, as a search through the list would find.
 */
internal class ArchiveTree(val members: List<Member>) {

    /** One entry of a folder's listing. */
    class Child(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private val folders = HashMap<String, LinkedHashMap<String, Child>>()
    private val byName = HashMap<String, Member>()

    init {
        for (m in members) {
            if (m.name.isEmpty()) continue
            byName.putIfAbsent(m.name, m)
            val parts = m.name.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var folder = ""
            for ((k, part) in parts.withIndex()) {
                val here = folders.getOrPut(folder) { LinkedHashMap() }
                val last = k == parts.lastIndex
                when {
                    !last -> here.putIfAbsent(part, Child(part, true, -1L, m.mtime))
                    m.isDir -> here.putIfAbsent(part, Child(part, true, -1L, m.mtime))
                    else -> here[part] = Child(part, false, m.size, m.mtime)
                }
                folder = if (folder.isEmpty()) part else "$folder/$part"
            }
        }
    }

    /** What is directly inside [folder] ("" for the top), in the order the archive stores it. */
    fun children(folder: String): List<Child> = folders[folder.trim('/')]?.values?.toList().orEmpty()

    /** The member stored under exactly [name], if there is one. */
    fun member(name: String): Member? = byName[name]

    /** Something is stored under [folder], so it is a folder whether or not it has an entry. */
    fun isFolder(folder: String): Boolean = folders[folder.trim('/')]?.isNotEmpty() == true
}

/**
 * Each archive's [ArchiveTree], read once and kept while the file is the same file.
 *
 * Bug identified here: every listing inside an archive opened the archive and read its whole
 * table of contents again - every folder tapped, every stat, every sub-folder count on a row.
 * A zip's table is the cheap case and still costs a parse per tap on a big archive; a tar.gz
 * has no table at all and was decompressed end to end to list one folder. Browsing a big
 * archive was a spinner on every folder, where a reader that keeps what it read once walks the
 * folders instantly.
 *
 * Kept by path, length and modification time, so an archive changed by anything else is read
 * again; Filet's own writes into one [forget] it outright, since a rewrite inside the same
 * millisecond with the same length would otherwise look unchanged. Held to a total number of
 * members rather than of archives - one archive of two hundred thousand members weighs what a
 * hundred small ones do - and the newest one is always kept, whatever its size.
 */
internal class ArchiveListings(private val maxMembers: Int = 250_000) {

    private val kept = object : LinkedHashMap<String, ArchiveTree>(8, 0.75f, true) {}
    private val loading = ConcurrentHashMap<String, Any>()

    /** The tree for [host], from [read] when it is not kept or the file has changed since. */
    fun of(host: File, read: () -> List<Member>): ArchiveTree {
        val key = stampOf(host) ?: return ArchiveTree(read())
        synchronized(kept) { kept[key] }?.let { return it }
        // One read per archive at a time: a folder and its rows' counts arrive together, and
        // each of them reading the same table at once is the cost being removed.
        val lock = loading.computeIfAbsent(key) { Any() }
        try {
            synchronized(lock) {
                synchronized(kept) { kept[key] }?.let { return it }
                val tree = ArchiveTree(read())
                synchronized(kept) {
                    kept.keys.removeAll { it.startsWith(prefixOf(host)) }
                    kept[key] = tree
                    trim()
                }
                return tree
            }
        } finally {
            loading.remove(key)
        }
    }

    /** [host] was written by Filet: whatever was kept for it is out of date. */
    fun forget(host: File) {
        synchronized(kept) { kept.keys.removeAll { it.startsWith(prefixOf(host)) } }
    }

    private fun trim() {
        var total = kept.values.sumOf { it.members.size }
        // Through the entries, eldest first: a `get` on an access-ordered map moves what it
        // reads, which part way through this walk would be a ConcurrentModificationException.
        val order = kept.entries.iterator()
        while (total > maxMembers && kept.size > 1 && order.hasNext()) {
            total -= order.next().value.members.size
            order.remove()
        }
    }

    private fun prefixOf(host: File) = host.absolutePath + "|"

    /** Null when the file is not there to stamp, and then nothing is kept for it. */
    private fun stampOf(host: File): String? =
        if (host.isFile) prefixOf(host) + host.length() + "|" + host.lastModified() else null
}
