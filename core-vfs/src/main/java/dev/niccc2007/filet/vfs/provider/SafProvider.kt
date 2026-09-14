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

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (tree, docId) = parse(path)
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
        val (tree, docId) = parse(path)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(uri, COLS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
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
        val (tree, docId) = parse(path)
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            ?: throw VfsException.NotFound(path)
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val (tree, docId) = parse(path)
        val mode = if (append) "wa" else "wt"
        context.contentResolver.openOutputStream(
            DocumentsContract.buildDocumentUriUsingTree(tree, docId), mode
        ) ?: throw VfsException.AccessDenied(path)
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val parent = path.parent ?: throw VfsException.Unsupported("cannot create a volume root")
        val (tree, parentId) = parse(parent)
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            mime,
            path.name,
        ) ?: throw VfsException.AccessDenied(path)
        stat(pathOf(tree, DocumentsContract.getDocumentId(created), path.name))
            ?: throw VfsException.Io(path, IllegalStateException("created then vanished"))
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (tree, docId) = parse(path)
        val ok = DocumentsContract.deleteDocument(
            context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        )
        if (!ok) throw VfsException.AccessDenied(path)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (tree, docId) = parse(path)
        val renamed = DocumentsContract.renameDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            newName,
        ) ?: throw VfsException.AccessDenied(path)
        stat(pathOf(tree, DocumentsContract.getDocumentId(renamed), newName))
            ?: throw VfsException.Io(path, IllegalStateException("renamed then vanished"))
    }

    companion object {
        const val SCHEME = "saf"

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
         * The display name is carried as the last segment purely so breadcrumbs and row labels
         * read as names rather than as document ids; only the first two segments are addressed.
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
