package dev.niccc2007.filet.locker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/** The Locker's file format: what goes in comes out, and nothing else does. */
class LockerCryptoTest {

    private val key = ByteArray(32) { it.toByte() }
    private val chunk = 16

    private fun sealed(plain: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { LockerCrypto.encrypt(k, ByteArrayInputStream(plain), it, chunk) }.toByteArray()

    private fun opened(file: ByteArray, k: ByteArray = key): ByteArray =
        LockerCrypto.decrypting(k, ByteArrayInputStream(file)).readBytes()

    private fun data(n: Int) = Random(n).nextBytes(n)

    @Test fun `every size round trips, the chunk edges included`() {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 5)) {
            val plain = data(n)
            val file = sealed(plain)
            assertArrayEquals("size $n", plain, opened(file))
            assertEquals("size $n", n.toLong(), LockerCrypto.plainSize(file.size.toLong(), chunk))
        }
    }

    @Test fun `the writer pushed a byte at a time makes the same plain bytes`() {
        val plain = data(3 * chunk + 2)
        val out = ByteArrayOutputStream()
        LockerCrypto.encrypting(key, out, chunk).use { w -> plain.forEach { w.write(it.toInt()) } }
        assertArrayEquals(plain, opened(out.toByteArray()))
    }

    @Test fun `a wrong key never opens`() {
        assertFails { opened(sealed(data(40)), ByteArray(32) { 9 }) }
    }

    @Test fun `one changed byte anywhere fails the read rather than returning wrong bytes`() {
        val file = sealed(data(3 * chunk + 5))
        for (i in listOf(LockerCrypto.HEADER - 1, LockerCrypto.HEADER + 3, file.size - 1)) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFails { opened(bad) }
        }
    }

    @Test fun `a file cut short at a chunk boundary is refused`() {
        val file = sealed(data(3 * chunk))
        val one = LockerCrypto.HEADER + chunk + 16
        assertFails { opened(file.copyOf(one)) }
        assertFails { opened(file.copyOf(one * 2 - LockerCrypto.HEADER)) }
    }

    @Test fun `two chunks swapped are refused`() {
        val file = sealed(data(3 * chunk))
        val size = chunk + 16
        val a = LockerCrypto.HEADER
        val swapped = file.copyOf()
        System.arraycopy(file, a, swapped, a + size, size)
        System.arraycopy(file, a + size, swapped, a, size)
        assertFails { opened(swapped) }
    }

    @Test fun `a chunk from another file is refused`() {
        val plain = data(2 * chunk)
        val one = sealed(plain)
        val two = sealed(plain)
        val mixed = one.copyOf()
        System.arraycopy(two, LockerCrypto.HEADER, mixed, LockerCrypto.HEADER, chunk + 16)
        assertFails { opened(mixed) }
    }

    @Test fun `a read from any offset gives the bytes from there on`() {
        val plain = data(5 * chunk + 3)
        val file = sealed(plain)
        for (at in listOf(0, 1, chunk - 1, chunk, chunk + 7, 4 * chunk, plain.size - 1)) {
            val got = LockerCrypto.decryptingAt(key, ByteArrayInputStream(file), at.toLong()).readBytes()
            assertArrayEquals("offset $at", plain.copyOfRange(at, plain.size), got)
        }
    }

    @Test fun `small things seal and open, and refuse the wrong key or binding`() {
        val s = LockerCrypto.seal(key, "index".toByteArray(), aad = "a".toByteArray())
        assertArrayEquals("index".toByteArray(), LockerCrypto.open(key, s, aad = "a".toByteArray()))
        assertNull(LockerCrypto.open(ByteArray(32), s, aad = "a".toByteArray()))
        assertNull(LockerCrypto.open(key, s, aad = "b".toByteArray()))
    }

    @Test fun `the password key is the same for the same password and salt, different otherwise`() {
        val salt = ByteArray(16) { 1 }
        val a = LockerCrypto.derive("pass".toCharArray(), salt, rounds = 1000)
        assertArrayEquals(a, LockerCrypto.derive("pass".toCharArray(), salt, rounds = 1000))
        assertNotNull(a)
        assertEquals(32, a.size)
        assert(!a.contentEquals(LockerCrypto.derive("pass!".toCharArray(), salt, rounds = 1000)))
        assert(!a.contentEquals(LockerCrypto.derive("pass".toCharArray(), ByteArray(16), rounds = 1000)))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        fail("read succeeded")
    }
}
