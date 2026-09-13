package dev.niccc2007.filet.index

import android.content.Context
import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
// R3 exception, stated: SQLite opens a database by file handle and nothing else. This is the
// index's own private file under the app's data directory - never a user path.
import java.io.File

/**
 * The index database: schema, pragmas, and the one place that knows which SQLite this is.
 *
 * **Bundled SQLite, not the system one** (SEARCH.md §2.1). FTS5 is not guaranteed on every
 * OEM's build and you find that out from a crash report on a device you do not own; the
 * `trigram` tokenizer additionally needs SQLite >= 3.34, which the platform library only
 * reaches around API 33 against a floor of API 26. The bundled build makes every device
 * behave the same, which is the same argument that picks ARSCLib over a native aapt2.
 *
 * Even so, [hasFts] is probed rather than assumed: a build flag can change under us, and the
 * retrieve-then-rerank design degrades to a prefix scan without losing a single result -
 * only speed. An index that throws on an unexpected device is worse than a slower one.
 */
class IndexDb private constructor(val db: SQLiteDatabase, val hasFts: Boolean) {

    companion object {
        const val NAME = "filet-index.db"
        const val VERSION = 1

        fun open(context: Context): IndexDb {
            val file = File(context.getDatabasePath(NAME).absolutePath)
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            // Every pragma goes through rawQuery, not execSQL. Several of them - journal_mode
            // and mmap_size among them - RETURN a row with the value they settled on, and
            // execSQL refuses any statement that produces data. Using execSQL here is what
            // makes the whole index fail to open, on every device.
            pragma(db, "PRAGMA journal_mode = WAL")
            pragma(db, "PRAGMA synchronous = NORMAL")
            pragma(db, "PRAGMA temp_store = MEMORY")
            // 256 MB of mmap: the index is read far more than it is written, and this is
            // what turns a candidate query into a memory read instead of a syscall.
            pragma(db, "PRAGMA mmap_size = 268435456")
            pragma(db, "PRAGMA foreign_keys = ON")

            val fts = probeFts(db)
            createSchema(db, fts)
            return IndexDb(db, fts)
        }

        /** Run a pragma and consume whatever row it produces. */
        private fun pragma(db: SQLiteDatabase, sql: String) {
            runCatching { db.rawQuery(sql, null).use { it.moveToFirst() } }
        }

        /**
         * Build a throwaway FTS5 trigram table. The only honest probe is to try it: a
         * compile-option string can claim FTS5 while the tokenizer is missing.
         */
        private fun probeFts(db: SQLiteDatabase): Boolean = try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts_probe USING fts5(x, tokenize=\"trigram case_sensitive 0\")")
            db.execSQL("DROP TABLE IF EXISTS fts_probe")
            true
        } catch (e: Throwable) {
            false
        }

