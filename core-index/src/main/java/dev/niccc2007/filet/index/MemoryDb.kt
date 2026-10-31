package dev.niccc2007.filet.index

import io.requery.android.database.sqlite.SQLiteDatabase
// R3 exception, stated: SQLite opens a database by file handle and nothing else. The folder
// comes from FiletStore (Area.MEMORY), never from a browsed location, and the handle stops here.
import java.io.File

/**
 * The [FileMemory] database, `filet-memory.db`.
 *
 * A separate file from the index on purpose. The index's opener may set its file aside and
 * start again, because a re-crawl rebuilds it; that is the one thing this file must never have
 * done to it silently, and one opener carrying both contracts is how the first derived table
 * added later would quietly inherit the wrong one.
 *
 * Opening a broken file renames it aside as `filet-memory.db.broken-<millis>` and starts a
 * new one, so the app still starts and nothing is destroyed: a later build, or a person, can
 * still read what was there. It is only deleted when it cannot even be renamed.
 */
class MemoryDb private constructor(private val db: SQLiteDatabase) : FileMemory {

    companion object {
        const val NAME = "filet-memory.db"

        fun open(dir: String): MemoryDb {
            val file = File(dir, NAME)
            file.parentFile?.mkdirs()
            val db = openOrQuarantine(file)
            runCatching { db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() } }
            createSchema(db)
            return MemoryDb(db)
        }

