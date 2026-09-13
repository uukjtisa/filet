package dev.niccc2007.filet.index

import android.content.Context
import android.database.Cursor
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The index, on bundled SQLite.
 *
 * Everything here runs on one IO dispatcher behind one mutex. SQLite would tolerate more
 * concurrency, but a crawl and a keystroke competing for the same write lock is how a search
 * box starts stuttering, and the crawl is the only writer that matters.
 */
class SqliteIndex(
    context: Context,
    private val vfs: Vfs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * What can look inside a file (FEATURES.md F59, F60).
     *
     * Empty means `pkg:` `class:` `perm:` `label:` `inzip:` and `member:` find nothing -
     * which [IndexStatus.containerFacts] reports, so the search UI can decline to offer a
     * field that cannot answer rather than returning an empty list and looking broken (R1).
     */
    private val extractors: List<FactExtractor> = emptyList(),
) : FileIndex {

    /** Extension -> the extractors that claim it, resolved once. */
    private val extractorsByExt: Map<String, List<FactExtractor>> =
        extractors.flatMap { e -> e.extensions.map { it.lowercase() to e } }
            .groupBy({ it.first }, { it.second })

    private val dbh = IndexDb.open(context)
    private val db get() = dbh.db
    private val lock = Mutex()

    private val _status = MutableStateFlow(
        IndexStatus(
            available = countFiles() > 0,
            files = countFiles(),
            lastRunAt = dbh.meta(META_LAST_RUN)?.toLongOrNull() ?: 0L,
            ftsAccelerated = dbh.hasFts,
            containerFacts = extractors.isNotEmpty(),
        )
    )
    override val status: StateFlow<IndexStatus> = _status.asStateFlow()

    // ────────────────────────── retrieval ──────────────────────────

    override suspend fun candidates(req: SearchRequest, parsed: ParsedQuery): List<IndexRow> =
        withContext(io) {
            val free = Fuzzy.fold(parsed.free).trim()
            val where = StringBuilder()
            val args = ArrayList<Any>()

            // Scope first: it is the cheapest and most selective predicate there is.
            val scopeSql = scopeClause(req, args)
            if (scopeSql != null) where.append(scopeSql)

            // SEARCH.md §2.3 - trigram cannot accelerate a query under 3 characters, so a
            // short one takes the prefix index instead. Both feed the same reranker, and the
            // user never sees the seam.
            //
            // The free text is split into words and each word becomes its own constraint,
            // ANDed. Matching the whole string as one FTS phrase looks equivalent and is not:
            // a phrase is a literal substring, so `quarterly recon` would miss
            // `quarterly-reconciliation-2026.xlsx` - both words are there, the space is not.
            // SQL's job here is only to narrow honestly; deciding which of the survivors is
            // the best answer is the reranker's.
            var ftsJoin = ""
            val words = free.split(' ', '\t').filter { it.isNotEmpty() }
            val longWords = words.filter { it.length >= 3 }
            val shortWords = words.filter { it.length < 3 }

            if (dbh.hasFts && longWords.isNotEmpty()) {
                ftsJoin = " JOIN node_fts f ON f.rowid = n.id"
                if (where.isNotEmpty()) where.append(" AND ")
                where.append("node_fts MATCH ?")
                args += longWords.joinToString(" AND ") { quoteFts(it) }
            } else {
                for (w in longWords) {
                    if (where.isNotEmpty()) where.append(" AND ")
                    where.append("n.name_fold LIKE ?")
                    args += "%$w%"
                }
            }

            for (w in shortWords) {
                if (where.isNotEmpty()) where.append(" AND ")
                if (words.size == 1) {
                    // The whole query is one or two characters - the "just started typing"
                    // case. A prefix range uses node_pfx; LIKE '%x%' would scan the table.
                    where.append("(n.name_fold >= ? AND n.name_fold < ?)")
                    args += w
                    args += w + "￿"
                } else {
                    where.append("n.name_fold LIKE ?")
                    args += "%$w%"
                }
            }

            for (clause in sqlPredicates(parsed.root, args)) {
                if (where.isNotEmpty()) where.append(" AND ")
                where.append(clause)
            }

            // `dup:` is not a predicate on a row, it is a predicate on a SET of rows, so it
            // runs its own pass and then narrows by id. Run last, because everything above has
            // already made the set it has to hash much smaller.
            if (hasDup(parsed.root)) {
                val ids = duplicateIds(req)
                if (ids.isEmpty()) return@withContext emptyList()
                if (where.isNotEmpty()) where.append(" AND ")
                where.append("n.id IN (").append(ids.joinToString(",")).append(")")
            }

            val sql = buildString {
                append("SELECT n.id, n.name, n.name_fold, n.is_dir, n.size, n.mtime, n.parent_id, n.vol_id, ")
                append("p.origin, IFNULL(u.opens,0), IFNULL(u.last_at,0), IFNULL(u.pinned,0) ")
                append("FROM node n")
                append(ftsJoin)
                append(" LEFT JOIN provenance p ON p.node_id = n.id")
                append(" LEFT JOIN usage u ON u.node_id = n.id")
                if (where.isNotEmpty()) append(" WHERE ").append(where)
                append(" LIMIT ").append(req.limit)
            }
            runCatching { db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).map(::readRow) }
                .getOrElse { emptyList() }
        }

    /**
     * @return a SQL fragment limiting the search to the requested scope, or null for none.
     *
     * The subtree case is a recursive CTE walking DOWN from the scope directory. Walking up
     * from every candidate instead would be 500 round trips through `parent_id`.
     */
    private fun scopeClause(req: SearchRequest, args: MutableList<Any>): String? {
        val origin = req.origin ?: return null
        return when (req.scope) {
            SearchScope.FOLDER -> {
                val id = nodeIdFor(origin) ?: return "1 = 0"
                args += id
                "n.parent_id = ?"
            }
            SearchScope.SUBFOLDERS -> {
                val id = nodeIdFor(origin) ?: return "1 = 0"
                args += id
                "n.parent_id IN (WITH RECURSIVE sub(id) AS (" +
                    "SELECT ? UNION ALL SELECT c.id FROM node c JOIN sub ON c.parent_id = sub.id WHERE c.is_dir = 1" +
                    ") SELECT id FROM sub)"
            }
            SearchScope.DEVICE -> null
            SearchScope.PROVENANCE -> "p.origin IS NOT NULL"
        }
    }

    /** The half of the query that SQL can answer before retrieval, so it costs nothing. */
    private fun sqlPredicates(node: Node, args: MutableList<Any>): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Node, negated: Boolean) {
            when (n) {
                is Node.And -> n.parts.forEach { walk(it, negated) }
                // OR and NOT are left entirely to the post-filter: pushing half of a
                // disjunction into SQL would silently drop rows the other half wanted.
                is Node.Or -> Unit
                is Node.Not -> Unit
                is Node.Text, Node.Everything -> Unit
                is Node.Field -> if (!negated) pushField(n, out, args)
            }
        }
        walk(node, false)
        return out
    }

    private fun pushField(n: Node.Field, out: MutableList<String>, args: MutableList<Any>) {
        when (n.field) {
            QField.EXT -> {
                out += "n.name_fold LIKE ?"
                args += "%." + n.value.removePrefix(".").lowercase()
            }
            QField.SIZE -> {
                val v = QueryEval.parseSize(n.value) ?: return
                out += "n.size ${cmpSql(n.op)} ?"
                args += v
            }
            QField.MODIFIED, QField.CREATED -> {
                val cutoff = QueryEval.parseTime(n.value, System.currentTimeMillis()) ?: return
                // `modified:<7d` means "within the last 7 days", which inverts the operator.
                out += if (n.op == Cmp.GT || n.op == Cmp.GTE) "n.mtime < ?" else "n.mtime >= ?"
                args += cutoff
            }
            QField.TYPE -> {
                val t = n.value.lowercase()
                if (t == "dir" || t == "folder") out += "n.is_dir = 1"
                else if (t == "file") out += "n.is_dir = 0"
            }
            QField.FROM -> {
                out += "p.origin LIKE ?"
                args += "%${n.value}%"
            }
            // What is INSIDE the file. An EXISTS against `inner_fact` rather than a post-filter,
            // because the whole point of retrieve-then-rerank is that SQL hands back few rows:
            // post-filtering `pkg:` would mean fetching 500 unrelated candidates and throwing
            // 499 away.
            QField.PKG, QField.CLASS, QField.PERM, QField.LABEL,
            QField.INZIP, QField.MEMBER, QField.TAG,
            -> {
                val kind = innerKindOf(n.field) ?: return
                out += "EXISTS (SELECT 1 FROM inner_fact i WHERE i.node_id = n.id AND i.kind = ? AND i.value LIKE ?)"
                args += kind
                args += if (n.field == QField.TAG) n.value else "%${n.value}%"
            }
            // Resolved by its own pass: a duplicate is a property of a SET of rows, which no
            // per-row predicate can express.
            QField.DUP -> Unit
            QField.NAME -> {
                out += "n.name_fold LIKE ?"
                args += "%${Fuzzy.fold(n.value)}%"
            }
            else -> Unit   // the rest are post-filters over the 500
        }
    }

    private fun cmpSql(op: Cmp) = when (op) {
        Cmp.LT -> "<"
        Cmp.LTE -> "<="
        Cmp.GT -> ">"
        Cmp.GTE -> ">="
        else -> "="
    }

    /** FTS5 treats a bare string as a query language; a filename fragment must be a phrase. */
    private fun hasDup(node: Node): Boolean = when (node) {
        is Node.Field -> node.field == QField.DUP
        is Node.And -> node.parts.any(::hasDup)
        is Node.Or -> node.parts.any(::hasDup)
        is Node.Not -> hasDup(node.part)
        else -> false
    }

    /** Node ids that have at least one byte-identical twin, for a `dup:` query. */
    private fun duplicateIds(req: SearchRequest): List<Long> {
        val scope = if (req.scope == SearchScope.DEVICE) null else req.origin
        val groups = kotlinx.coroutines.runBlocking { duplicates(scope) }
        val out = ArrayList<Long>()
        for (group in groups) for (path in group) nodeIdFor(path)?.let { out += it }
        return out
    }

    /** The `inner_fact.kind` a query field reads. The two names differ where the token reads
     *  better than the storage name - `inzip:` searches entries, `member:` searches members. */
    private fun innerKindOf(f: QField): String? = when (f) {
        QField.PKG -> "pkg"
        QField.CLASS -> "class"
        QField.PERM -> "perm"
        QField.LABEL -> "label"
        QField.INZIP -> "entry"
        QField.MEMBER -> "member"
        QField.TAG -> "tag"
        else -> null
    }

    private fun quoteFts(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun readRow(c: Cursor) = IndexRow(
        id = c.getLong(0),
        name = c.getString(1),
        nameFold = c.getString(2),
        isDir = c.getInt(3) != 0,
        size = c.getLong(4),
        mtime = c.getLong(5),
        parentId = if (c.isNull(6)) null else c.getLong(6),
        volumeId = c.getLong(7),
        origin = c.getString(8),
        opens = c.getInt(9),
        lastOpenAt = c.getLong(10),
        pinned = c.getInt(11) != 0,
    )

    // ────────────────────────── identity ──────────────────────────

    override suspend fun idFor(path: VPath): Long? = withContext(io) { nodeIdFor(path) }

    /**
     * Walk `parent_id` up to the volume root, then rebuild the path from the volume's own
     * root path.
     *
     * The volume root node carries only its last segment as a name - it is a node, not a
     * path - so reassembling from names alone would produce `/index-test-x/Download/a.txt`
     * instead of the real location. The volume row is what supplies the rest.
     */
    override suspend fun pathFor(id: Long): VPath? = withContext(io) {
        val segs = ArrayList<String>()
        var cur: Long? = id
        var volId = -1L
        var guard = 0
        while (cur != null && guard++ < 512) {
            val row = db.rawQuery("SELECT name, parent_id, vol_id FROM node WHERE id = ?", arrayOf(cur.toString()))
                .use { c -> if (c.moveToFirst()) Triple(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getLong(2)) else null }
                ?: return@withContext null
            volId = row.third
            if (row.second == null) break      // the volume root: its path comes from the volume row
            segs += row.first
            cur = row.second
        }
        val root = volumeRoot(volId) ?: return@withContext null
        segs.reverse()
        var p = root
        for (seg in segs) p = p.child(seg)
        p
    }

    /**
     * Resolve a path to a node id, walking from the VOLUME ROOT rather than from "/".
     *
     * The tree stored in `node` is rooted at the volume, not at the filesystem root: a volume
     * at `/storage/emulated/0` has one row whose parent is NULL, and `Download` hangs off it
     * directly. Walking every segment of the absolute path would look for `storage`,
     * `emulated`, `0` as separate rows and find nothing - which is exactly the bug that
     * silently breaks stable IDs, and with them every pinned shortcut.
     */
    private fun nodeIdFor(path: VPath): Long? {
        val vol = volumeIdFor(path) ?: return null
        val root = volumeRoot(vol) ?: return null
        var id = rootNodeId(vol) ?: return null
        if (path == root) return id

        val rootSegments = root.segments.size
        val segments = path.segments
        if (segments.size < rootSegments) return null
        for (seg in segments.drop(rootSegments)) {
            id = db.rawQuery(
                "SELECT id FROM node WHERE vol_id = ? AND parent_id = ? AND name = ?",
                arrayOf(vol.toString(), id.toString(), seg),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        }
        return id
    }

    private fun rootNodeId(volId: Long): Long? =
        db.rawQuery(
            "SELECT id FROM node WHERE vol_id = ? AND parent_id IS NULL LIMIT 1",
            arrayOf(volId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun volumeIdFor(path: VPath): Long? =
        db.rawQuery("SELECT id, root FROM volume WHERE ? LIKE root || '%'", arrayOf(path.toString()))
            .use { c ->
                var best: Long? = null
                var bestLen = -1
                while (c.moveToNext()) {
                    val root = c.getString(1)
                    if (root.length > bestLen) { bestLen = root.length; best = c.getLong(0) }
                }
                best
            }

    private fun volumeRoot(volId: Long): VPath? =
        db.rawQuery("SELECT root FROM volume WHERE id = ?", arrayOf(volId.toString()))
            .use { c -> if (c.moveToFirst()) runCatching { VPath.parse(c.getString(0)) }.getOrNull() else null }

    private fun ensureVolume(root: VPath): Long {
        val uuid = root.toString()
        db.rawQuery("SELECT id FROM volume WHERE uuid = ?", arrayOf(uuid)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        db.execSQL(
            "INSERT INTO volume(kind, uuid, label, root, mounted) VALUES(?,?,?,?,1)",
            arrayOf<Any>(root.scheme, uuid, root.name.ifEmpty { root.scheme }, uuid),
        )
        return db.rawQuery("SELECT id FROM volume WHERE uuid = ?", arrayOf(uuid))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    // ────────────────────────── frecency and provenance ──────────────────────────

    override suspend fun recordOpen(path: VPath) = withContext(io) {
        val id = nodeIdFor(path) ?: return@withContext
        db.execSQL(
            "INSERT INTO usage(node_id, opens, last_at) VALUES(?,1,?) " +
                "ON CONFLICT(node_id) DO UPDATE SET opens = opens + 1, last_at = excluded.last_at",
            arrayOf<Any>(id, System.currentTimeMillis()),
        )
    }

    override suspend fun setPinned(path: VPath, pinned: Boolean) = withContext(io) {
        val id = nodeIdFor(path) ?: return@withContext
        db.execSQL(
            "INSERT INTO usage(node_id, pinned) VALUES(?,?) " +
                "ON CONFLICT(node_id) DO UPDATE SET pinned = excluded.pinned",
            arrayOf<Any>(id, if (pinned) 1 else 0),
        )
    }

    override suspend fun putProvenance(path: VPath, record: Provenance) = withContext(io) {
        // Index the file first if it is not known: a biography with nothing to attach to is
        // lost, and the writer is usually telling us about a file that just appeared.
        val id = nodeIdFor(path) ?: indexSingle(path) ?: return@withContext
        db.execSQL(
            "INSERT OR REPLACE INTO provenance(node_id, source_app, origin, page_title, uploader, fmt, at, extra) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(
                id, record.sourceApp, record.origin, record.pageTitle,
                record.uploader, record.format, record.at, record.extra,
            ),
        )
    }

    override suspend fun provenanceOf(path: VPath): Provenance? = withContext(io) {
        val id = nodeIdFor(path) ?: return@withContext null
        db.rawQuery(
            "SELECT source_app, origin, page_title, uploader, fmt, at, extra FROM provenance WHERE node_id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) null
            else Provenance(
                sourceApp = c.getString(0) ?: "",
                origin = c.getString(1),
                pageTitle = c.getString(2),
                uploader = c.getString(3),
                format = c.getString(4),
                at = c.getLong(5),
                extra = c.getString(6),
            )
        }
    }

    override suspend fun byOrigin(fragment: String, limit: Int): List<IndexRow> = withContext(io) {
        db.rawQuery(
            "SELECT n.id, n.name, n.name_fold, n.is_dir, n.size, n.mtime, n.parent_id, n.vol_id, " +
                "p.origin, IFNULL(u.opens,0), IFNULL(u.last_at,0), IFNULL(u.pinned,0) " +
                "FROM provenance p JOIN node n ON n.id = p.node_id " +
                "LEFT JOIN usage u ON u.node_id = n.id " +
                "WHERE p.origin LIKE ? LIMIT ?",
            arrayOf("%$fragment%", limit.toString()),
        ).map(::readRow)
    }

    override suspend fun putInner(path: VPath, kind: String, values: List<String>) = withContext(io) {
        val id = nodeIdFor(path) ?: indexSingle(path) ?: return@withContext
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM inner_fact WHERE node_id = ? AND kind = ?", arrayOf<Any>(id, kind))
            for (v in values) {
                db.execSQL("INSERT INTO inner_fact(node_id, kind, value) VALUES(?,?,?)", arrayOf<Any>(id, kind, v))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun innerOf(id: Long, kind: String): List<String> = withContext(io) {
        db.rawQuery(
            "SELECT value FROM inner_fact WHERE node_id = ? AND kind = ?",
            arrayOf(id.toString(), kind),
        ).map { it.getString(0) }
    }

    // ────────────────────────── crawl ──────────────────────────

    /**
     * The detour a search has asked for, if any.
     *
     * Volatile and plain rather than a flow: it is written from the UI thread and read once
     * per directory by the crawl coroutine, and a flow would add a collector to a loop whose
     * whole job is to not allocate.
     */
    @Volatile
    private var detour: Detour? = null

    override fun steerCrawl(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            detour = null
            _status.update { it.copy(steeredFor = null) }
            return
        }
        // Only while something is actually running. Steering a crawl that is not happening
        // would put a notice on screen promising work nobody is doing.
        if (!_status.value.running) return
        detour = Detour(trimmed, System.currentTimeMillis())
        _status.update { it.copy(steeredFor = trimmed) }
    }

    /** The detour, or null once it has run out of time. Clears the notice as it expires. */
    private fun liveDetour(now: Long): Detour? {
        val d = detour ?: return null
        if (!d.expired(now)) return d
        detour = null
        _status.update { it.copy(steeredFor = null) }
        return null
    }

    override suspend fun crawl(roots: List<VPath>, budgetMs: Long, onProgress: (Long) -> Unit): CrawlResult =
        lock.withLock {
            withContext(io) {
                val started = System.currentTimeMillis()
                // A zero or negative budget means "no deadline" - see FileIndex.crawl. Adding
                // it blindly would also overflow on Long.MAX_VALUE and produce a deadline in
                // the past, which is the opposite of what the caller asked for.
                val deadline = if (budgetMs <= 0) Long.MAX_VALUE else started + budgetMs
                val gen = (dbh.meta(META_GEN)?.toLongOrNull() ?: 0L) + 1
                _status.update { it.copy(running = true, phase = "scanning", scanned = 0) }

                var visited = 0
                var seen = 0L
                var changed = 0
                var complete = true
                var moved = 0
                var factsWritten = 0

                try {
                    for (root in roots) {
                        val volId = ensureVolume(root)
                        val rootId = upsertNode(volId, null, root.name.ifEmpty { root.scheme }, true, -1, 0, gen)
                        // Pending entries carry the order they were discovered in, so a
                        // detour is a sort and undoing one is a sort back - see CrawlPriority.
                        var discovered = 0L
                        val queue = ArrayDeque(listOf(Pending(discovered++, rootId, root)))
                        var steeredFor: String? = null
                        while (queue.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val now = System.currentTimeMillis()
                            if (now > deadline) { complete = false; break }

                            val want = liveDetour(now)?.query
                            if (want != steeredFor) {
                                // Once per change of query, not once per directory: sorting a
                                // queue of tens of thousands on every step would cost more
                                // than the crawl it is trying to help.
                                val reordered = steer(queue.toList(), want.orEmpty())
                                queue.clear()
                                queue.addAll(reordered)
                                steeredFor = want
                            }

                            val pending = queue.removeFirst()
                            val parentId = pending.nodeId
                            val dir = pending.path
                            visited++

                            val children = runCatching { vfs.list(dir) }.getOrNull() ?: continue
                            // Directory-mtime validation, SEARCH.md §4.2: if the directory has
                            // not changed since the last readdir, its children are still right
                            // and re-stating all of them is pure waste.
                            val dirMtime = runCatching { vfs.stat(dir)?.mtime ?: 0L }.getOrDefault(0L)
                            val known = knownDirMtime(parentId)
                            val unchanged = known != 0L && known == dirMtime

                            db.beginTransaction()
                            val toExtract = ArrayList<Pair<Long, dev.niccc2007.filet.vfs.VNode>>()
                            try {
                                if (!unchanged) changed++
                                for (child in children) {
                                    seen++
                                    val before = if (extractorsByExt.isEmpty()) null else knownStamp(volId, parentId, child.name)
                                    val id = upsertNode(
                                        volId, parentId, child.name, child.isDir,
                                        child.size, child.mtime, gen,
                                        hidden = child.hidden,
                                        inode = child.inode,
                                    )
                                    if (child.isDir) queue.addLast(Pending(discovered++, id, child.path))
                                    // Look inside only when the file is new or has actually
                                    // changed. Re-parsing every APK on every crawl would turn a
                                    // cheap metadata pass into a minutes-long one.
                                    else if (!child.isDir && extractorsByExt.containsKey(child.extension) &&
                                        before != (child.size to child.mtime)
                                    ) toExtract += id to child
                                }
                                db.execSQL(
                                    "UPDATE node SET dir_mtime = ?, gen = ? WHERE id = ?",
                                    arrayOf<Any>(dirMtime, gen, parentId),
                                )
                                db.setTransactionSuccessful()
                            } finally {
                                db.endTransaction()
                            }

                            // Outside the transaction: opening an archive is slow, and holding a
                            // write lock across it would stall every read in the app.
                            for ((id, child) in toExtract) {
                                currentCoroutineContext().ensureActive()
                                if (System.currentTimeMillis() > deadline) { complete = false; break }
                                for (extractor in extractorsByExt[child.extension].orEmpty()) {
                                    val facts = runCatching { extractor.facts(child) }.getOrNull() ?: continue
                                    for ((kind, values) in facts) writeInner(id, kind, values)
                                    factsWritten++
                                }
                            }
                            if (!complete) break
                            if (visited % 40 == 0) {
                                _status.update { it.copy(scanned = seen) }
                                onProgress(seen)
                            }
                        }
                        if (!complete) break
                    }

                    // Generation sweep, SEARCH.md §4.4. Only after a COMPLETE pass: sweeping
                    // after a truncated one would delete everything the crawl never reached.
                    if (complete) {
                        // Moves first, THEN the sweep. The other order destroys the very rows
                        // a move needs to carry forward.
                        moved = relinkMoves(gen)
                        db.execSQL("DELETE FROM node WHERE gen < ?", arrayOf<Any>(gen))
                        dbh.putMeta(META_GEN, gen.toString())
                        dbh.putMeta(META_LAST_RUN, System.currentTimeMillis().toString())
                    }
                } finally {
                    // The detour cannot outlive the crawl it was steering.
                    detour = null
                    val files = countFiles()
                    _status.update {
                        it.copy(
                            running = false, phase = "", scanned = seen, steeredFor = null,
                            files = files, available = files > 0,
                            lastRunAt = if (complete) System.currentTimeMillis() else it.lastRunAt,
                        )
                    }
                }

                CrawlResult(
                    visited, seen.toInt(), changed, complete,
                    System.currentTimeMillis() - started, moved, factsWritten,
                )
            }
        }

    /**
     * Carry a moved file's identity across a crawl, instead of letting the sweep destroy it.
     *
     * A move is indistinguishable from "deleted over here, created over there" unless
     * something ties the two rows together. Without that tie the generation sweep deletes the
     * old row, its id stops resolving, and every pinned shortcut, provenance record and open
     * count attached to that file dies with it - which is precisely the failure the shortcut
     * rule exists to prevent:
     *
     * > A shortcut never stores a path. It stores a Filet file ID, and Filet resolves ID to
     * > the current path at launch (PLAN.md L5).
     *
     * The tie is the **inode**, which a rename inside a filesystem preserves. Where the
     * backend has no such number - archives, SMB, WebDAV - the fallback is an exact
     * `(name, size, mtime)` signature, which a move also preserves.
     *
     * Three rules keep this from doing harm:
     *
     * 1. **Identity moves to the new place; it is never replaced.** The OLD row's id is kept
     *    and repointed. Keeping the new row instead would change the id, which is the one
     *    thing that must not happen.
     * 2. **Only one-to-one matches are relinked.** Two identical files and one deletion is
     *    ambiguous, and guessing would bind a shortcut to the wrong file. Ambiguity is left
     *    to the sweep, so the shortcut dies honestly instead of lying.
     * 3. **Files only.** A moved directory's own row is not carried over: its children are
     *    already re-parented under a fresh row, and re-pointing the old directory at that
     *    same parent collides on `node_uq(vol_id, parent_id, name)`. The files inside still
     *    keep their ids, because each is matched on its own inode wherever it ends up - so a
     *    shortcut to a file survives its whole folder being moved.
     *
     * @return how many files were carried forward.
     */
    private fun relinkMoves(gen: Long): Int {
        // Rows the sweep is about to delete. Normally empty, which is why this returns before
        // touching anything else - a crawl where nothing vanished must cost nothing.
        val gone = ArrayList<Candidate>()
        db.rawQuery(
            "SELECT id, vol_id, name, size, mtime, IFNULL(inode, 0) FROM node WHERE gen < ? AND is_dir = 0",
            arrayOf(gen.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                gone += Candidate(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5))
            }
        }
        if (gone.isEmpty()) return 0

        // Pair each vanished row with the single fresh row that is the same file.
        val pairs = ArrayList<Pair<Long, Long>>()
        for (old in gone) {
            val fresh = if (old.inode != 0L) {
                query(
                    "SELECT id FROM node WHERE gen = ? AND vol_id = ? AND is_dir = 0 AND inode = ? LIMIT 2",
                    arrayOf(gen.toString(), old.volId.toString(), old.inode.toString()),
                )
            } else {
                query(
                    "SELECT id FROM node WHERE gen = ? AND vol_id = ? AND is_dir = 0 AND " +
                        "name = ? AND size = ? AND mtime = ? LIMIT 2",
                    arrayOf(gen.toString(), old.volId.toString(), old.name, old.size.toString(), old.mtime.toString()),
                )
            }
            // Exactly one candidate, and not the row itself.
            if (fresh.size == 1 && fresh[0] != old.id) pairs += old.id to fresh[0]
        }

        // Rule 2, the other direction: drop any pair whose new row two different old rows both
        // claim. Silence beats a confident wrong answer here.
        val claims = pairs.groupingBy { it.second }.eachCount()
        val unambiguous = pairs.filter { claims[it.second] == 1 }
        if (unambiguous.isEmpty()) return 0

        var relinked = 0
        db.beginTransaction()
        try {
            for ((oldId, newId) in unambiguous) {
                val row = db.rawQuery(
                    "SELECT parent_id, name, name_fold, size, mtime, flags, IFNULL(inode, 0) FROM node WHERE id = ?",
                    arrayOf(newId.toString()),
                ).use { c ->
                    if (!c.moveToFirst()) null
                    else NewPlace(
                        if (c.isNull(0)) null else c.getLong(0),
                        c.getString(1), c.getString(2),
                        c.getLong(3), c.getLong(4), c.getInt(5), c.getLong(6),
                    )
                } ?: continue

                // The fresh row has to go before the old one can take its place - they would
                // otherwise be two rows with the same (vol_id, parent_id, name).
                db.execSQL("DELETE FROM node WHERE id = ?", arrayOf<Any>(newId))
                db.execSQL(
                    "UPDATE node SET parent_id = ?, name = ?, name_fold = ?, size = ?, mtime = ?, " +
                        "flags = ?, gen = ?, inode = ? WHERE id = ?",
                    arrayOf(
                        row.parentId, row.name, row.nameFold, row.size, row.mtime,
                        row.flags, gen, row.inode, oldId,
                    ),
                )
                relinked++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return relinked
    }

    private fun query(sql: String, args: Array<String>): List<Long> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Long>(2)
            while (c.moveToNext()) out += c.getLong(0)
            out
        }

    private class Candidate(
        val id: Long,
        val volId: Long,
        val name: String,
        val size: Long,
        val mtime: Long,
        val inode: Long,
    )

    private class NewPlace(
        val parentId: Long?,
        val name: String,
        val nameFold: String,
        val size: Long,
        val mtime: Long,
        val flags: Int,
        val inode: Long,
    )

    /** Size and mtime as the index last saw them, so an unchanged file is not re-parsed. */
    private fun knownStamp(volId: Long, parentId: Long?, name: String): Pair<Long, Long>? {
        val sql = if (parentId == null) {
            "SELECT size, mtime FROM node WHERE vol_id = ? AND parent_id IS NULL AND name = ?"
        } else {
            "SELECT size, mtime FROM node WHERE vol_id = ? AND parent_id = ? AND name = ?"
        }
        val args = if (parentId == null) arrayOf(volId.toString(), name)
        else arrayOf(volId.toString(), parentId.toString(), name)
        return db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
    }

    /** The write half of [putInner], without the path lookup - the crawl already has the id. */
    private fun writeInner(id: Long, kind: String, values: List<String>) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM inner_fact WHERE node_id = ? AND kind = ?", arrayOf<Any>(id, kind))
            for (v in values) {
                db.execSQL("INSERT INTO inner_fact(node_id, kind, value) VALUES(?,?,?)", arrayOf<Any>(id, kind, v))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The three-stage duplicate hunt (FEATURES.md F62).
     *
     * Stage 1 is free: SQL groups by size, and any file with a unique size cannot have a twin.
     * On a real device that removes the overwhelming majority before a single byte is read.
     * Stage 2 hashes 4 KB from each end, which separates same-size-different-content files -
     * media containers and archives differ in their headers almost immediately. Only what
     * survives both reaches stage 3 and gets read in full.
     *
     * The order matters more than the hash function. Reading every file and hashing it is the
     * obvious implementation and it is unusable: on a phone it is minutes of IO and a hot
     * battery to answer a question that stage 1 usually answers for free.
     */
    override suspend fun duplicates(scope: VPath?, minSize: Long, limitGroups: Int): List<List<VPath>> =
        withContext(io) {
            val scopeId = scope?.let { nodeIdFor(it) }
            val args = ArrayList<String>()
            val where = StringBuilder("n.is_dir = 0 AND n.size >= ?")
            args += minSize.toString()
            if (scope != null) {
                if (scopeId == null) return@withContext emptyList()
                where.append(
                    " AND n.parent_id IN (WITH RECURSIVE sub(id) AS (" +
                        "SELECT ? UNION ALL SELECT c.id FROM node c JOIN sub ON c.parent_id = sub.id WHERE c.is_dir = 1" +
                        ") SELECT id FROM sub)"
                )
                args += scopeId.toString()
            }

            // Stage 1 - candidates are rows whose size is shared with at least one other row.
            val bySize = LinkedHashMap<Long, MutableList<Long>>()
            db.rawQuery(
                "SELECT n.id, n.size FROM node n WHERE $where AND n.size IN (" +
                    "SELECT size FROM node WHERE is_dir = 0 AND size >= ? GROUP BY size HAVING COUNT(*) > 1)",
                (args + minSize.toString()).toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) bySize.getOrPut(c.getLong(1)) { ArrayList() } += c.getLong(0)
            }

            val groups = ArrayList<List<VPath>>()
            for ((size, ids) in bySize) {
                currentCoroutineContext().ensureActive()
                if (groups.size >= limitGroups) break
                if (ids.size < 2) continue

                // Stage 2 - cheap signature.
                val byEdges = HashMap<Long, MutableList<VPath>>()
                for (id in ids) {
                    val path = pathForSync(id) ?: continue
                    val sig = edgeHash(path, size) ?: continue
                    byEdges.getOrPut(sig) { ArrayList() } += path
                }

                // Stage 3 - full content, only for what survived.
                for ((_, sameEdges) in byEdges) {
                    if (sameEdges.size < 2) continue
                    val byFull = HashMap<Long, MutableList<VPath>>()
                    for (path in sameEdges) {
                        val full = fullHash(path) ?: continue
                        byFull.getOrPut(full) { ArrayList() } += path
                    }
                    for ((_, identical) in byFull) {
                        if (identical.size >= 2) groups += identical
                        if (groups.size >= limitGroups) break
                    }
                }
            }
            groups
        }

    /** FNV-1a over the first and last 4 KB, plus the size. Fast, and never needs to be secure. */
    private fun edgeHash(path: VPath, size: Long): Long? = runCatching {
        val head = ByteArray(EDGE)
        val tail = ByteArray(EDGE)
        var headLen = 0
        var tailLen = 0
        kotlinx.coroutines.runBlocking { vfs.openRead(path) }.use { input ->
            headLen = input.readNBytesCompat(head)
            if (size > EDGE * 2L) {
                var skip = size - EDGE * 2L
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                input.readNBytesCompat(ByteArray(EDGE))   // the middle chunk we deliberately drop
            }
            tailLen = input.readNBytesCompat(tail)
        }
        var h = 0xcbf29ce484222325UL.toLong()
        fun mix(b: ByteArray, len: Int) {
            for (i in 0 until len) { h = h xor b[i].toLong(); h *= 0x100000001b3L }
        }
        mix(head, headLen)
        mix(tail, tailLen)
        h = h xor size
        h
    }.getOrNull()

    private fun fullHash(path: VPath): Long? = runCatching {
        var h = 0xcbf29ce484222325UL.toLong()
        val buf = ByteArray(64 * 1024)
        kotlinx.coroutines.runBlocking { vfs.openRead(path) }.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) { h = h xor buf[i].toLong(); h *= 0x100000001b3L }
            }
        }
        h
    }.getOrNull()

    /** `pathFor` without the coroutine hop, for use inside a pass that is already off-thread. */
    private fun pathForSync(id: Long): VPath? = kotlinx.coroutines.runBlocking { pathFor(id) }

    private fun java.io.InputStream.readNBytesCompat(into: ByteArray): Int {
        var off = 0
        while (off < into.size) {
            val n = read(into, off, into.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun knownDirMtime(id: Long): Long =
        db.rawQuery("SELECT IFNULL(dir_mtime, 0) FROM node WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun upsertNode(
        volId: Long,
        parentId: Long?,
        name: String,
        isDir: Boolean,
        size: Long,
        mtime: Long,
        gen: Long,
        hidden: Boolean = false,
        inode: Long = 0,
    ): Long {
        val fold = Fuzzy.fold(name)
        val flags = if (hidden) 1 else 0
        val sel = if (parentId == null) {
            db.rawQuery(
                "SELECT id FROM node WHERE vol_id = ? AND parent_id IS NULL AND name = ?",
                arrayOf(volId.toString(), name),
            )
        } else {
            db.rawQuery(
                "SELECT id FROM node WHERE vol_id = ? AND parent_id = ? AND name = ?",
                arrayOf(volId.toString(), parentId.toString(), name),
            )
        }
        val existing = sel.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (existing != null) {
            db.execSQL(
                "UPDATE node SET size = ?, mtime = ?, gen = ?, flags = ?, is_dir = ?, inode = ? WHERE id = ?",
                arrayOf<Any>(size, mtime, gen, flags, if (isDir) 1 else 0, inode, existing),
            )
            return existing
        }
        db.execSQL(
            "INSERT INTO node(vol_id, parent_id, name, name_fold, is_dir, size, mtime, flags, gen, inode) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(volId, parentId, name, fold, if (isDir) 1 else 0, size, mtime, flags, gen, inode),
        )
        val q = if (parentId == null) {
            db.rawQuery("SELECT id FROM node WHERE vol_id = ? AND parent_id IS NULL AND name = ?", arrayOf(volId.toString(), name))
        } else {
            db.rawQuery("SELECT id FROM node WHERE vol_id = ? AND parent_id = ? AND name = ?", arrayOf(volId.toString(), parentId.toString(), name))
        }
        return q.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    /**
     * Index one path and the ancestors between it and its volume root, so a record can attach
     * to a file nothing has crawled yet.
     *
     * Rooted at the volume for the same reason [nodeIdFor] is: the tree starts there.
     */
    private fun indexSingle(path: VPath): Long? {
        val root = volumeRootFor(path) ?: return null
        val volId = ensureVolume(root)
        val gen = dbh.meta(META_GEN)?.toLongOrNull() ?: 1L
        var parent = upsertNode(volId, null, root.name.ifEmpty { root.scheme }, true, -1L, 0L, gen)
        var cur = root
        val rootSegments = root.segments.size
        val segments = path.segments
        if (segments.size < rootSegments) return parent
        for (seg in segments.drop(rootSegments)) {
            cur = cur.child(seg)
            val stat = runCatching { kotlinx.coroutines.runBlocking { vfs.stat(cur) } }.getOrNull()
            parent = upsertNode(
                volId, parent, seg,
                stat?.isDir ?: (cur != path),
                stat?.size ?: -1L,
                stat?.mtime ?: 0L,
                gen,
                stat?.hidden == true,
            )
        }
        return parent
    }

    private fun volumeRootFor(path: VPath): VPath? {
        db.rawQuery("SELECT root FROM volume WHERE ? LIKE root || '%'", arrayOf(path.toString())).use { c ->
            var best: String? = null
            while (c.moveToNext()) {
                val r = c.getString(0)
                if (best == null || r.length > best!!.length) best = r
            }
            if (best != null) return runCatching { VPath.parse(best!!) }.getOrNull()
        }
        // Unknown volume: treat the scheme root as one rather than refusing the record.
        return VPath(path.scheme, "/")
    }

    override suspend fun forget(path: VPath) = withContext(io) {
        val id = nodeIdFor(path) ?: return@withContext
        // ON DELETE CASCADE only reaches the side tables; children reference parent_id with
        // no constraint, so the subtree is removed explicitly.
        db.execSQL(
            "WITH RECURSIVE sub(id) AS (SELECT ? UNION ALL SELECT c.id FROM node c JOIN sub ON c.parent_id = sub.id) " +
                "DELETE FROM node WHERE id IN (SELECT id FROM sub)",
            arrayOf<Any>(id),
        )
        _status.update { it.copy(files = countFiles()) }
    }

    override suspend fun clear() = lock.withLock {
        withContext(io) {
            dbh.wipe()
            // A fresh IndexStatus here would silently drop every field not listed - which is
            // how clearing the index used to turn off `pkg:` and `inzip:` until the next app
            // start. Wiping the DATA must not change what the index is CAPABLE of.
            _status.update {
                it.copy(
                    available = false, files = 0, lastRunAt = 0,
                    running = false, phase = "", scanned = 0,
                    ftsAccelerated = dbh.hasFts,
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) = _status.update { it.copy(enabled = enabled) }

    override fun close() = dbh.close()

    private fun countFiles(): Long =
        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM node", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrDefault(0L)

    private val EDGE = 4096

    private companion object {
        const val META_GEN = "gen"
        const val META_LAST_RUN = "lastRun"
    }
}
