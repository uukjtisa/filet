package dev.niccc2007.filet.vfs.provider

/**
 * What an extraction is about to do to a folder, worked out before a byte is written.
 *
 * Nic, round 9:
 *
 * > "theres this porblem when extracting archive sometiemsi expect it to be extracted as
 * > `<archivename>/content` but it ends up bombing me by extracting `/content`"
 *
 * That is a tarbomb, and the fix is not a better default - it is showing him the answer before
 * he commits to it. So the preview and the extractor read **the same value**: this file
 * produces an [ExtractPlan], the preview draws it, and the extractor walks it. If those two
 * ever came from different code the preview would be a drawing of something that then does not
 * happen, which is a worse feature than no preview at all.
 *
 * Everything here is pure: names in, names out, no storage. The two things it needs to know
 * about the world - what is already at the destination, and how much room there is - are
 * parameters, because that is what makes the awkward cases testable.
 *
 * ## The two transforms, and why they can never both apply
 *
 * - **Wrap.** Put everything inside a new folder named after the archive. For an archive with
 *   several things loose at its top level.
 * - **Strip.** Lift the contents out of a redundant parent folder, repeatedly, while each
 *   level holds exactly one folder and nothing else. For `archive.zip/archive/archivehere/…`.
 *
 * They are opposites, so [WrapChoice.AUTO] picks at most one: an archive with a single root
 * folder gets stripped and never wrapped, and an archive with loose files gets wrapped and has
 * nothing to strip. Either can then be overridden from the preview, one tap each, which is
 * what his two buttons are.
 */

/** One member, as the archive stores it. */
data class ArchiveEntry(val path: String, val isDir: Boolean, val size: Long = 0)

/** What to do about the wrapping folder. */
enum class WrapChoice {
    /** Wrap when the archive would otherwise scatter. */
    AUTO,

    /** His "put inside a folder with the original archive name", pressed. */
    FORCE_ON,

    /** The same button pressed again when AUTO had already wrapped. */
    FORCE_OFF,
}

/** What to do when a file already exists at the destination. */
enum class CollisionChoice {
    /** Leave what is there. The safe default: nothing already on disk is lost. */
    SKIP,
    OVERWRITE,

    /** Extract alongside as `name (1).ext`, so both survive and neither is guessed at. */
    KEEP_BOTH,
}

/**
 * @param stripLevels how many redundant parent folders to lift away, or null for "as many as
 *   are genuinely redundant". His *"add an option to keep doing so if its still one folder"*
 *   is the null case; the preview's undo sets an explicit smaller number.
 */
data class ExtractOptions(
    val wrap: WrapChoice = WrapChoice.AUTO,
    val stripLevels: Int? = null,
    val onCollision: CollisionChoice = CollisionChoice.SKIP,
)

/**
 * One thing that will exist afterwards, at [path] relative to the destination.
 *
 * @param source where to read it FROM, relative to the archive root. Not the same string as
 *   [path] once anything has been stripped or wrapped, and carrying both is what lets the
 *   extractor walk this list directly instead of re-deriving the transform. If it re-derived
 *   it, the preview and the extraction would be two implementations of one decision, which is
 *   the thing this whole file exists to prevent. Empty for a folder the plan invents.
 */
data class PlannedItem(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val source: String = "",
    /** True for a folder this plan invents, which the preview highlights. */
    val added: Boolean = false,
    /** Non-null when something is already there and this is what will happen to it. */
    val collides: CollisionChoice? = null,
)

/** An entry that will not be written, and why. */
data class RefusedEntry(val path: String, val reason: String)

/**
 * The whole answer. Everything the preview draws and everything the extractor needs.
 *
 * @param strippedFolders the parent folders lifted away, outermost first, so the preview can
 *   show them struck through instead of just silently not being there.
 * @param needsBytes uncompressed total of what will actually be written, after skips.
 */
