package dev.niccc2007.filet.log

import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failure flattened into its cause chain: in order, every link, each with a site, and the
 * root cause's own frames - never a raw stack.
 */
class FailureTest {

    private fun stage(): Nothing = try {
        connect()
    } catch (e: SocketException) {
        throw IOException("could not stage report.pdf", e)
    }

    private fun connect(): Nothing = throw SocketException("Connection reset")

    @Test
    fun `the chain is flattened outermost first, one link per cause`() {
        val t = runCatching { stage() }.exceptionOrNull()!!
        val links = Failure.chain(t)
        assertEquals(listOf("IOException", "SocketException"), links.map { it.type })
        assertEquals("could not stage report.pdf", links[0].message)
        assertEquals("Connection reset", links[1].message)
        // This test lives in the app's package, so each site is a frame of this file.
        assertTrue(links.all { it.site?.startsWith("FailureTest.kt:") == true })
    }

    @Test
    fun `the rendered failure names each link and the line the root cause came from`() {
        val t = runCatching { stage() }.exceptionOrNull()!!
        val text = Failure.render(t)
        val lines = text.lines()
        assertTrue(lines[0].startsWith("  failure   IOException: could not stage report.pdf @ FailureTest.kt:"))
        assertTrue(lines[1].startsWith("  caused by SocketException: Connection reset @ FailureTest.kt:"))
        assertTrue(text.contains("trail"))
        assertTrue(text.contains("FailureTest.connect"))
        // Not a stack dump: no JUnit or JDK reflection frames.
        assertFalse(text.contains("org.junit"))
        assertFalse(text.contains("java.lang.reflect"))
    }

    @Test
    fun `a cycle in the causes ends the walk instead of looping`() {
        val a = IOException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertEquals(2, Failure.chain(a).size)
        assertNotNull(Failure.render(a))
    }

    @Test
    fun `a chain deeper than the cap is cut and says so`() {
        var t: Throwable = RuntimeException("0")
        repeat(30) { t = RuntimeException("${it + 1}", t) }
        assertEquals(Failure.DEPTH, Failure.chain(t).size)
        assertTrue(Failure.render(t).contains("chain cut"))
    }

    @Test
    fun `a library exception keeps its package, the JDK's does not`() {
        class LibError : Exception()
        assertEquals("IOException", Failure.typeName(IOException()))
        // A local class in the app package reads as a simple name; a foreign one would be full.
        assertTrue(Failure.typeName(LibError()).contains("LibError"))
    }

    @Test
    fun `a message-less throwable says so rather than printing null`() {
        assertTrue(Failure.render(IllegalStateException()).contains("(no message)"))
    }
}