        private fun createSchema(db: SQLiteDatabase, fts: Boolean) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS volume (
                  id      INTEGER PRIMARY KEY,
                  kind    TEXT,
                  uuid    TEXT UNIQUE,
                  label   TEXT,
                  root    TEXT,
                  mounted INTEGER DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS node (
                  id        INTEGER PRIMARY KEY,
                  vol_id    INTEGER NOT NULL REFERENCES volume(id) ON DELETE CASCADE,
                  parent_id INTEGER,
                  name      TEXT    NOT NULL,
                  name_fold TEXT    NOT NULL,
                  is_dir    INTEGER NOT NULL,
                  size      INTEGER,
                  mtime     INTEGER,
                  dir_mtime INTEGER,
                  flags     INTEGER DEFAULT 0,
                  gen       INTEGER NOT NULL,
                  inode     INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
            // parent_id, never a stored path: moving a folder of 3,000 photos is one row
            // update instead of rewriting every descendant.
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS node_uq ON node(vol_id, parent_id, name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS node_par ON node(parent_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS node_gen ON node(gen)")
            db.execSQL("CREATE INDEX IF NOT EXISTS node_pfx ON node(name_fold COLLATE BINARY)")

            // Additive migration. The table above is created with IF NOT EXISTS, so a
            // database written by an earlier build keeps its old shape and never gains a new
            // column. ADD COLUMN is the one migration SQLite does cheaply and in place; a
            // duplicate-column error here just means it is already present.
            runCatching { db.execSQL("ALTER TABLE node ADD COLUMN inode INTEGER DEFAULT 0") }
            // The two ways a moved file is recognised: by inode where the volume has one,
            // and by an exact (name, size) signature where it does not.
            db.execSQL("CREATE INDEX IF NOT EXISTS node_ino ON node(vol_id, inode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS node_sig ON node(vol_id, name, size)")
            db.execSQL("CREATE INDEX IF NOT EXISTS node_size ON node(size) WHERE is_dir = 0")

            db.execSQL(
                """
                -- SEARCH.md calls this table `inner`; SQLite reserves INNER (as in INNER
                -- JOIN), so the real name carries a suffix rather than quotes at every
                -- call site. Same columns, same purpose.
                CREATE TABLE IF NOT EXISTS inner_fact (
                  rowid   INTEGER PRIMARY KEY,
                  node_id INTEGER NOT NULL REFERENCES node(id) ON DELETE CASCADE,
                  kind    TEXT NOT NULL,
                  value   TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS inner_node ON inner_fact(node_id)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS usage (
                  node_id INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE,
                  opens   INTEGER DEFAULT 0,
                  last_at INTEGER,
                  pinned  INTEGER DEFAULT 0
                )
                """.trimIndent()
            )

            // Provenance: where a file came from. Android has no mark-of-the-web and no
            // kMDItemWhereFroms, so this table is the only memory a file has (PLAN.md §5.1).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS provenance (
                  node_id    INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE,
                  source_app TEXT,
                  origin     TEXT,
                  page_title TEXT,
                  uploader   TEXT,
                  fmt        TEXT,
                  at         INTEGER,
                  extra      TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS prov_origin ON provenance(origin)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS saved_search (
                  id INTEGER PRIMARY KEY, name TEXT, query TEXT, icon TEXT, sort INTEGER
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS meta (
                  k TEXT PRIMARY KEY, v TEXT
                )
                """.trimIndent()
            )

            if (fts) {
                // external content: names are stored once, in node
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS node_fts USING fts5(
                      name_fold, content='node', content_rowid='id',
                      tokenize="trigram case_sensitive 0"
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS inner_fts USING fts5(
                      value, content='inner_fact', content_rowid='rowid',
                      tokenize="trigram case_sensitive 0"
                    )
                    """.trimIndent()
                )
                // Triggers keep the external-content tables in step. Doing it by hand at
                // every call site is how an FTS index silently drifts from its content table.
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS node_ai AFTER INSERT ON node BEGIN
                      INSERT INTO node_fts(rowid, name_fold) VALUES (new.id, new.name_fold);
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS node_ad AFTER DELETE ON node BEGIN
                      INSERT INTO node_fts(node_fts, rowid, name_fold) VALUES('delete', old.id, old.name_fold);
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS node_au AFTER UPDATE ON node BEGIN
                      INSERT INTO node_fts(node_fts, rowid, name_fold) VALUES('delete', old.id, old.name_fold);
                      INSERT INTO node_fts(rowid, name_fold) VALUES (new.id, new.name_fold);
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS inner_ai AFTER INSERT ON inner_fact BEGIN
                      INSERT INTO inner_fts(rowid, value) VALUES (new.rowid, new.value);
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS inner_ad AFTER DELETE ON inner_fact BEGIN
                      INSERT INTO inner_fts(inner_fts, rowid, value) VALUES('delete', old.rowid, old.value);
                    END
                    """.trimIndent()
                )
            }
        }
    }

    fun meta(key: String, def: String? = null): String? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else def
        }

    fun putMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", arrayOf<Any>(key, value))
    }

    fun close() = db.close()

    /** Drop every row but keep the schema: a rebuild, not a reinstall. */
    fun wipe() {
        db.execSQL("DELETE FROM node")
        db.execSQL("DELETE FROM volume")
        db.execSQL("DELETE FROM inner_fact")
        db.execSQL("DELETE FROM meta")
        if (hasFts) {
            db.execSQL("INSERT INTO node_fts(node_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO inner_fts(inner_fts) VALUES('rebuild')")
        }
        db.execSQL("VACUUM")
    }
}

internal inline fun <T> Cursor.map(block: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count.coerceAtMost(1024))
    use { while (it.moveToNext()) out += block(it) }
    return out
}
