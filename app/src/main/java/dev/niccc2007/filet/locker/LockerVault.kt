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
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The Locker on disk, and whether it is open.
 *
 * ```
 * <app files>/locker/
 *   key        the file key, sealed under the password; the rounds and salt beside it in clear
 *   key.bak    the same, kept so one damaged or missing file does not cost every sealed file
 *   bio        the file key, sealed under a fingerprint-held key, when that is on
 *   index      every name, folder and origin, sealed under the file key
 *   index.bak  the index as it was at the last unlock that read it whole
 *   files/     one sealed file per id, random names, nothing else
 * ```
 *
 * The folder is Filet's private storage, which no other app can read on a phone that is not
 * rooted. On one that is, anything with root can read it, and what it finds is the list above:
 * random names and sealed bytes. The password is the protection there, not the folder - which is
 * what the root warning at set-up says.
 *
 * Open means the file key is in memory. [lock] wipes it, drops the index, empties [scratch] - the
 * private folder anything opened from the Locker was read into - and cuts off every file still
 * being read out. A copy into or out of the Locker is let finish first ([hold]), so a lock never
 * leaves a file cut in half at either end; until it does, [pending] says so.
 *
 * Nothing here deletes a sealed file it cannot account for unless the index it holds is the
 * current one. See [health] and [sweep].
 */