data class ExtractPlan(
    val archiveName: String,
    val wrapFolder: String?,
    val strippedFolders: List<String>,
    val items: List<PlannedItem>,
    val refused: List<RefusedEntry>,
    val needsBytes: Long,
    val freeBytes: Long,
    val entryCount: Int,
    val alreadyCompressedBytes: Long,
    /** How many levels COULD be stripped, so the preview knows whether to offer the button. */
    val strippableLevels: Int,
) {
    val collisions: List<PlannedItem> get() = items.filter { it.collides != null }
    val fits: Boolean get() = freeBytes < 0 || needsBytes <= freeBytes
    val addedFolders: List<PlannedItem> get() = items.filter { it.added }
}

object ExtractPlanner {

    /**
     * Folders that can be lifted away, outermost first.
     *
     * The rule is **exactly one folder and nothing else** at each level. A level holding one
     * folder AND a readme stops it, because lifting there would scatter two things into the
     * destination - which is the bug this whole feature exists to prevent, just one level
     * down.
     */
    fun redundantParents(paths: List<String>): List<String> {
        val out = mutableListOf<String>()
        var current = paths.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct()
        while (true) {
            if (current.isEmpty()) break
            val heads = current.mapTo(HashSet()) { it.substringBefore('/') }
            if (heads.size != 1) break
            val head = heads.first()
            // The single head has to BE a folder with something in it. A lone file, or a lone
            // empty directory, leaves nothing to lift out.
            if (current.none { it.startsWith("$head/") }) break
            out.add(head)
            current = current.mapNotNull { p ->
                if (p == head) null else p.removePrefix("$head/")
            }.filter { it.isNotEmpty() }
        }
        return out
    }

    /**
     * How many things sit at the archive's top level.
     *
     * Counted as distinct first components rather than as entries, because an archive that
     * stores no explicit directory records still has a top level.
     */
    fun topLevelCount(paths: List<String>): Int =
        paths.map { it.trim('/') }.filter { it.isNotEmpty() }
            .mapTo(HashSet()) { it.substringBefore('/') }.size

    /**
     * Whether an entry would be written outside the destination.
     *
     * Refused rather than sanitised. An archive containing `../../etc/passwd` is not one whose
     * author made a mistake with a relative path, and quietly rewriting it to `etc/passwd`
     * extracts a file somebody deliberately aimed somewhere else.
     */
    fun escapeReason(rawPath: String): String? {
        val path = rawPath.replace('\\', '/')
        if (path.startsWith("/")) return "absolute path"
        if (path.length > 1 && path[1] == ':') return "drive letter"
        if (path.split('/').any { it == ".." }) return "points outside the folder"
        if (path.contains('\u0000')) return "contains a null byte"
        return null
    }

    /**
     * `report.txt` -> `report (1).txt`, keeping the extension where there is one.
     *
     * A dotfile such as `.gitignore` has no extension to keep, and `(1)` belongs on the end of
     * it rather than in the middle of the name.
     */
    fun keepBothName(name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }

