package dev.niccc2007.filet.locker

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Locker's encryption: what a locked file looks like on disk, and how the key is kept.
 *
 * ## Why chunks, and not one stream
 *
 * AES-GCM over a whole file is the obvious shape and it fails on Android in a way that only shows
 * on a big file: the platform's GCM decrypt holds back every byte until it has checked the tag at
 * the very end, so opening a 2 GB video in the Locker would mean 2 GB in memory before the first
 * frame. A file here is a run of chunks of [CHUNK] bytes, each sealed on its own, so a reader holds
 * one chunk at a time and a player can start at any chunk without reading the ones before it.
 *
 * Each chunk's nonce is the file's random prefix, the chunk's number and a flag on the last one,
 * and the file's header is bound into every chunk. So a chunk cannot be moved to another place in
 * the file, swapped in from another file, or the file cut short at a chunk boundary, without the
 * read failing - the three things a chunked format gets wrong when it seals the chunks alone.
 *
 * ## The key
 *
 * Files are sealed with one random key, which is itself kept sealed under a key made from the
 * password ([derive], PBKDF2 with SHA-256). Changing the password re-seals that one key and
 * touches no file. A wrong password is a failed seal, not a comparison against a stored hash, so
 * nothing on disk can be checked against guesses faster than the derivation allows.
 *
 * Pure `javax.crypto`, so all of it is tested off the device.
 */
object LockerCrypto {

    /** Plain bytes per chunk. 64 KiB: small enough to hold, large enough that the tags cost little. */
    const val CHUNK = 64 * 1024

