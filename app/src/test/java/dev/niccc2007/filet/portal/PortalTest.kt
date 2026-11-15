package dev.niccc2007.filet.portal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class PortalCryptoTest {
    private val key = PortalCrypto.newKey()

    private fun roundTrip(size: Int) {
        val plain = Random(size).nextBytes(size)
        val out = ByteArrayOutputStream()
        PortalCrypto.seal(key, ByteArrayInputStream(plain), size.toLong(), out)
        val sealed = out.toByteArray()
        assertEquals("sealed size for $size", PortalCrypto.sealedSize(size.toLong()), sealed.size.toLong())
        assertArrayEquals(plain, PortalCrypto.openBytes(key, sealed))
    }

    @Test
    fun `any size seals to its promised size and opens back`() {
        val seg = PortalCrypto.SEG
        for (size in listOf(0, 1, 100, seg - 1, seg, seg + 1, 3 * seg, 3 * seg + 77)) roundTrip(size)
    }

    @Test
    fun `plainFitting is the largest plain size whose seal fits`() {
        for (sealed in listOf(28L, 29L, 1000L, 65_563L, 65_564L, 131_100L, 1_000_000_000L)) {
            val p = PortalCrypto.plainFitting(sealed)
            assertTrue("$sealed", PortalCrypto.sealedSize(p) <= sealed)
            assertTrue("$sealed", PortalCrypto.sealedSize(p + 1) > sealed)
        }
    }

    private fun sealed(size: Int): ByteArray = PortalCrypto.sealBytes(key, Random(1).nextBytes(size))

    private fun assertRefused(bytes: ByteArray, k: ByteArray = key) {
        try {
            PortalCrypto.openBytes(k, bytes)
            fail("opened")
        } catch (e: IOException) {
            // refused, as it must be
        }
    }

    @Test
    fun `a changed byte is refused`() {
        val s = sealed(1000)
        s[500] = (s[500].toInt() xor 1).toByte()
        assertRefused(s)
    }

    @Test
    fun `a file cut at a segment edge is refused`() {
        val seg = PortalCrypto.SEG
        val s = sealed(3 * seg)
        // Keep the header and the first two whole segments: each looks complete on its own.
        assertRefused(s.copyOf(PortalCrypto.HEADER + 2 * (seg + PortalCrypto.TAG)))
    }

    @Test
    fun `segments swapped are refused`() {
        val seg = PortalCrypto.SEG
        val step = seg + PortalCrypto.TAG
        val s = sealed(3 * seg)
        val h = PortalCrypto.HEADER
        val a = s.copyOfRange(h, h + step)
        System.arraycopy(s, h + step, s, h, step)
        System.arraycopy(a, 0, s, h + step, step)
        assertRefused(s)
    }

    @Test
    fun `another key does not open it`() {
        assertRefused(sealed(10), PortalCrypto.newKey())
    }

    @Test
    fun `a source shorter than promised fails the seal`() {
        try {
            PortalCrypto.seal(key, ByteArrayInputStream(ByteArray(10)), 20, ByteArrayOutputStream())
            fail("sealed")
        } catch (e: IOException) {
            // the host was promised 20
        }
    }

    @Test
    fun `the key text is 43 characters and reads back`() {
        val t = PortalCrypto.keyText(key)
        assertEquals(43, t.length)
        assertArrayEquals(key, PortalCrypto.keyOf(t))
        assertNull(PortalCrypto.keyOf("short"))
    }
}

class PortalHostTest {
    @Test
    fun `links are read from text and json answers`() {
        assertEquals("https://litter.catbox.moe/ab12.bin", PortalHost.LITTERBOX.answer("https://litter.catbox.moe/ab12.bin\n"))
        assertEquals(
            "https://tmpfiles.org/dl/123/x.bin",
            PortalHost.TMPFILES.answer("""{"status":"success","data":{"url":"https:\/\/tmpfiles.org\/123\/x.bin"}}"""),
        )
        assertEquals("https://h.uguu.se/x.bin", PortalHost.UGUU.answer("""{"success":true,"files":[{"url":"https://h.uguu.se/x.bin"}]}"""))
    }

