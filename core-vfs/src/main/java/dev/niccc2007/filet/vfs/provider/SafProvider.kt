package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework volumes - SD cards, `Android/data`, and anything else the user has
 * granted a tree for. First-class, not a fallback: PLAN.md L0 makes this the path that keeps
 * the app usable when all-files access is refused.
 *
 * Addressing: `saf:///<treeUriEncoded>/<documentId>`. The tree URI is carried in the path so a
 * VPath stays a plain string and bookmarks survive a restart.
 *
 * Children are fetched with ONE cursor per directory (SEARCH.md §4.6) rather than per-file IPC,
 * which is the difference between a usable SD card and an unusable one.
 */
/** What the first three parts of a SAF path name: the document itself, or the name beside it. */
internal enum class SafAnchor { SELF, SIBLING }

class SafProvider(private val context: Context) : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> =
        setOf(Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR)

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapNotNull { perm ->
                val tree = perm.uri
                val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                    ?: return@mapNotNull null
                stat(pathOf(tree, docId))
            }
    }

    /**
     * The document a path names.
     *
     * Bug identified here: a path is `tree/docId/name`, and only the first two parts were read. A
     * path built for something NEW inside a folder - `folder.child("notes.txt")`, which is how
     * every copy names its destination - is `tree/folderId/FolderName/notes.txt`, and it was read
     * as the folder itself. So the copy's "is it already there?" check found the folder and
     * refused every copy with "already exists", and a folder copied in put its children into its
     * parent. Reading worked and deleting worked, because both are only ever handed real nodes.
     *
     * Every name after the third part is now a child, looked up one level at a time under the
     * document before it. Null when one of them does not exist yet.
     */
    private fun resolve(path: VPath): Pair<Uri, String>? {
        val (tree, docId) = parse(path)
        var id = anchor(tree, docId, path) ?: return null
        for (name in childSegments(path)) {
            id = childId(tree, id, Uri.decode(name)) ?: return null
        }
        return tree to id
    }

    /**
     * The document the first three parts of a path name: the document itself, or a sibling of it.
     *
     * Bug identified here: the text editor saved a file in Termux's home by writing a hidden copy
     * beside it, and "beside" was `file.parent.child(".filet-saving-name")`. A SAF path carries
     * the document's OWN id, so its parent is that same document without its name, and adding a
     * name back gave `tree/fileId/.filet-saving-name`, which was read as the file itself. The
     * save checked whether the hidden copy existed, found the file, and deleted it. Each save
     * destroyed the document being saved, which then showed as "no longer exists".
     *
     * A listed path's third part is always the document's own name, because [pathOf] writes it.
     * So a third part that is NOT the document's name was built as a sibling, and is read as one:
     * that name in the document's folder. The same holds when the document no longer exists,
     * which is what a save that copies under a new name leaves behind.
     */
    private fun anchor(tree: Uri, docId: String, path: VPath): String? {
        val segs = path.segments
        val third = segs.getOrNull(2)?.let { Uri.decode(it) }
        return when (anchorOf(segs.size, third, third?.let { ownName(tree, docId) }, isRoot(tree, docId))) {
            SafAnchor.SELF -> docId
            SafAnchor.SIBLING -> parentOf(tree, docId)?.let { childId(tree, it, third!!) }
        }
    }

    private fun isRoot(tree: Uri, docId: String): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(tree) == docId }.getOrDefault(false)

    /** The document's display name, from the last listing when it was seen, otherwise asked. Null when it is gone. */
    private fun ownName(tree: Uri, docId: String): String? {
        names["$tree|$docId"]?.let { return it }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.also { names["$tree|$docId"] = it }
    }

    /**
     * The folder a document was listed in. The listing is where nearly every path comes from; for
     * one that did not (a tab restored after a restart), the provider is asked for the document's
     * path from the top of the tree, which providers may decline.
     */
    private fun parentOf(tree: Uri, docId: String): String? {
        parents["$tree|$docId"]?.let { return it }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val chain = runCatching { DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path }.getOrNull()
        val parent = chain?.takeIf { it.size >= 2 }?.let { it[it.size - 2] } ?: return null
        remember(tree, docId, parent)
        return parent
    }

    /**
     * The folder a NEW path's last name goes into. For `tree/docId/name` that is the folder
     * holding the document when the name is a sibling's; the document itself is not a folder to
     * create in, and treating it as one is the same misreading as [anchor]'s.
     */
    private fun container(path: VPath): Pair<Uri, String> {
        val segs = path.segments
        if (segs.size < 3) throw VfsException.Unsupported("cannot create a volume root")
        if (segs.size > 3) return resolveOrThrow(path.parent ?: throw VfsException.NotFound(path))
        val (tree, docId) = parse(path)
        val third = Uri.decode(segs[2])
        return when (anchorOf(3, third, ownName(tree, docId), isRoot(tree, docId))) {
            SafAnchor.SELF -> throw VfsException.AlreadyExists(path)
            SafAnchor.SIBLING -> tree to (parentOf(tree, docId) ?: throw VfsException.NotFound(path))
        }
    }

    private fun resolveOrThrow(path: VPath): Pair<Uri, String> = resolve(path) ?: throw VfsException.NotFound(path)

    /** The child of [parentId] whose display name is [name], or null. One cursor, ids and names only. */
    private fun childId(tree: Uri, parentId: String, name: String): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (c.getString(1) == name) {
                    remember(tree, id, parentId, name)
                    return id
                }
            }
        }
        return null
    }

    /**
     * Which folder each document was listed in. A path here carries only the document's own id,
     * not its folder's, and a rename that has to be done as copy-then-delete needs the folder.
     * Anything renamed has been seen in a listing first, so this is filled by [list].
     */
    private val parents = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Each document's display name as last seen, so telling a document's own path from a sibling's costs no query. */
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun remember(tree: Uri, childId: String, parentId: String, name: String? = null) {
        if (parents.size > PARENTS_MAX) parents.clear()
        if (names.size > PARENTS_MAX) names.clear()
        parents["$tree|$childId"] = parentId
        if (name != null) names["$tree|$childId"] = name
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (tree, docId) = resolveOrThrow(path)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<VNode>()
        val cursor = context.contentResolver.query(childrenUri, COLS, null, null, null)
            ?: throw VfsException.AccessDenied(path)
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val iTime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getString(iId) ?: continue
                val name = c.getString(iName) ?: continue
                val isDir = c.getString(iMime) == DocumentsContract.Document.MIME_TYPE_DIR
                remember(tree, id, docId, name)
                out += VNode(
                    path = pathOf(tree, id, name),
                    isDir = isDir,
                    size = if (isDir) -1L else c.getLong(iSize),
                    mtime = c.getLong(iTime),
                    hidden = name.startsWith("."),
                )
            }
        }
        out
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (tree, docId) = resolve(path) ?: return@withContext null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(uri, COLS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            names["$tree|$docId"] = name
            val isDir = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
                DocumentsContract.Document.MIME_TYPE_DIR
            VNode(
                path = pathOf(tree, docId, name),
                isDir = isDir,
                size = if (isDir) -1L else c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)),
                mtime = c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)),
                hidden = name.startsWith("."),
            )
        }
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (tree, docId) = resolveOrThrow(path)
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            ?: throw VfsException.NotFound(path)
    }

    /**
     * Writing to a path that does not exist yet makes it first. A document provider has no "open
     * for writing and create" - the document has to be created and then opened - and every copy
     * writes to a name that is new in the folder it goes to.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val (tree, docId) = resolve(path) ?: parse(create(path, isDir = false).path)
        val mode = if (append) "wa" else "wt"
        context.contentResolver.openOutputStream(
            DocumentsContract.buildDocumentUriUsingTree(tree, docId), mode
        ) ?: throw VfsException.AccessDenied(path)
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (tree, parentId) = container(path)
        // The name as a person typed it: a name taken from another SAF path arrives encoded.
        val name = Uri.decode(path.name)
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(name)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            mime,
            name,
        ) ?: throw VfsException.AccessDenied(path)
        val id = DocumentsContract.getDocumentId(created)
        remember(tree, id, parentId, name)
        stat(pathOf(tree, id, name))
            ?: throw VfsException.Io(path, IllegalStateException("created then vanished"))
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (tree, docId) = resolveOrThrow(path)
        val ok = DocumentsContract.deleteDocument(
            context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        )
        if (!ok) throw VfsException.AccessDenied(path)
        // Its name goes, its folder stays: a path still naming it is now read by name in that folder.
        names.remove("$tree|$docId")
        Unit
    }

    /**
     * Rename, and when the other app's provider has no rename, copy under the new name and then
     * delete the original.
     *
     * Bug identified here: renaming inside another app's storage (Termux's home is the one seen)
     * failed while reading and deleting there worked. That provider implements create and delete
     * and not rename, which is allowed - rename is optional in the contract - so the platform's
     * call threw and the rename surfaced as access denied.
     *
     * Decision: files only. A file costs one copy, and the original is deleted only after the
     * copy has fully landed, so a failure part way leaves the original as it was. A folder would
     * be a recursive copy of everything under it, which is a move dressed as a rename and can run
     * for minutes; it is refused with a sentence instead.
     */
    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (tree, docId) = resolveOrThrow(path)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val attempt = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, newName) }
        val renamed = attempt.getOrNull()
        if (renamed != null) {
            val id = DocumentsContract.getDocumentId(renamed)
            names.remove("$tree|$docId")
            parents["$tree|$docId"]?.let { remember(tree, id, it, newName) }
            return@withContext stat(pathOf(tree, id, newName))
                ?: throw VfsException.Io(path, IllegalStateException("renamed then vanished"))
        }
        renameByCopy(path, tree, docId, newName)
    }

    private suspend fun renameByCopy(path: VPath, tree: Uri, docId: String, newName: String): VNode {
        val node = stat(path) ?: throw VfsException.NotFound(path)
        if (node.isDir) {
            throw VfsException.Unsupported("The app that owns this folder does not allow renaming folders in it.")
        }
        val parentId = parents["$tree|$docId"]
            ?: throw VfsException.Unsupported("The app that owns this file does not allow renaming it here.")
        if (childId(tree, parentId, newName) != null) throw VfsException.AlreadyExists(path.parent?.child(newName) ?: path)
        val resolver = context.contentResolver
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val made = DocumentsContract.createDocument(resolver, parentUri, mimeOf(newName), newName)
            ?: throw VfsException.AccessDenied(path)
        try {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId)).use { input ->
                resolver.openOutputStream(made, "wt").use { output ->
                    if (input == null || output == null) throw VfsException.AccessDenied(path)
                    input.copyTo(output, 64 * 1024)
                }
            }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, made) }
            throw e
        }
        if (!DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))) {
            // Both exist now. Say so rather than leave a silent duplicate.
            throw VfsException.Io(path, IllegalStateException("copied to $newName but the original could not be removed"))
        }
        names.remove("$tree|$docId")
        val id = DocumentsContract.getDocumentId(made)
        remember(tree, id, parentId, newName)
        return stat(pathOf(tree, id, newName))
            ?: throw VfsException.Io(path, IllegalStateException("renamed then vanished"))
    }

    /** A type from the extension, so a provider that files documents by type puts this in the right place. */
    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val SCHEME = "saf"

        /** How many document-to-folder links [parents] holds before it starts again. */
        private const val PARENTS_MAX = 50_000

        /**
         * The names under the document a path addresses, still encoded: everything after
         * `tree/docId/ownName`. Empty for a path that names a document itself.
         */
        internal fun childSegments(path: VPath): List<String> = path.segments.drop(3)

        /**
         * Whether `tree/docId/third` names the document or a sibling of it. Pure, because getting
         * it wrong deletes files: see [anchor].
         *
         * @param size how many parts the path has.
         * @param third its third part, decoded; null when there is none.
         * @param ownName the document's display name, or null when it no longer exists.
         */
        internal fun anchorOf(size: Int, third: String?, ownName: String?, isRoot: Boolean = false): SafAnchor = when {
            size < 3 || third == null -> SafAnchor.SELF
            ownName != null -> if (third == ownName) SafAnchor.SELF else SafAnchor.SIBLING
            // The top of a granted tree that gives no name: nothing beside it is inside the
            // grant, so the path can only mean the top itself.
            isRoot -> SafAnchor.SELF
            else -> SafAnchor.SIBLING
        }

        /**
         * A document URI for another app's DocumentsProvider, to hint the picker where to open.
         *
         * Here rather than in the app layer because `DocumentsContract` is a storage API and
         * R3 keeps those below the VFS - the check caught this exact thing when the Termux
         * integration was first written in `app/`. The app layer knows Termux's authority and
         * the path it wants; turning that into a content URI is this layer's job.
         */
        fun documentUriFor(authority: String, documentId: String): android.net.Uri =
            android.provider.DocumentsContract.buildDocumentUri(authority, documentId)

        private val COLS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * `saf:///<enc tree>/<enc docId>/<display name>`
         *
         * The display name is carried as the third segment so breadcrumbs and row labels read as
         * names, and so a path built beside a document can be told from the document's own: see
         * [anchorOf].
         */
        fun pathOf(tree: Uri, docId: String, name: String? = null): VPath {
            val enc = Uri.encode(tree.toString())
            val encId = Uri.encode(docId)
            val tail = name?.let { "/" + Uri.encode(it) } ?: ""
            return VPath(SCHEME, "/$enc/$encId$tail")
        }

        /**
         * The VPath for a `content://` document URI handed to us by another app.
         *
         * Lives here rather than at the call site because unpacking a document URI is SAF
         * knowledge, and R3 keeps SAF knowledge inside the SAF provider.
         *
         * @return null when the URI is a single document with no tree behind it - there is no
         *   stable VFS path for one of those, and the caller must copy it instead.
         */
        fun pathOfDocumentUri(uri: Uri): VPath? = runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            pathOf(uri, docId)
        }.getOrNull()

        fun parse(path: VPath): Pair<Uri, String> {
            val segs = path.segments
            if (segs.size < 2) throw VfsException.Unsupported("not a SAF path: $path")
            return Uri.parse(Uri.decode(segs[0])) to Uri.decode(segs[1])
        }
    }
}
