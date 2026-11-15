package dev.niccc2007.filet.portal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything Portal puts on a host is sealed here first, so a host holds only ciphertext under a
 * random name. One key per send, carried in the link after the `#`, which no browser and no host
 * ever receives.
 *
 * Decision: AES-256-GCM in sealed segments rather than one GCM over the whole file. Decrypting one
 * long GCM stream on Android buffers all of it before the tag can be checked, which runs a phone
 * out of memory on a video; segments are checked as they arrive, so a file of any size streams
 * straight to storage. Each segment's nonce is the blob's random prefix plus its index, and the
 * last one is sealed with a different flag, so a host cannot reorder, drop or cut segments
 * without the open failing.
 *
 * Layout: MAGIC (4) · nonce prefix (8) · segments of up to [SEG] plain bytes, each plus a 16 byte tag.
 */
object PortalCrypto {
    const val SEG = 64 * 1024
    const val TAG = 16
    const val HEADER = 12
    const val KEY_BYTES = 32
    private val MAGIC = byteArrayOf(0x46, 0x50, 0x54, 0x31) // "FPT1"
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun keyText(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    fun keyOf(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }
        .getOrNull()?.takeIf { it.size == KEY_BYTES }

    /** How many segments [plain] bytes seal into: at least one, so an empty file still has a tag. */
    fun segments(plain: Long): Long = if (plain <= 0) 1 else (plain + SEG - 1) / SEG

    /** The exact size of [plain] bytes once sealed, known before a byte is read. */
    fun sealedSize(plain: Long): Long = HEADER + segments(plain) * TAG + plain.coerceAtLeast(0)

    /** The most plain bytes that still seal into [sealed] bytes or fewer. */
    fun plainFitting(sealed: Long): Long {
        if (sealed < HEADER + TAG) return 0
        val room = sealed - HEADER
        val whole = room / (SEG + TAG)
        val rest = room % (SEG + TAG)
        return whole * SEG + (rest - TAG).coerceAtLeast(0)
    }

    /**
     * Seal exactly [length] bytes of [input] into [out]. Throws when [input] ends early, because
     * the size was promised to the host before the upload began.
     */
    fun seal(key: ByteArray, input: InputStream, length: Long, out: OutputStream, onBytes: (Int) -> Unit = {}) {
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(prefix)
        val count = segments(length)
        val buf = ByteArray(SEG)
        var left = length.coerceAtLeast(0)
        for (i in 0 until count) {
            val want = minOf(SEG.toLong(), left).toInt()
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) throw IOException("the file ended before its size")
                got += n
            }
            left -= want
            out.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, i, last = i == count - 1).doFinal(buf, 0, want))
            onBytes(want)
        }
    }

    /**
     * Open a sealed blob from [input] into [out], returning the plain bytes written. Throws on
     * anything a host could have done to it: a changed byte, a missing or extra segment, a cut end.
     */
    fun open(key: ByteArray, input: InputStream, out: OutputStream, onBytes: (Int) -> Unit = {}): Long {
        val inp = PushbackInputStream(input, 1)
        val head = ByteArray(HEADER)
        if (readFull(inp, head, HEADER) != HEADER || !head.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw IOException("not a Filet Portal file")
        }
        val prefix = head.copyOfRange(4, HEADER)
        val buf = ByteArray(SEG + TAG)
        var i = 0L
        var total = 0L
        while (true) {
            val n = readFull(inp, buf, buf.size)
            if (n < TAG) throw IOException("the file was cut short")
            val next = if (n == buf.size) inp.read() else -1
            val last = next < 0
            if (!last) inp.unread(next)
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, i, last).doFinal(buf, 0, n)
            } catch (e: Exception) {
                throw IOException("the file was changed or cut on the host")
            }
            out.write(plain)
            onBytes(plain.size)
            total += plain.size
            if (last) return total
            i++
        }
    }

    fun sealBytes(key: ByteArray, plain: ByteArray): ByteArray = ByteArrayOutputStream().also {
        seal(key, ByteArrayInputStream(plain), plain.size.toLong(), it)
    }.toByteArray()

    fun openBytes(key: ByteArray, sealed: ByteArray): ByteArray = ByteArrayOutputStream().also {
        open(key, ByteArrayInputStream(sealed), it)
    }.toByteArray()

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, index: Long, last: Boolean): Cipher {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, 8)
        for (b in 0 until 4) nonce[8 + b] = (index ushr (8 * (3 - b))).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }
    }

    private fun readFull(input: InputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = input.read(buf, got, want - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
