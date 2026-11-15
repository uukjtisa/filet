package dev.niccc2007.filet.docs

import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Puts a password on a PDF, changes it, or takes it off.
 *
 * Locking writes the whole file again, every string and stream encrypted with AES-256 the way
 * the PDF standard's revision 6 says, with a fresh key, fresh salts and a fresh IV for each
 * string and stream. Changing is opening the old lock and writing the new one in the same pass;
 * removing is [PdfUnlock]'s plain copy. Nothing here touches the file on disk: the caller gets
 * the new bytes in an output stream and decides where they go.
 *
 * Decision: AES-256 only, revision 6. The older ways to lock a PDF (RC4, AES-128, revision 5)
 * are all broken or weakened, and every reader from the last decade opens revision 6.
 *
 * Decision: the owner password is the same as the one typed, and every permission is allowed.
 * The lock is about who can open the file; a printing or copying restriction is something any
 * reader may ignore, and offering one would promise more than it keeps.
 *
 * Decision: a full rewrite rather than an incremental update. An update appended to the end
 * leaves the old, unencrypted objects in the file for anyone to read past the lock.
 */
object PdfLock {

    sealed interface Result {
        /** The output holds the whole file, locked or unlocked as asked. */
        data object Written : Result

        /** The file's current lock did not open with the password given for it. */
        data object WrongPassword : Result

        data class Unsupported(val why: String) : Result
    }

    /**
     * Write [src] to [out] locked with [password]. A file that is locked already is opened with
     * [current] first - an empty one for a file locked only against changes.
     */
    fun lock(src: PdfSource, password: String, out: OutputStream, current: String? = null): Result = guarded {
        Unlocker(Bytes(src)).relock(current.orEmpty(), { id -> Lock(password, id) }, out)
    }

    /** Write [src] to [out] with no password at all, opening it with [current]. */
    fun remove(src: PdfSource, current: String, out: OutputStream): Result = when (val r = PdfUnlock.unlock(src, current, out)) {
        PdfUnlock.Result.Unlocked -> Result.Written
        PdfUnlock.Result.WrongPassword -> Result.WrongPassword
        PdfUnlock.Result.NotLocked -> Result.Unsupported("This PDF has no password to remove.")
        is PdfUnlock.Result.Unsupported -> Result.Unsupported(r.why)
    }

    /**
     * Whether a written file is what was asked for: with [password], every object in it reads
     * and decrypts with that password and no other; with none, it is not locked and its catalog
     * reads. The last check before the file replaces the one on disk.
     */
    fun verify(src: PdfSource, password: String?): Boolean = runCatching {
        if (password != null) {
            PdfUnlock.unlock(src, password, Nowhere) == PdfUnlock.Result.Unlocked &&
                PdfUnlock.unlock(src, password + "\u0000", Nowhere) == PdfUnlock.Result.WrongPassword
        } else {
            val u = Unlocker(Bytes(src))
            if (u.encryptDict() != null) return@runCatching false
            val root = u.trailer?.get("Root") as? PRef ?: return@runCatching false
            (u.load(root.num)?.value as? PDict)?.name("Type") == "Catalog"
        }
    }.getOrDefault(false)

    /** Somewhere to write a check's output to; `OutputStream.nullOutputStream` is API 33. */
    private object Nowhere : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private inline fun guarded(block: () -> Result): Result = try {
        block()
    } catch (e: OutOfMemoryError) {
        Result.Unsupported("This PDF is too large to lock on this phone.")
    } catch (e: PdfUnlock.Unreadable) {
        Result.Unsupported(e.message ?: "This PDF is damaged.")
    }
}

/**
 * One new AES-256 lock: the file key, the entry a reader checks a password against, and the
 * encryption of each string and stream.
 *
 * [id] is the file's own identifier when it has one; revision 6 does not mix it into the key,
 * so keeping it costs nothing and keeps the file the same file to anything that tracks it.
 */
internal class Lock(password: String, id: PArr?, private val random: SecureRandom = SecureRandom()) {
    private val key = bytes(32)
    val id: PArr = id ?: bytes(16).let { PArr(mutableListOf(PStr(it), PStr(it.copyOf()))) }
    val dict: PDict

    init {
        val pw = Security.utf8Password(password)
        val none = ByteArray(0)
        val uSalt = bytes(8)
        val uKeySalt = bytes(8)
        val u = Security.hash(pw, uSalt, none, 6) + uSalt + uKeySalt
        val ue = wrap(Security.hash(pw, uKeySalt, none, 6), key)
        val oSalt = bytes(8)
        val oKeySalt = bytes(8)
        val o = Security.hash(pw, oSalt, u, 6) + oSalt + oKeySalt
        val oe = wrap(Security.hash(pw, oKeySalt, u, 6), key)
        // The permissions again, sealed with the file key, so they cannot be edited unnoticed.
        val perms = byteArrayOf(
            (P and 0xFF).toByte(), ((P shr 8) and 0xFF).toByte(), ((P shr 16) and 0xFF).toByte(), ((P shr 24) and 0xFF).toByte(),
            -1, -1, -1, -1, 'T'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte(),
        ) + bytes(4)
        val sealedPerms = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(perms)
        }
        val filter = linkedMapOf<String, PObj>("AuthEvent" to PName("DocOpen"), "CFM" to PName("AESV3"), "Length" to PNum("32"))
        dict = PDict(
            linkedMapOf<String, PObj>(
                "Filter" to PName("Standard"),
                "V" to PNum("5"),
                "R" to PNum("6"),
                "Length" to PNum("256"),
                "P" to PNum(P.toString()),
                "EncryptMetadata" to PKw("true"),
                "O" to PStr(o),
                "U" to PStr(u),
                "OE" to PStr(oe),
                "UE" to PStr(ue),
                "Perms" to PStr(sealedPerms),
                "CF" to PDict(linkedMapOf<String, PObj>("StdCF" to PDict(filter))),
                "StmF" to PName("StdCF"),
                "StrF" to PName("StdCF"),
            ),
        )
    }

    /** Encrypt every string in [o], in place. */
    fun strings(o: PObj) {
        when (o) {
            is PStr -> o.bytes = seal(o.bytes)
            is PArr -> o.items.forEach { strings(it) }
            is PDict -> o.map.values.forEach { strings(it) }
            else -> {}
        }
    }

    /** One string or stream: a random IV in front, then AES-256-CBC with PKCS#7 padding. */
    fun seal(data: ByteArray): ByteArray {
        val iv = bytes(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    /** The file key under a password's hash: AES-256-CBC, zero IV, no padding, as UE and OE are. */
    private fun wrap(k: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16)))
        return c.doFinal(data)
    }

    companion object {
        /** Every permission granted; the two lowest bits must be clear. */
        const val P = -4

        /** A stream with its own crypt filter is left as its writer meant it. */
        fun leaves(d: PDict): Boolean {
            val f = d["Filter"]
            return (f as? PName)?.raw == "Crypt" || (f as? PArr)?.items?.any { (it as? PName)?.raw == "Crypt" } == true
        }
    }
}
