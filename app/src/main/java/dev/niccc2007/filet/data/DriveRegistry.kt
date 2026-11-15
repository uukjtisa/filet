package dev.niccc2007.filet.data

/**
 * Every SD card and USB drive this phone has seen, by volume ID.
 *
 * What makes a drive the same drive when it comes back: its name, its place on Home, whether it
 * is hidden and its index are all keyed by its mount path, `/storage/<volume ID>`, and the volume
 * ID is written into the drive itself. So nothing has to be written onto somebody's drive to know
 * it again, and two drives are never confused for each other - a drive with no ID is shown while
 * it is plugged in and never remembered.
 *
 * This is the record behind the "not plugged in" line: what is remembered, when it was last seen,
 * and how big it was. Pure, and stored as plain lines, because `org.json` is a stub in the unit
 * tests and the decisions here are the ones worth testing.
 */
object DriveRegistry {

    enum class Kind { SD, USB }

    /**
     * One remembered drive.
     *
     * @param indexed for a USB drive, whether its files were asked to be indexed. An SD card is
     *   indexed like the phone's own storage and ignores this.
     * @param present whether it was plugged in at the last look. What tells a drive plugged in
     *   while Filet was closed from one that never left, at the next launch.
     */
    data class Known(
        val uuid: String,
        val kind: Kind,
        val name: String,
        val path: String,
        val total: Long?,
        val firstSeen: Long,
        val lastSeen: Long,
        val indexed: Boolean = false,
        val present: Boolean = true,
    )

    /** A drive plugged in now, as the registry needs it. */
    data class Seen(val uuid: String, val kind: Kind, val name: String, val path: String, val total: Long?)

    /**
     * [known] after seeing [plugged] at [now]: new drives added, the ones present stamped and their
     * name, path and size brought up to date, the rest marked absent. A size that could not be
     * read keeps the last one.
     */
    fun seen(known: List<Known>, plugged: List<Seen>, now: Long): List<Known> {
        val byId = known.associateBy { it.uuid }.mapValues { it.value.copy(present = false) }.toMutableMap()
        for (p in plugged) {
            val before = byId[p.uuid]
            byId[p.uuid] = before?.copy(
                kind = p.kind,
                name = p.name.ifEmpty { before.name },
                path = p.path,
                total = p.total ?: before.total,
                lastSeen = now,
                present = true,
            ) ?: Known(p.uuid, p.kind, p.name, p.path, p.total, now, now)
        }
        // Order kept: the drives already known in their order, then the new ones.
        return known.map { byId.getValue(it.uuid) } + plugged.map { it.uuid }.distinct()
            .filter { id -> known.none { it.uuid == id } }
            .map { byId.getValue(it) }
    }

    /** The remembered drives not plugged in now, most recently seen first. */
    fun gone(known: List<Known>, plugged: Set<String>): List<Known> =
        known.filter { it.uuid !in plugged }.sortedByDescending { it.lastSeen }

    /**
     * The volume IDs in [plugged] that were not present at the last look - the drives just plugged
     * in, whether that happened a moment ago or while Filet was closed.
     */
    fun arrived(known: List<Known>, plugged: Set<String>): Set<String> =
        plugged.filterTo(LinkedHashSet()) { id -> known.none { it.uuid == id && it.present } }

    fun forget(known: List<Known>, uuid: String): List<Known> = known.filterNot { it.uuid == uuid }

    /**
     * What a drive's card is called before anyone renames it, from what the system calls it.
     *
     * The system's own words when they already say what the thing is ("SanDisk SD card", "USB
     * drive"), the kind in front when they are only a brand ("SanDisk" becomes "SD · SanDisk"),
     * and the kind alone when there are none. Never the volume ID, which is what the card showed
     * before and which reads as a code rather than a drive.
     */
    fun displayName(kind: Kind, description: String): String {
        val d = description.trim()
        val generic = if (kind == Kind.SD) "SD card" else "USB drive"
        if (d.isEmpty()) return generic
        val says = Regex("\\b(sd|usb|card|drive|storage|stick|otg)\\b", RegexOption.IGNORE_CASE).containsMatchIn(d)
        return if (says) d else (if (kind == Kind.SD) "SD" else "USB") + " · " + d
    }

    fun setIndexed(known: List<Known>, uuid: String, on: Boolean): List<Known> =
        known.map { if (it.uuid == uuid) it.copy(indexed = on) else it }

    /**
     * The USB drives a crawl must leave alone: every one not asked to be indexed, by mount path.
     * An SD card is never in it.
     */
    fun unindexedUsb(known: List<Known>): Set<String> =
        known.filter { it.kind == Kind.USB && !it.indexed }.map { it.path }.toSet()

    // ── stored form ──

    /** One line per drive, fields separated by tabs, a tab or newline in a name escaped. */
    fun encode(known: List<Known>): String = known.joinToString("\n") { k ->
        listOf(
            k.uuid, k.kind.name, esc(k.name), esc(k.path), k.total?.toString().orEmpty(),
            k.firstSeen.toString(), k.lastSeen.toString(), if (k.indexed) "1" else "0",
            if (k.present) "1" else "0",
        ).joinToString("\t")
    }

    /** What [encode] wrote. A line that cannot be read is dropped rather than failing the rest. */
    fun decode(text: String?): List<Known> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            runCatching {
                Known(
                    uuid = f[0].ifEmpty { return@mapNotNull null },
                    kind = Kind.valueOf(f[1]),
                    name = unesc(f[2]),
                    path = unesc(f[3]),
                    total = f[4].toLongOrNull(),
                    firstSeen = f[5].toLong(),
                    lastSeen = f[6].toLong(),
                    indexed = f.getOrNull(7) == "1",
                    present = f.getOrNull(8) != "0",
                )
            }.getOrNull()
        }.distinctBy { it.uuid }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