        private fun openOrQuarantine(file: File): SQLiteDatabase =
            runCatching { SQLiteDatabase.openOrCreateDatabase(file, null).also { probe(it) } }.getOrElse {
                val stamp = System.currentTimeMillis()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val bad = File(file.absolutePath + suffix)
                    if (!bad.isFile) continue
                    val aside = File(file.absolutePath + ".broken-" + stamp + suffix)
                    if (!runCatching { bad.renameTo(aside) }.getOrDefault(false)) runCatching { bad.delete() }
                }
                SQLiteDatabase.openOrCreateDatabase(file, null)
            }

        /** Opening a damaged file can succeed; reading from it is what fails. */
        private fun probe(db: SQLiteDatabase) {
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS item (
                  id         INTEGER PRIMARY KEY,
                  path       TEXT UNIQUE,
                  last_path  TEXT NOT NULL,
                  is_dir     INTEGER NOT NULL,
                  first_seen INTEGER NOT NULL,
                  found      INTEGER NOT NULL,
                  gone_at    INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS event (
                  id        INTEGER PRIMARY KEY,
                  item_id   INTEGER NOT NULL REFERENCES item(id),
                  at        INTEGER NOT NULL,
                  kind      TEXT NOT NULL,
                  from_path TEXT,
                  to_path   TEXT,
                  old_size  INTEGER,
                  new_size  INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS event_item ON event(item_id, at)")
        }

        private const val SEEDED = "seeded"
        private const val BASELINE = "baseline:"
    }

    private fun meta(k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putMeta(k: String, v: String) =
        db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", arrayOf<Any>(k, v))

    private fun liveId(path: String): Long? =
        db.rawQuery("SELECT id FROM item WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun event(item: Long, at: Long, kind: MemoryEventKind, from: String? = null, to: String? = null, old: Long? = null, new: Long? = null) =
        db.execSQL(
            "INSERT INTO event(item_id, at, kind, from_path, to_path, old_size, new_size) VALUES(?,?,?,?,?,?,?)",
            arrayOf(item, at, kind.key, from, to, old, new),
        )

    @Synchronized
    override fun seen(path: String, isDir: Boolean, at: Long, found: Boolean) {
        db.execSQL(
            "INSERT OR IGNORE INTO item(path, last_path, is_dir, first_seen, found) VALUES(?,?,?,?,?)",
            arrayOf<Any>(path, path, if (isDir) 1 else 0, at, if (found) 1 else 0),
        )
    }

    @Synchronized
    override fun resized(path: String, oldSize: Long, newSize: Long, at: Long) {
        if (oldSize == newSize || oldSize < 0 || newSize < 0) return
        val id = liveId(path) ?: return
        event(id, at, MemoryEventKind.RESIZED, old = oldSize, new = newSize)
    }

    @Synchronized
    override fun moved(from: String, to: String, isDir: Boolean, at: Long, certain: Boolean) {
        if (from == to) return
        val old = liveId(from)
        if (!certain) {
            // The new place keeps whatever lineage it has (the crawl's insert gave it a fresh
            // one); the old one is retired with its history intact.
            if (old != null) retire(old, from, at)
            return
        }
        // Already carried - Filet's own move re-keyed it before the crawl noticed.
        if (old == null) return
        db.beginTransaction()
        try {
            // Whatever the crawl may already have started at the destination is cleared first.
            // A lineage with no history of its own is just a fresh first look and is dropped; one
            // WITH history is a real file that was there before, retired rather than erased.
            clearDestination(to, at)
            if (isDir) {
                db.rawQuery(
                    "SELECT id, path FROM item WHERE path LIKE ? ESCAPE '\\'",
                    arrayOf(MemoryRules.likeUnder(to)),
                ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
                    .forEach { (id, p) -> dropOrRetire(id, p, at) }
            }
            db.execSQL("UPDATE item SET path = ?, last_path = ? WHERE id = ?", arrayOf<Any>(to, to, old))
            if (isDir) {
                // Children ride along: the folder moved, so did everything in it, and none of
                // them was renamed. Their own histories are untouched; their paths follow.
                val cut = from.trimEnd('/').length
                db.execSQL(
                    "UPDATE item SET path = ? || substr(path, ?), last_path = ? || substr(path, ?) " +
                        "WHERE path LIKE ? ESCAPE '\\'",
                    arrayOf<Any>(to.trimEnd('/'), cut + 1, to.trimEnd('/'), cut + 1, MemoryRules.likeUnder(from)),
                )
            }
            event(old, at, MemoryRules.kindOf(from, to), from = from, to = to)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun clearDestination(to: String, at: Long) {
        val id = liveId(to) ?: return
        dropOrRetire(id, to, at)
    }

    private fun dropOrRetire(id: Long, path: String, at: Long) {
        val hasHistory = db.rawQuery("SELECT 1 FROM event WHERE item_id = ? LIMIT 1", arrayOf(id.toString()))
            .use { it.moveToFirst() }
        if (hasHistory) retire(id, path, at) else db.execSQL("DELETE FROM item WHERE id = ?", arrayOf<Any>(id))
    }

    private fun retire(id: Long, path: String, at: Long) {
        db.execSQL("UPDATE item SET path = NULL, last_path = ?, gone_at = ? WHERE id = ?", arrayOf<Any>(path, at, id))
        event(id, at, MemoryEventKind.GONE, from = path)
    }

    @Synchronized
    override fun gone(path: String, at: Long) {
        val id = liveId(path) ?: return
        retire(id, path, at)
    }

    override fun baselined(root: String): Boolean = meta(BASELINE + root) != null

    @Synchronized
    override fun markBaselined(root: String, at: Long) {
        if (!baselined(root)) putMeta(BASELINE + root, at.toString())
    }

    override fun seeded(): Boolean = meta(SEEDED) != null

    @Synchronized
    override fun seed(paths: Sequence<Pair<String, Boolean>>, at: Long) {
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO item(path, last_path, is_dir, first_seen, found) VALUES(?,?,?,?,1)",
            )
            for ((p, dir) in paths) {
                st.bindString(1, p); st.bindString(2, p); st.bindLong(3, if (dir) 1 else 0); st.bindLong(4, at)
                st.executeInsert()
                st.clearBindings()
            }
            putMeta(SEEDED, at.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun of(path: String): ItemMemory? {
        val row = db.rawQuery("SELECT id, first_seen, found, is_dir FROM item WHERE path = ?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else null
        } ?: return null
        val events = db.rawQuery(
            "SELECT at, kind, from_path, to_path, old_size, new_size FROM event WHERE item_id = ? ORDER BY at DESC, id DESC LIMIT 200",
            arrayOf(row[0].toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = MemoryEventKind.of(c.getString(1)) ?: continue
                    add(
                        MemoryEvent(
                            at = c.getLong(0), kind = kind,
                            fromPath = c.getString(2), toPath = c.getString(3),
                            oldSize = if (c.isNull(4)) null else c.getLong(4),
                            newSize = if (c.isNull(5)) null else c.getLong(5),
                        )
                    )
                }
            }
        }
        return ItemMemory(firstSeen = row[1], found = row[2] != 0L, isDir = row[3] != 0L, events = events)
    }

    /**
     * A range over the path's unique index rather than a LIKE, which SQLite will not serve from
     * an index: `dir/` up to but not including `dir0`, '0' being the character after '/'.
     */
    override fun firstSeen(paths: List<String>): Map<String, Long> {
        val out = HashMap<String, Long>(paths.size)
        // 500 a statement keeps well under SQLite's bound-parameter limit on every build.
        for (chunk in paths.distinct().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT path, first_seen FROM item WHERE path IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
            }
        }
        return out
    }

    override fun earliestUnder(dir: String): Pair<Long, Boolean>? {
        val base = dir.trimEnd('/')
        return db.rawQuery(
            "SELECT first_seen, found FROM item WHERE path >= ? AND path < ? ORDER BY first_seen ASC LIMIT 1",
            arrayOf("$base/", "${base}0"),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to (c.getInt(1) != 0) else null }
    }

    @Synchronized
    override fun <T> batch(block: () -> T): T {
        db.beginTransaction()
        try {
            val out = block()
            db.setTransactionSuccessful()
            return out
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()
}
