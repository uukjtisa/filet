package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What ending a fast share takes down with it. */
class FastEndTest {

    @Test fun `the last fast share ends the sharing it switched on`() {
        assertTrue(FastEnd.stopsSharing(startedForFast = true, running = 0))
    }

    @Test fun `sharing stays on while another fast share runs, or when it was on before`() {
        assertFalse(FastEnd.stopsSharing(startedForFast = true, running = 1))
        assertFalse(FastEnd.stopsSharing(startedForFast = false, running = 0))
    }

    @Test fun `a stopped fast share takes off the Share page only what it put there`() {
        val f = FastShare("k1", tokens = listOf("a", "b", "c"), at = 0, added = listOf("b", "c"))
        assertEquals(setOf("b", "c"), FastShare.toRevoke(f, listOf(f)))
    }

    @Test fun `a file another running fast share hands out stays shared`() {
        val f = FastShare("k1", tokens = listOf("a", "b"), at = 0, added = listOf("a", "b"))
        val other = FastShare("k2", tokens = listOf("b"), at = 0, added = emptyList())
        assertEquals(setOf("a"), FastShare.toRevoke(f, listOf(f, other)))
    }

    @Test fun `a fast share saved before it kept what it added takes nothing off`() {
        val old = FastShare("k1", tokens = listOf("a"), at = 0)
        assertEquals(emptySet<String>(), FastShare.toRevoke(old, listOf(old)))
    }
}
