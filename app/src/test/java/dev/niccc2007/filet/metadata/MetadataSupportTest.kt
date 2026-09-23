package dev.niccc2007.filet.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The support matrix, checked for internal honesty.
 *
 * The table is a set of claims about other people's files. A claim that is wrong in the
 * optimistic direction costs somebody their data, so what is tested here is mostly that the
 * table cannot quietly become optimistic.
 */
class MetadataSupportTest {

    @Test
    fun `anything not fully supported says why`() {
        // A tier without a reason is a claim nobody can check, and it is the first thing that
        // rots when a format is moved between tiers.
        for (f in MetadataSupport.FORMATS) {
            if (f.tier != MetadataSupport.Tier.FULL) {
                assertNotNull("${f.name} is ${f.tier} with no caveat", f.caveat)
                assertTrue("${f.name}'s caveat is too short to explain anything", f.caveat!!.length > 40)
            }
        }
    }

    @Test
    fun `every format declares at least one field and one extension`() {
        for (f in MetadataSupport.FORMATS) {
            assertTrue("${f.name} has no fields", f.fields.isNotEmpty())
            assertTrue("${f.name} has no extensions", f.extensions.isNotEmpty())
        }
    }

    @Test
    fun `extensions are stored the way they are looked up`() {
        // Lowercase, no dot. A single stray ".JPG" makes a format invisible to the lookup and
        // the file silently unsupported.
        for (f in MetadataSupport.FORMATS) {
            for (e in f.extensions) {
                assertEquals(e, e.lowercase())
                assertFalse(e.startsWith("."))
            }
        }
    }

    @Test
    fun `only a writable tier is ever offered as a writer`() {
        for (f in MetadataSupport.FORMATS) {
            for (e in f.extensions) {
                val w = MetadataSupport.writerFor(e)
                if (w != null) {
                    assertTrue(
                        "${w.name} is offered as a writer at tier ${w.tier}",
                        w.tier != MetadataSupport.Tier.READ_ONLY,
                    )
                }
            }
        }
    }

    @Test
    fun `a read-only format is not writable even when another entry shares its extension`() {
        // jpg appears twice - a writable comment and read-only EXIF. The lookup must return
        // the writable one without that making EXIF look writable.
        val jpg = MetadataSupport.forExtension("jpg")
        assertTrue(jpg.size >= 2)
        assertEquals(MetadataSupport.Tier.FULL, jpg.first().tier)
        assertTrue(jpg.any { it.tier == MetadataSupport.Tier.READ_ONLY })
        assertEquals("JPEG comment", MetadataSupport.writerFor("jpg")?.name)
    }

    @Test
    fun `the formats that are genuinely dangerous stay read-only`() {
        // Each of these has internal byte offsets that every edit invalidates. If one of them
        // ever moves up a tier, that is a decision that has to be made deliberately and this
        // is where it gets noticed.
        for (ext in listOf("mp4", "pdf", "flac", "ogg", "m4a", "mov")) {
            assertFalse("$ext must not be writable", MetadataSupport.canWrite(ext))
        }
    }

    @Test
    fun `the formats that are safe stay writable`() {
        for (ext in listOf("png", "jpg", "jpeg", "gif", "zip", "apk")) {
            assertTrue("$ext should be writable", MetadataSupport.canWrite(ext))
        }
    }

    @Test
    fun `only PNG claims both custom keys and binary payloads`() {
        // The claim that an arbitrary key and an embedded image both work. Exactly one format
        // here can honestly make it, and the table must not grow a second one by accident.
        val both = MetadataSupport.FORMATS.filter {
            it.custom && it.binary && it.tier == MetadataSupport.Tier.FULL
        }
        assertEquals(listOf("PNG"), both.map { it.name })
    }

    @Test
    fun `lookup is case and dot insensitive`() {
        assertEquals(MetadataSupport.forExtension("png"), MetadataSupport.forExtension("PNG"))
        assertEquals(MetadataSupport.forExtension("png"), MetadataSupport.forExtension(".png"))
    }

    @Test
    fun `an unknown extension has no writer and says something useful`() {
        assertNull(MetadataSupport.writerFor("xyzzy"))
        assertTrue(MetadataSupport.refusalFor("xyzzy").contains("does not recognise"))
    }

    @Test
    fun `a refusal names the format's problem rather than the app's`() {
        // "Not supported" teaches nothing and reads as an unfinished feature.
        val mp4 = MetadataSupport.refusalFor("mp4")
        assertTrue(mp4.contains("moov") || mp4.contains("offset"))
        assertFalse(mp4.lowercase().contains("not supported"))
    }

    @Test
    fun `every tier is populated, so the list is a real spread and not a label`() {
        val counts = MetadataSupport.countByTier()
        for (t in MetadataSupport.Tier.entries) {
            assertTrue("$t has no formats", (counts[t] ?: 0) > 0)
        }
    }
}