    /**
     * Work out what the extraction will produce.
     *
     * @param existingPaths what is already at the destination, relative to it. Whatever the
     *   caller can cheaply see - a pane already holds its own listing. A path not in the set is
     *   taken to be absent, so a caller that only knows the top level still gets top-level
     *   collision detection, which is where a tarbomb lands.
     * @param freeBytes free space on the destination's own volume, or -1 when unknown. The
     *   volume, not the device: extracting to an SD card asks the SD card.
     */
    fun plan(
        archiveName: String,
        entries: List<ArchiveEntry>,
        existingPaths: Set<String> = emptySet(),
        freeBytes: Long = -1,
        options: ExtractOptions = ExtractOptions(),
    ): ExtractPlan {
        val refused = mutableListOf<RefusedEntry>()
        val usable = mutableListOf<ArchiveEntry>()
        for (e in entries) {
            val why = escapeReason(e.path)
            if (why != null) refused.add(RefusedEntry(e.path, why))
            else {
                val clean = e.path.replace('\\', '/').trim('/')
                if (clean.isNotEmpty()) usable.add(e.copy(path = clean))
            }
        }

        val paths = usable.map { it.path }
        val chain = redundantParents(paths)
        val strip = (options.stripLevels ?: chain.size).coerceIn(0, chain.size)
        val stripped = chain.take(strip)
        val prefix = if (stripped.isEmpty()) "" else stripped.joinToString("/") + "/"

        val afterStrip = usable.mapNotNull { e ->
            if (prefix.isEmpty()) e
            else {
                val rest = if (e.path == prefix.trimEnd('/')) "" else e.path.removePrefix(prefix)
                // A parent folder that was lifted away is not an item any more. Its own record
                // has to go, or the plan re-creates the folder it just removed.
                if (rest.isEmpty() || rest == e.path) null else e.copy(path = rest)
            }
        }

        val wrap = when (options.wrap) {
            WrapChoice.FORCE_ON -> true
            WrapChoice.FORCE_OFF -> false
            // Stripping and wrapping are opposites, so AUTO never does both. Nothing was
            // stripped AND more than one thing is loose at the top: that is the bomb.
            WrapChoice.AUTO -> stripped.isEmpty() && topLevelCount(afterStrip.map { it.path }) > 1
        }
        val wrapFolder = if (wrap) Archives.baseName(archiveName).ifBlank { "extracted" } else null

        val items = mutableListOf<PlannedItem>()
        if (wrapFolder != null) {
            items.add(
                PlannedItem(
                    path = wrapFolder,
                    isDir = true,
                    size = 0,
                    added = true,
                    // The folder itself can collide, and that is worth saying: extracting into
                    // an existing folder of the same name is usually meant, and occasionally
                    // is the thing that ruins an afternoon.
                    collides = if (wrapFolder in existingPaths) options.onCollision else null,
                ),
            )
        }

        val taken = HashSet(existingPaths)
        var needs = 0L
        var media = 0L
        for (e in afterStrip.sortedBy { it.path }) {
            val under = if (wrapFolder != null) "$wrapFolder/${e.path}" else e.path
            var finalPath = under
            var collides: CollisionChoice? = null
            if (under in existingPaths) {
                collides = options.onCollision
                if (options.onCollision == CollisionChoice.KEEP_BOTH && !e.isDir) {
                    val dir = under.substringBeforeLast('/', "")
                    val name = under.substringAfterLast('/')
                    val renamed = keepBothName(name, taken.mapTo(HashSet()) { it.substringAfterLast('/') })
                    finalPath = if (dir.isEmpty()) renamed else "$dir/$renamed"
                }
            }
            taken.add(finalPath)
            items.add(
                PlannedItem(
                    path = finalPath,
                    isDir = e.isDir,
                    size = e.size,
                    // The path INSIDE the archive, before stripping and wrapping. The extractor
                    // reads from here and writes to `path`, so the two never disagree.
                    source = if (prefix.isEmpty()) e.path else prefix + e.path,
                    added = false,
                    collides = collides,
                ),
            )

            // Skipped files are not written, so they do not count toward the space needed.
            val willWrite = !e.isDir && (collides == null || options.onCollision != CollisionChoice.SKIP)
            if (willWrite) {
                needs += e.size
                val ext = e.path.substringAfterLast('.', "").lowercase()
                if (ext in ArchiveWriter.ALREADY_COMPRESSED) media += e.size
            }
        }

        return ExtractPlan(
            archiveName = archiveName,
            wrapFolder = wrapFolder,
            strippedFolders = stripped,
            items = items,
            refused = refused,
            needsBytes = needs,
            freeBytes = freeBytes,
            entryCount = afterStrip.count { !it.isDir },
            alreadyCompressedBytes = media,
            strippableLevels = chain.size,
        )
    }
}