    private const val TAG_BITS = 128
    private const val TAG = TAG_BITS / 8
    private const val PREFIX = 7
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())

    /** The header: magic, chunk size and the file's nonce prefix. Bound into every chunk. */
    const val HEADER = 4 + 4 + PREFIX

    /**
     * PBKDF2 rounds. OWASP's figure for PBKDF2-HMAC-SHA256 is 600,000; this is a third of it, so
     * unlocking takes under a second on a mid-range phone. The file key is random, so a guess
     * has to go through this for every attempt and has nothing faster to test against.
     */
    const val ROUNDS = 210_000

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** A new random file key. */
    fun newKey(): ByteArray = randomBytes(32)

    /** The key that seals the file key, from [password] and [salt]. Slow on purpose: see [ROUNDS]. */
    fun derive(password: CharArray, salt: ByteArray, rounds: Int = ROUNDS): ByteArray {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** [plain] sealed under [key]: a fresh 12-byte nonce, then the sealed bytes. For small things. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** What [seal] made, or null when [key] is wrong or a byte has changed. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (sealed.size < 12 + TAG) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, 12))
            if (aad != null) c.updateAAD(aad)
            c.doFinal(sealed, 12, sealed.size - 12)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    /** Seals [input] into [output] as chunks under [key]. Returns the plain byte count. */
    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream, chunk: Int = CHUNK): Long {
        val w = ChunkWriter(key, output, chunk)
        val buf = ByteArray(minOf(chunk, 1 shl 16))
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            w.write(buf, 0, r)
        }
        w.close()
        return w.written
    }

    /**
     * An OutputStream that seals what is written to it, for a writer that pushes bytes rather
     * than handing over a stream - a copy into the Locker is one. [close] seals the last chunk
     * and closes [output]; until then the file is not readable, which is the point: a copy that
     * stops half way leaves a file that fails to open rather than one that opens short.
     */
    fun encrypting(key: ByteArray, output: OutputStream, chunk: Int = CHUNK): OutputStream = ChunkWriter(key, output, chunk)

    private class ChunkWriter(key: ByteArray, private val output: OutputStream, private val chunk: Int) : OutputStream() {
        private val header = ByteBuffer.allocate(HEADER).put(MAGIC).putInt(chunk).put(randomBytes(PREFIX)).array()
        private val k = SecretKeySpec(key, "AES")
        private val c = Cipher.getInstance("AES/GCM/NoPadding")
        // A full chunk is held back until a byte after it arrives, because only then is it
        // known not to be the last - and the last is sealed differently.
        private val pending = ByteArray(chunk)
        private var filled = 0
        private var index = 0
        private var closed = false
        var written = 0L
            private set

        init {
            output.write(header)
        }

        private fun seal(last: Boolean) {
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(header, index, last)))
            c.updateAAD(header)
            output.write(c.doFinal(pending, 0, filled))
            index++
            filled = 0
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var at = off
            var left = len
            while (left > 0) {
                if (filled == chunk) seal(last = false)
                val n = minOf(left, chunk - filled)
                System.arraycopy(b, at, pending, filled, n)
                filled += n
                at += n
                left -= n
                written += n
            }
        }

        override fun flush() = output.flush()

        override fun close() {
            if (closed) return
            closed = true
            seal(last = true)
            output.close()
        }
    }

    /**
     * The plain bytes of a sealed file from [offset] on, for a seek. [input] is the sealed file
     * from its first byte, and should skip cheaply - a file stream does, by moving its position -
     * since everything before the chunk holding [offset] is skipped rather than read.
     */
    fun decryptingAt(key: ByteArray, input: InputStream, offset: Long): InputStream {
        require(offset >= 0) { "bad offset $offset" }
        val header = ByteArray(HEADER)
        if (input.readNBytesCompat(header) != HEADER) throw java.io.IOException("not a Locker file")
        val chunk = ChunkReader.chunkOf(header)
        val first = offset / chunk
        skipFully(input, first * (chunk + TAG))
        val reader = ChunkReader(key, input, header, first.toInt())
        var drop = offset - first * chunk
        while (drop > 0) {
            val n = reader.skip(drop)
            if (n <= 0) break
            drop -= n
        }
        return reader
    }

    /** The plain size of a sealed file of [sealedSize] bytes, without opening it. */
    fun plainSize(sealedSize: Long, chunk: Int = CHUNK): Long {
        val body = sealedSize - HEADER
        if (body < TAG) return 0
        val full = body / (chunk + TAG)
        val rest = body % (chunk + TAG)
        return full * chunk + if (rest >= TAG) rest - TAG else 0
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) throw EOFException("Locker file cut short")
            left--
        }
    }

    /**
     * A stream of the plain bytes of a sealed file. Reads one chunk at a time; a changed, moved or
     * missing chunk ends it with [java.io.IOException], never with wrong bytes.
     */
    fun decrypting(key: ByteArray, input: InputStream): InputStream = ChunkReader(key, input)

    private class ChunkReader(
        key: ByteArray,
        private val input: InputStream,
        known: ByteArray? = null,
        private var index: Int = 0,
    ) : InputStream() {
        private val header = known ?: ByteArray(HEADER)
        private val chunk: Int
        private val k = SecretKeySpec(key, "AES")
        private val c = Cipher.getInstance("AES/GCM/NoPadding")
        private var buffer = ByteArray(0)
        private var at = 0
        private var ended = false

        init {
            if (known == null && input.readNBytesCompat(header) != HEADER) throw java.io.IOException("not a Locker file")
            chunk = chunkOf(header)
        }

        companion object {
            fun chunkOf(header: ByteArray): Int {
                if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw java.io.IOException("not a Locker file")
                val chunk = ByteBuffer.wrap(header, 4, 4).int
                if (chunk <= 0 || chunk > 16 * 1024 * 1024) throw java.io.IOException("not a Locker file")
                return chunk
            }
        }

        private fun fill(): Boolean {
            if (ended) return false
            val sealed = readFully(input, chunk + TAG)
            if (sealed.size < TAG) throw EOFException("Locker file cut short")
            // A sealed chunk shorter than a full one can only be the last; a full one may be too.
            val plain = tryOpen(sealed, last = sealed.size < chunk + TAG)
                ?: (if (sealed.size == chunk + TAG) tryOpen(sealed, last = true)?.also { ended = true } else null)
                ?: throw java.io.IOException("Locker file damaged at chunk $index")
            if (sealed.size < chunk + TAG) ended = true
            buffer = plain
            at = 0
            index++
            return true
        }

        private fun tryOpen(sealed: ByteArray, last: Boolean): ByteArray? = try {
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(header, index, last)))
            c.updateAAD(header)
            c.doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }

        override fun read(): Int {
            while (at >= buffer.size) if (!fill()) return -1
            return buffer[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= buffer.size) if (!fill()) return -1
            val n = minOf(len, buffer.size - at)
            System.arraycopy(buffer, at, b, off, n)
            at += n
            return n
        }

        override fun close() = input.close()
    }

    /** The 12-byte nonce of chunk [index]: the file's prefix, the index, and whether it is the last. */
    private fun nonce(header: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(header, 8, PREFIX).putInt(index).put(if (last) 1 else 0).array()

    /** Up to [n] bytes, fewer only at the end of [input]. */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(n, 1 shl 16))
        val buf = ByteArray(minOf(n, 1 shl 16))
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    internal fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val r = read(into, got, into.size - got)
            if (r < 0) break
            got += r
        }
        return got
    }
}