class LockerVault(
    private val dir: File,
    /** Where things opened from the Locker are read into; emptied at every lock. */
    val scratch: File,
    private val rounds: Int = LockerCrypto.ROUNDS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val keyFile = File(dir, "key")
    private val keyBak = File(dir, "key.bak")
    private val bioFile = File(dir, "bio")
    private val indexFile = File(dir, "index")
    private val indexBak = File(dir, "index.bak")
    private val blobs = File(dir, "files")

    private val gate = Any()
    @Volatile private var key: ByteArray? = null
    private var index: LockerIndex? = null

    private val _open = MutableStateFlow(false)

    /** Whether the Locker is open. Everything showing a Locker path redraws on this. */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _pending = MutableStateFlow(false)

    /** A lock was asked for while a copy into the Locker runs: it locks when the copy ends. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Moves on every change to the index, for the card's count. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Copies into or out of the Locker that have not finished. They hold a lock off. */
    private var busy = 0
    private var lockWhenIdle = false

    /** Files being read out of the Locker. A lock cuts them; on their own they never hold it off. */
    private val readers = HashSet<Reader>()

    /** Where things about to arrive came from, by their path in the Locker. See [expectOrigin]. */
    private val expected = ConcurrentHashMap<String, String>()

    /**
     * The index this open came from was not the current one - the copy, or one rebuilt from the
     * sealed files - so [sweep] keeps what it does not know of instead of deleting it.
     */
    private var adopt = false

    /** Called after every lock, for the caches that hold Locker content in memory. */
    var onLocked: () -> Unit = {}

    /** Why the last [unlock] that failed did. */
    enum class Failure { WRONG_PASSWORD, KEY_DAMAGED }

    @Volatile var failure: Failure? = null
        private set

    /** Said once after an unlock that had to mend something; see [takeNotice]. */
    @Volatile private var notice: String? = null

    /** What is on disk, read before the set-up form or the password is offered. */
    enum class Health {
        /** No Locker on this phone. */
        NONE,
        OK,

        /**
         * Sealed files with no key file to open them. Setting a new Locker up over them would
         * orphan them for good, so it is refused until they are erased by hand.
         */
        KEY_MISSING,
    }

    /**
     * What is on disk. A key file that has gone is put back from its copy first.
     *
     * Bug identified here: with the key file gone, [made] read false, the set-up form came up,
     * and the first unlock of the new Locker ran [sweep] - which deleted every sealed file of the
     * old one as unknown. A missing file cost everything, silently.
     */
    fun health(): Health = synchronized(gate) {
        if (!keyFile.isFile && keyBak.isFile) runCatching { writeAtomically(keyFile, keyBak.readBytes()) }
        when {
            keyFile.isFile -> Health.OK
            sealedCount() > 0 -> Health.KEY_MISSING
            else -> Health.NONE
        }
    }

    /** Whether a Locker has been set up on this phone. */
    val made: Boolean get() = health() == Health.OK

    /** Sealed files on disk, whether the index knows them or not. */
    fun sealedCount(): Int = blobs.listFiles()?.count { !it.name.endsWith(".part") } ?: 0

    /**
     * Sets the Locker up with [password]. Refuses when one exists, and when sealed files of an
     * earlier one are still here: either way the new key would orphan them.
     */
    fun create(password: CharArray) = synchronized(gate) {
        check(!keyFile.isFile) { "a Locker already exists" }
        check(sealedCount() == 0) { "sealed files from an earlier Locker are still here" }
        blobs.mkdirs()
        // A fingerprint key sealed for an earlier Locker opens nothing here.
        bioFile.delete()
        val k = LockerCrypto.newKey()
        writeKey(password, k)
        key = k
        index = LockerIndex()
        adopt = false
        saveIndex()
        _open.value = true
    }

    /**
     * Erases the Locker: the key, its copy, the index and every sealed file. Only ever called
     * after the word in [ERASE_WORD] has been typed; nothing calls it on its own.
     */
    fun erase() {
        lock()
        synchronized(gate) {
            dir.deleteRecursively()
            _revision.value++
        }
    }

    /** Opens with [password]. False when it is wrong; [failure] says why. */
    fun unlock(password: CharArray): Boolean {
        val k = try {
            keyFrom(password)
        } catch (e: IllegalArgumentException) {
            failure = Failure.KEY_DAMAGED
            return false
        } ?: run {
            failure = Failure.WRONG_PASSWORD
            return false
        }
        return try {
            // The password is right, so a list that will not open is damaged, not a wrong guess.
            openIndex(k, keyKnown = true)
        } finally {
            k.fill(0)
        }
    }

    /**
     * Opens with the file key itself, as a fingerprint hands it back. False when it is not this
     * Locker's key - checked by opening the index, which only the right key can.
     */
    fun unlockWith(fileKey: ByteArray): Boolean = openIndex(fileKey, keyKnown = false)

    /**
     * Opens the index under [fileKey]: the file, else its copy, else - only when the key is known
     * to be right - a new one holding every sealed file under Recovered.
     *
     * Bug identified here: a damaged index read as "That is not the password", so a right password
     * looked wrong forever and nothing pointed at the real fault.
     */
    private fun openIndex(fileKey: ByteArray, keyKnown: Boolean): Boolean = synchronized(gate) {
        val current = sealedIndex(indexFile, fileKey)
        val opened: LockerIndex = when {
            current != null -> {
                adopt = false
                // The copy is the last index read whole.
                runCatching { writeAtomically(indexBak, indexFile.readBytes()) }
                current
            }
            else -> {
                val copy = sealedIndex(indexBak, fileKey)
                when {
                    copy != null -> {
                        adopt = true
                        notice = "The Locker's list was damaged and its copy was used. Files added since then are under Recovered."
                        copy.also { dropMissing(it) }
                    }
                    keyKnown -> {
                        adopt = true
                        if (indexFile.isFile) runCatching { indexFile.renameTo(File(dir, "index.damaged")) }
                        notice = "The Locker's list was damaged. Its files are under Recovered, without their names."
                        LockerIndex()
                    }
                    else -> return false
                }
            }
        }
        index = opened
        key = fileKey.copyOf()
        lockWhenIdle = false
        _pending.value = false
        failure = null
        _open.value = true
        if (adopt) {
            adoptUnknown()
            saveIndex()
        }
        true
    }

    /** What to say after an unlock that mended something, once; null when it mended nothing. */
    fun takeNotice(): String? = notice.also { notice = null }

    /** Locks now, or as soon as the copy running in or out has finished. */
    fun lock() {
        val cut = synchronized(gate) {
            if (key == null) return
            if (busy > 0) {
                lockWhenIdle = true
                _pending.value = true
                return
            }
            lockNow()
        }
        cut.forEach { it.cutNow() }
        onLocked()
    }

    /** Wipes the key and returns the readers to cut, which is done outside the gate. */
    private fun lockNow(): List<Reader> {
        key?.fill(0)
        key = null
        index = null
        expected.clear()
        lockWhenIdle = false
        _pending.value = false
        scratch.deleteRecursively()
        _open.value = false
        return readers.toList().also { readers.clear() }
    }

    /** Whether something is being copied in or out right now, which holds a lock off. */
    val inUse: Boolean get() = synchronized(gate) { busy > 0 }

    /**
     * Holds the Locker open for a copy into or out of it, from its first file to its last; close
     * it when the copy ends. Null when the Locker is locked.
     *
     * Decision: a whole batch, not a file at a time. Held per file, a lock asked for in the middle
     * of a folder landed between two files and the rest of the folder failed as locked; and a copy
     * out reads, and reads are otherwise cut by a lock.
     */
    fun hold(): AutoCloseable? = synchronized(gate) {
        if (key == null) return null
        busy++
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        AutoCloseable { if (once.compareAndSet(false, true)) end() }
    }

    /** True when [old] is right; the files are untouched, only the sealed key is written again. */
    fun changePassword(old: CharArray, new: CharArray): Boolean {
        val k = runCatching { keyFrom(old) }.getOrNull() ?: return false
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

    /** The plain bytes of blob [id], from [offset]. Cut off by the next [lock]. */
    fun openBlob(id: String, offset: Long = 0): InputStream {
        val k = key ?: throw Locked()
        val raw = FileInputStream(File(blobs, id))
        val plain = try {
            if (offset == 0L) LockerCrypto.decrypting(k, raw) else LockerCrypto.decryptingAt(k, raw, offset)
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
        val r = Reader(plain, raw)
        val kept = synchronized(gate) { if (key != null) readers.add(r) else false }
        if (!kept) {
            r.close()
            throw Locked()
        }
        return r
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

    /**
     * Clears blobs left half written by a copy that was cut off. Called at unlock. A sealed file
     * the index does not know is deleted only when the index is the current one - there it is a
     * file whose removal did not finish. Under an index from the copy it may be a file added since,
     * and it is kept under Recovered instead (see [openIndex]).
     */
    fun sweep() {
        blobs.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        if (synchronized(gate) { adopt }) return
        val known = runCatching { read { i -> i.files().map { it.blob }.toSet() } }.getOrNull() ?: return
        blobs.listFiles()?.filter { !it.name.endsWith(".part") && it.name !in known }?.forEach { it.delete() }
    }

    /** Every sealed file the index does not know, put under Recovered with a plain name. */
    private fun adoptUnknown() {
        val i = index ?: return
        val known = i.files().map { it.blob }.toSet()
        val unknown = blobs.listFiles()?.filter { !it.name.endsWith(".part") && it.name !in known }.orEmpty()
        if (unknown.isEmpty()) return
        // A file of the owner's already called Recovered is left alone: the folder takes a number.
        val folder = generateSequence(1) { it + 1 }
            .map { if (it == 1) RECOVERED else "$RECOVERED $it" }
            .first { i.get(it)?.dir != false }
        if (i.get(folder) == null) i.put(LockerIndex.Entry(folder, dir = true, mtime = now(), added = now()))
        var n = 0
        for (f in unknown.sortedBy { it.lastModified() }) {
            // Never over a name already there: put replaces, and that would lose the entry.
            do n++ while (i.get(LockerIndex.childOf(folder, "Recovered file $n")) != null)
            i.put(
                LockerIndex.Entry(
                    LockerIndex.childOf(folder, "Recovered file $n"),
                    dir = false,
                    blob = f.name,
                    size = LockerCrypto.plainSize(f.length()),
                    mtime = f.lastModified(),
                    added = now(),
                ),
            )
        }
    }

    /** Entries of an older index whose sealed file has gone since: nothing would open. */
    private fun dropMissing(i: LockerIndex) {
        for (e in i.files()) if (!File(blobs, e.blob).isFile) i.remove(e.path)
    }

    private fun sealedIndex(file: File, fileKey: ByteArray): LockerIndex? {
        val sealed = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plain = LockerCrypto.open(fileKey, sealed, INDEX_AAD) ?: return null
        return runCatching { LockerIndex.decode(plain) }.getOrNull()
    }

    /**
     * The file key under [password], from the key file or its copy. Null when the password is
     * wrong; throws [IllegalArgumentException] when neither file can be read as a key at all.
     */
    private fun keyFrom(password: CharArray): ByteArray? {
        var damaged = 0
        for (f in listOf(keyFile, keyBak)) {
            val raw = runCatching { f.readBytes() }.getOrNull() ?: continue
            val parts = runCatching { parseKey(raw) }.getOrNull()
            if (parts == null) {
                damaged++
                continue
            }
            val (r, salt, sealed) = parts
            val derived = LockerCrypto.derive(password, salt, r)
            try {
                // The first readable file decides: the copy is the same key, so a wrong password
                // is wrong for both and is not stretched twice.
                return LockerCrypto.open(derived, sealed, KEY_AAD)
            } finally {
                derived.fill(0)
            }
        }
        require(damaged == 0) { "the Locker's key file is damaged" }
        return null
    }

    private fun parseKey(raw: ByteArray): Triple<Int, ByteArray, ByteArray> = DataInputStream(ByteArrayInputStream(raw)).use { i ->
        require(i.readInt() == KEY_MAGIC) { "not a Locker key" }
        require(i.readInt() == 1) { "Locker key version" }
        val r = i.readInt()
        require(r in 1..100_000_000) { "Locker key rounds" }
        val salt = ByteArray(i.readInt().also { require(it in 1..1024) }).also { i.readFully(it) }
        val sealed = ByteArray(i.readInt().also { require(it in 1..4096) }).also { i.readFully(it) }
        Triple(r, salt, sealed)
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
        val bytes = out.toByteArray()
        writeAtomically(keyFile, bytes)
        writeAtomically(keyBak, bytes)
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
        val cut = synchronized(gate) {
            busy--
            if (busy == 0 && lockWhenIdle) lockNow() else null
        } ?: return
        cut.forEach { it.cutNow() }
        onLocked()
    }

    /**
     * A file being read out of the Locker, which a lock cuts off.
     *
     * Bug identified here: every reader held the lock off exactly as a copy in did. A Locker track
     * playing in the background, or a player paused on a Locker video, kept the key in memory and
     * the Locker reading Open after Lock was pressed, for as long as it stayed open - and a stalled
     * connection could keep it open indefinitely. A read for viewing has nothing to lose by being
     * cut; a copy out holds the Locker for itself through [hold].
     */
    private inner class Reader(input: InputStream, private val raw: InputStream) : FilterInputStream(input) {
        @Volatile private var cut = false
        private var closed = false

        fun cutNow() {
            cut = true
            runCatching { raw.close() }
        }

        private fun live() {
            if (cut) throw Locked()
        }

        override fun read(): Int {
            live()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            live()
            return super.read(b, off, len)
        }

        override fun skip(n: Long): Long {
            live()
            return super.skip(n)
        }

        override fun available(): Int {
            live()
            return super.available()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                synchronized(gate) { readers.remove(this) }
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
        /** Typed to erase a Locker that cannot be opened. A word, so it cannot be tapped by accident. */
        const val ERASE_WORD = "erase"

        /** Whether [typed] is the erase word, however it is cased or spaced. */
        fun eraseConfirmed(typed: String): Boolean = typed.trim().equals(ERASE_WORD, ignoreCase = true)

        /** Where files the index did not know are put back, by an unlock that mended the index. */
        const val RECOVERED = "/Recovered"

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
