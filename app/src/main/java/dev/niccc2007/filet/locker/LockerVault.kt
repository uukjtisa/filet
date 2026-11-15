package dev.niccc2007.filet.locker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The Locker on disk, and whether it is open.
 *
 * ```
 * <app files>/locker/
 *   key        the file key, sealed under the password (and nothing else in clear)
 *   bio        the file key, sealed under a fingerprint-held key, when that is on
 *   index      every name, folder and origin, sealed under the file key
 *   files/     one sealed file per id, random names, nothing else
 * ```
 *
 * The folder is Filet's private storage, which no other app can read on a phone that is not
 * rooted. On one that is, anything with root can read it, and what it finds is the list above:
 * random names and sealed bytes. The password is the protection there, not the folder - which is
 * what the root warning at set-up says.
 *
 * Open means the file key is in memory. [lock] wipes it, drops the index and empties [scratch],
 * the private folder anything opened from the Locker was read into. A lock asked for while a copy
 * into or out of the Locker is running waits for the copy, so a lock never cuts a file in half.
 */
class LockerVault(
    private val dir: File,
    /** Where things opened from the Locker are read into; emptied at every lock. */
    val scratch: File,
    private val rounds: Int = LockerCrypto.ROUNDS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val keyFile = File(dir, "key")
    private val bioFile = File(dir, "bio")
    private val indexFile = File(dir, "index")
    private val blobs = File(dir, "files")

    private val gate = Any()
    @Volatile private var key: ByteArray? = null
    private var index: LockerIndex? = null

    private val _open = MutableStateFlow(false)

    /** Whether the Locker is open. Everything showing a Locker path redraws on this. */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Moves on every change to the index, for the card's count. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Streams into or out of the Locker that have not closed yet. */
    private var busy = 0
    private var lockWhenIdle = false

    /** Where things about to arrive came from, by their path in the Locker. See [expectOrigin]. */
    private val expected = ConcurrentHashMap<String, String>()

    /** Called after every lock, for the caches that hold Locker content in memory. */
    var onLocked: () -> Unit = {}

    /** Whether a Locker has been set up on this phone. */
    val made: Boolean get() = keyFile.isFile

    /** Sets the Locker up with [password]. Refuses when one exists: that would orphan its files. */
    fun create(password: CharArray) = synchronized(gate) {
        check(!made) { "a Locker already exists" }
        blobs.mkdirs()
        val k = LockerCrypto.newKey()
        writeKey(password, k)
        key = k
        index = LockerIndex()
        saveIndex()
        _open.value = true
    }

    /** Opens with [password]. False when it is wrong. */
    fun unlock(password: CharArray): Boolean {
        val k = keyFrom(password) ?: return false
        return unlockWith(k)
    }

    /**
     * Opens with the file key itself, as a fingerprint hands it back. False when it is not this
     * Locker's key - checked by opening the index, which only the right key can.
     */
    fun unlockWith(fileKey: ByteArray): Boolean = synchronized(gate) {
        val sealed = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
        val plain = LockerCrypto.open(fileKey, sealed, INDEX_AAD) ?: return false
        index = LockerIndex.decode(plain)
        key = fileKey.copyOf()
        lockWhenIdle = false
        _open.value = true
        true
    }

    /** Locks now, or as soon as the copy running in or out has finished. */
    fun lock() {
        synchronized(gate) {
            if (key == null) return
            if (busy > 0) {
                lockWhenIdle = true
                return
            }
            lockNow()
        }
        onLocked()
    }

    private fun lockNow() {
        key?.fill(0)
        key = null
        index = null
        expected.clear()
        lockWhenIdle = false
        scratch.deleteRecursively()
        _open.value = false
    }

    /** Whether something is being copied in or out right now, which holds a lock off. */
    val inUse: Boolean get() = synchronized(gate) { busy > 0 }

    /** True when [old] is right; the files are untouched, only the sealed key is written again. */
    fun changePassword(old: CharArray, new: CharArray): Boolean {
        val k = keyFrom(old) ?: return false
        synchronized(gate) { writeKey(new, k) }
        k.fill(0)
        return true
    }

    /** A copy of the file key while open, for sealing under a fingerprint. Null when locked. */
    fun fileKey(): ByteArray? = key?.copyOf()

    fun saveBio(sealed: ByteArray) = writeAtomically(bioFile, sealed)

    fun bio(): ByteArray? = bioFile.takeIf { it.isFile }?.readBytes()

    fun clearBio() {
        bioFile.delete()
    }

    /** Reads the index while open. Throws [Locked] when it is not. */
    fun <T> read(block: (LockerIndex) -> T): T = synchronized(gate) { block(index ?: throw Locked()) }

    /** Changes the index while open and writes it, sealed, before returning. */
    fun <T> change(block: (LockerIndex) -> T): T = synchronized(gate) {
        val i = index ?: throw Locked()
        val out = block(i)
        saveIndex()
        out
    }

    /** The plain bytes of blob [id], from [offset]. */
    fun openBlob(id: String, offset: Long = 0): InputStream {
        val k = key ?: throw Locked()
        val raw = FileInputStream(File(blobs, id))
        return try {
            held(if (offset == 0L) LockerCrypto.decrypting(k, raw) else LockerCrypto.decryptingAt(k, raw, offset))
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
    }

    /**
     * A new sealed blob. [done] hears its id and plain size once the stream is closed, and only
     * then: a stream abandoned half way leaves a `.part` that the next open sweeps away.
     */
    fun newBlob(done: (id: String, size: Long) -> Unit): OutputStream {
        val k = key ?: throw Locked()
        blobs.mkdirs()
        val id = LockerCrypto.randomBytes(16).joinToString("") { "%02x".format(it) }
        val part = File(blobs, "$id.part")
        val sealing = LockerCrypto.encrypting(k, FileOutputStream(part))
        var size = 0L
        val counting = object : FilterOutputStream(sealing) {
            override fun write(b: Int) { sealing.write(b); size++ }
            override fun write(b: ByteArray, off: Int, len: Int) { sealing.write(b, off, len); size += len }
            override fun close() {
                sealing.close()
                if (!part.renameTo(File(blobs, id))) {
                    part.delete()
                    throw java.io.IOException("could not keep the file in the Locker")
                }
                done(id, size)
            }
        }
        return held(counting)
    }

    fun deleteBlobs(ids: Collection<String>) {
        for (id in ids) File(blobs, id).delete()
    }

    /** The plain size of blob [id] as it is on disk, without opening it. */
    fun blobSize(id: String): Long = LockerCrypto.plainSize(File(blobs, id).length())

    /** [origin] is about to arrive at [lockerPath]: it is noted on the entry when it does. */
    fun expectOrigin(lockerPath: String, origin: String) {
        expected[LockerIndex.norm(lockerPath)] = origin
    }

    /** The origin noted for [lockerPath], taken so it is used once. */
    fun takeOrigin(lockerPath: String): String? = expected.remove(LockerIndex.norm(lockerPath))

    /** Bytes free where the Locker keeps its files. */
    fun free(): Long = dir.usableSpace

    fun total(): Long = dir.totalSpace

    /** Clears blobs left half written by a copy that was cut off. Called at unlock. */
    fun sweep() {
        blobs.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val known = runCatching { read { i -> i.files().map { it.blob }.toSet() } }.getOrNull() ?: return
        blobs.listFiles()?.filter { !it.name.endsWith(".part") && it.name !in known }?.forEach { it.delete() }
    }

    private fun keyFrom(password: CharArray): ByteArray? {
        val raw = runCatching { keyFile.readBytes() }.getOrNull() ?: return null
        val (r, salt, sealed) = DataInputStream(ByteArrayInputStream(raw)).use { i ->
            require(i.readInt() == KEY_MAGIC) { "not a Locker key" }
            require(i.readInt() == 1) { "Locker key version" }
            val r = i.readInt()
            val salt = ByteArray(i.readInt()).also { i.readFully(it) }
            val sealed = ByteArray(i.readInt()).also { i.readFully(it) }
            Triple(r, salt, sealed)
        }
        val derived = LockerCrypto.derive(password, salt, r)
        return try {
            LockerCrypto.open(derived, sealed, KEY_AAD)
        } finally {
            derived.fill(0)
        }
    }

    private fun writeKey(password: CharArray, fileKey: ByteArray) {
        val salt = LockerCrypto.randomBytes(16)
        val derived = LockerCrypto.derive(password, salt, rounds)
        val sealed = LockerCrypto.seal(derived, fileKey, KEY_AAD)
        derived.fill(0)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeInt(KEY_MAGIC)
            o.writeInt(1)
            o.writeInt(rounds)
            o.writeInt(salt.size)
            o.write(salt)
            o.writeInt(sealed.size)
            o.write(sealed)
        }
        writeAtomically(keyFile, out.toByteArray())
    }

    private fun saveIndex() {
        val k = key ?: throw Locked()
        val i = index ?: throw Locked()
        writeAtomically(indexFile, LockerCrypto.seal(k, i.encode(), INDEX_AAD))
        _revision.value++
    }

    /** Written beside and renamed, so a phone that dies mid-write keeps the old one whole. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        dir.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
        if (!part.renameTo(target)) {
            part.delete()
            throw java.io.IOException("could not write ${target.name}")
        }
    }

    private fun begin() = synchronized(gate) { busy++ }

    private fun end() {
        val now = synchronized(gate) {
            busy--
            if (busy == 0 && lockWhenIdle) {
                lockNow()
                true
            } else {
                false
            }
        }
        if (now) onLocked()
    }

    private fun held(input: InputStream): InputStream {
        begin()
        return object : java.io.FilterInputStream(input) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try { super.close() } finally { end() }
            }
        }
    }

    private fun held(output: OutputStream): OutputStream {
        begin()
        return object : FilterOutputStream(output) {
            private var closed = false
            override fun write(b: Int) = output.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try { output.close() } finally { end() }
            }
        }
    }

    /** The Locker is locked; open it first. */
    class Locked : java.io.IOException("the Locker is locked")

    companion object {
        /**
         * The phone's Locker. [scratch] is emptied here as well as at each lock: a process that
         * died while the Locker was open left whatever it had read out, and nothing in it is
         * wanted by the next one.
         */
        fun at(context: android.content.Context): LockerVault {
            val scratch = dev.niccc2007.filet.data.FiletStore.privateRoot(context)
            scratch.deleteRecursively()
            return LockerVault(File(context.filesDir, "locker"), scratch)
        }

        /**
         * Whether this phone looks rooted, for the warning beside the password. A file check
         * rather than asking the superuser shell, because asking raises its prompt and this runs
         * whenever the set-up form is drawn. It can miss a well-hidden root; the warning is a
         * courtesy, not the protection.
         */
        fun rooted(): Boolean = ROOT_MARKS.any { runCatching { File(it).exists() }.getOrDefault(false) }

        private val ROOT_MARKS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk",
        )

        private const val KEY_MAGIC = 0x464c4b4b // "FLKK"
        private val KEY_AAD = "filet-locker-key".toByteArray()
        private val INDEX_AAD = "filet-locker-index".toByteArray()
    }
}
