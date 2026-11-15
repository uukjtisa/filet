package dev.niccc2007.filet.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkProgressTest {
    @Test
    fun `stages add up in order and never claim done`() {
        val p = ApkProgress(listOf(1.0, 3.0))
        assertEquals(0f, p.at(0, 0.0), 1e-6f)
        assertEquals(0.25f, p.at(0, 1.0), 1e-6f)
        assertEquals(0.25f, p.at(1, 0.0), 1e-6f)
        assertEquals(0.625f, p.at(1, 0.5), 1e-6f)
        assertEquals(ApkProgress.LAST, p.at(1, 1.0), 1e-6f)
        assertEquals(ApkProgress.LAST, p.at(5, 0.0), 1e-6f)
    }

    @Test
    fun `a decompile gives the copy a tenth and shares the rest by dex size`() {
        val p = ApkProgress.decompile(listOf(300L, 100L))
        assertEquals(0.1f, p.at(1, 0.0), 1e-6f)
        assertEquals(0.775f, p.at(2, 0.0), 1e-6f)
        assertEquals(0.1f + 0.675f / 2, p.at(1, 0.5), 1e-6f)
    }

    @Test
    fun `an APK with no code still moves`() {
        val p = ApkProgress.decompile(emptyList())
        assertEquals(0.5f, p.at(0, 0.5), 1e-6f)
    }

    @Test
    fun `a rebuild weighs folders by their files, then repacks and signs`() {
        val p = ApkProgress.rebuild(listOf(900, 100))
        assertEquals(0.72f, p.at(1, 0.0), 1e-6f)
        assertEquals(0.8f, p.at(2, 0.0), 1e-6f)
        assertEquals(0.95f, p.at(3, 0.0), 1e-6f)
        // Folders whose files could not be counted share alike.
        assertEquals(0.4f, ApkProgress.rebuild(listOf(0, 0)).at(1, 0.0), 1e-6f)
    }

    @Test
    fun `every step forward is forward`() {
        val p = ApkProgress.decompile(listOf(5L, 0L, 7L))
        var last = -1f
        for (stage in 0..3) for (i in 0..10) {
            val f = p.at(stage, i / 10.0)
            assertTrue("$stage $i", f >= last)
            last = f
        }
    }

    @Test
    fun `the reporter passes new lines and real steps, not every class`() {
        val seen = mutableListOf<Pair<String, Float?>>()
        val r = ApkReporter { s, f -> seen += s to f }
        r.report("classes.dex", 0.100f)
        r.report("classes.dex", 0.101f)
        r.report("classes.dex", 0.106f)
        r.report("classes2.dex", 0.107f)
        assertEquals(listOf("classes.dex" to 0.100f, "classes.dex" to 0.106f, "classes2.dex" to 0.107f), seen)
    }
}