    @Test
    fun `a link on another domain is not taken`() {
        assertNull(PortalHost.LITTERBOX.answer("https://evil.example/x.bin"))
        assertNull(PortalHost.ZERO_X0.answer("https://0x0.st.evil.example/x"))
        assertNull(PortalHost.ZERO_X0.answer("http://0x0.st/x"))
        assertFalse(PortalHost.anyOwns("https://user@0x0.st/x"))
        assertFalse(PortalHost.anyOwns("https://192.168.1.2/x"))
        assertTrue(PortalHost.anyOwns("https://0x0.st/s/abc/x.bin"))
    }

    @Test
    fun `a host is only used for what it keeps`() {
        assertTrue(PortalHost.LITTERBOX.keeps(3 * DAY))
        assertFalse(PortalHost.UGUU.keeps(12 * HOUR))
        assertEquals(12 * HOUR, PortalHost.LITTERBOX.lifetime(12 * HOUR))
        assertEquals(24 * HOUR, PortalHost.LITTERBOX.lifetime(13 * HOUR))
        assertEquals(listOf("reqtype" to "fileupload", "time" to "12h"), PortalHost.LITTERBOX.request(12 * HOUR).fields)
    }
}

class PortalPlanTest {
    private val hosts = listOf(PortalHost.LITTERBOX, PortalHost.ZERO_X0, PortalHost.UGUU)

    @Test
    fun `every byte of every file is in exactly one part`() {
        val sizes = listOf(0L, 10L, 3_000_000_000L, 200_000_000L)
        for (scatter in listOf(false, true)) {
            val plan = PortalPlan.plan(sizes, hosts, scatter)
            sizes.forEachIndexed { i, size ->
                val parts = plan.filter { it.file == i }.sortedBy { it.offset }
                var at = 0L
                for (p in parts) {
                    assertEquals(at, p.offset)
                    at += p.length
                }
                assertEquals(size, at)
            }
            for (p in plan) {
                assertTrue(p.hosts.isNotEmpty())
                assertTrue(p.hosts.all { it.maxBytes >= PortalCrypto.sealedSize(p.length) })
            }
        }
    }

    @Test
    fun `parts take turns over the hosts`() {
        val plan = PortalPlan.plan(listOf(10L, 10L, 10L), hosts, scatter = false)
        assertEquals(hosts, plan.map { it.hosts.first() })
    }

    @Test
    fun `scatter spreads one file over every host`() {
        val plan = PortalPlan.plan(listOf(40L * 1024 * 1024), hosts, scatter = true)
        assertEquals(hosts.toSet(), plan.map { it.hosts.first() }.toSet())
    }

    @Test
    fun `no hosts is no plan`() {
        assertTrue(PortalPlan.plan(listOf(1L), emptyList(), scatter = false).isEmpty())
    }
}

class PortalManifestTest {
    private val m = PortalManifest(
        "Pixel", 1L, 2L,
        listOf(ManifestFile("a.txt", 5, listOf(ManifestPart("https://litter.catbox.moe/x.bin", 2), ManifestPart("https://0x0.st/y.bin", 3)))),
    )

    @Test
    fun `a manifest reads back as written`() {
        assertEquals(m, PortalManifest.of(m.json()))
    }

    @Test
    fun `a part off the hosts, or parts that do not add up, is refused`() {
        val off = m.copy(files = listOf(m.files[0].copy(parts = listOf(ManifestPart("https://192.168.1.1/x", 5)))))
        assertNull(PortalManifest.of(off.json()))
        val short = m.copy(files = listOf(m.files[0].copy(size = 6)))
        assertNull(PortalManifest.of(short.json()))
        assertNull(PortalManifest.of("""{"v":2,"files":[]}"""))
        assertNotNull(PortalManifest.of(m.json()))
    }
}
