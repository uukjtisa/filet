package dev.niccc2007.filet.docs

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The files beside one file, as much of a filesystem as [SafeReplace] needs.
 *
 * An interface so the replace protocol can be tested against a store that fails on cue - a
 * write that dies halfway, a rename the backend refuses - which is the whole point of it.
 */
internal interface SiblingStore {
    suspend fun exists(name: String): Boolean
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun read(name: String): ByteArray
    suspend fun rename(from: String, to: String)
    suspend fun delete(name: String)
}

/**
 * Replace a file's bytes so that no failure leaves it truncated or gone.
 *
 * ## The protocol
 *
 * 1. Write the new bytes to a hidden sibling. A failure here has not touched the original.
 * 2. Read the sibling BACK and check it - byte-for-byte against what was written, then the
 *    caller's own check (for an office file: the zip opens and holds the edit). A backend that
 *    accepted a write and stored something else is caught here, original still untouched.
 * 3. Rename the original aside to a second hidden sibling.
 * 4. Rename the new file into the original's name. If THAT fails, the original is renamed back.
 * 5. Delete the set-aside original.
 *
 * Writing straight over the file - what the text editor's save does - truncates it first, so a
 * write that fails after the first byte destroys the document; for a zip, any truncation is all
 * of it. Between 3 and 4 the original exists only under its hidden name, so a process killed in
 * that instant leaves it recoverable rather than lost - the one window a provider-agnostic
 * replace cannot close, since not every backend has an atomic rename-over.
 *
 * The temporary names keep the original's extension last, because some document providers
 * append an extension to a name that does not end in the one its type expects.
 */
internal object SafeReplace {

    fun tempName(name: String) = ".filet-saving-$name"

    fun asideName(name: String) = ".filet-original-$name"

    suspend fun replace(store: SiblingStore, name: String, bytes: ByteArray, verify: (ByteArray) -> Unit) {
        val temp = tempName(name)
        val aside = asideName(name)
        if (store.exists(temp)) store.delete(temp)

        try {
            store.write(temp, bytes)
            val back = store.read(temp)
            if (!back.contentEquals(bytes)) error("The saved copy did not read back as written.")
            verify(back)
        } catch (t: Throwable) {
            runCatching { store.delete(temp) }
            throw t
        }

        if (store.exists(aside)) store.delete(aside)
        try {
            store.rename(name, aside)
        } catch (t: Throwable) {
            runCatching { store.delete(temp) }
            throw t
        }
        try {
            store.rename(temp, name)
        } catch (t: Throwable) {
            val restored = runCatching { store.rename(aside, name) }.isSuccess
            if (!restored) {
                throw IllegalStateException("Saving failed and the original is kept as $aside beside it.", t)
            }
            runCatching { store.delete(temp) }
            throw t
        }
        // The edit is in place. A set-aside copy that will not delete is harmless, and is not a
        // reason to report a save that worked as one that failed.
        runCatching { store.delete(aside) }
    }
}

/** [SiblingStore] over the app's own filesystem, for the folder holding the file. */
internal class VfsSiblings(private val vfs: Vfs, private val folder: VPath) : SiblingStore {
    override suspend fun exists(name: String) = vfs.stat(folder.child(name)) != null
    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        vfs.openWrite(folder.child(name)).use { it.write(bytes) }
    }
    override suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
        vfs.openRead(folder.child(name)).use { it.readBytes() }
    }
    override suspend fun rename(from: String, to: String) {
        val renamed = vfs.rename(folder.child(from), to)
        // A backend that renamed to something else has not done what was asked, and the next
        // step would act on a name that does not exist. Put it back before saying so.
        if (renamed.name != to) {
            runCatching { vfs.rename(renamed.path, from) }
            error("The file was renamed to ${renamed.name} instead of $to.")
        }
    }
    override suspend fun delete(name: String) = vfs.delete(folder.child(name))
}
