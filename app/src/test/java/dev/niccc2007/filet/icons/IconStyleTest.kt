package dev.niccc2007.filet.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy icons are opt-in, place by place; nothing starts out Legacy. */
class IconStyleTest {

    @Test fun `every place starts on the drawn art`() {
        val styles = IconStyles()
        assertEquals(IconStyle.DRAWN, styles.tabs)
        assertEquals(IconStyle.DRAWN, styles.shortcuts)
        for (p in listOf(styles.files, styles.gallery)) {
            assertEquals(FolderDrawing.DRAWN, p.folders)
            assertEquals(IconStyle.DRAWN, p.files)
        }
    }

    @Test fun `the default set is the all-drawn set`() {
        assertEquals(IconStyles(), IconStyles.DRAWN)
    }

    @Test fun `each place switches on its own`() {
        val one = IconStyles(files = PlaceLook(folders = FolderDrawing.OUTLINE))
        assertEquals(FolderDrawing.OUTLINE, one.files.folders)
        assertEquals(IconStyle.DRAWN, one.files.files)
        assertEquals(PlaceLook.DRAWN, one.gallery)
        assertEquals(IconStyle.DRAWN, one.tabs)
        assertEquals(IconStyle.DRAWN, one.shortcuts)
    }

    // ── the stored form, look.legacy.v1 ──

    @Test fun `drawn stores as an empty object`() {
        assertEquals("{}", IconStyles.DRAWN.toJson())
    }

    @Test fun `every value round trips`() {
        for (f in FolderDrawing.entries) for (s in IconStyle.entries) {
            val styles = IconStyles(
                tabs = s,
                files = PlaceLook(f, s),
                gallery = PlaceLook(FolderDrawing.entries[(f.ordinal + 1) % FolderDrawing.entries.size], s),
                shortcuts = s,
            )
            assertEquals(styles, IconStyles.fromJson(styles.toJson()))
        }
    }

    @Test fun `missing, unknown and broken read as drawn`() {
        assertEquals(IconStyles.DRAWN, IconStyles.fromJson(null))
        assertEquals(IconStyles.DRAWN, IconStyles.fromJson("not json"))
        assertEquals(IconStyles.DRAWN, IconStyles.fromJson("""{"tabs":"PLAIN","files":{"folders":"SPARKLY"}}"""))
        val partial = IconStyles.fromJson("""{"gallery":{"files":"LEGACY"}}""")
        assertEquals(PlaceLook(FolderDrawing.DRAWN, IconStyle.LEGACY), partial.gallery)
        assertEquals(PlaceLook.DRAWN, partial.files)
    }

    // ── what a folder draws as ──

    private val dressed = FolderLook(emblem = "music", colour = "rose", cover = "a.jpg")

    @Test fun `the outline shows nothing whatever the record says`() {
        val d = folderDraw(FolderDrawing.OUTLINE, dressed.copy(paint = FolderPaint.WHOLE))
        assertTrue(d.outline)
        assertNull(d.emblem)
        assertNull(d.cover)
        assertFalse(d.whole)
    }

    @Test fun `the place's choice paints when the folder has none of its own`() {
        assertFalse(folderDraw(FolderDrawing.DRAWN, dressed).whole)
        assertTrue(folderDraw(FolderDrawing.DRAWN_WHOLE, dressed).whole)
        assertFalse(folderDraw(FolderDrawing.WITH_PICTURES, dressed).whole)
        assertTrue(folderDraw(FolderDrawing.WITH_PICTURES, dressed).legacy)
    }

    @Test fun `a folder's own paint wins`() {
        assertTrue(folderDraw(FolderDrawing.DRAWN, dressed.copy(paint = FolderPaint.WHOLE)).whole)
        assertFalse(folderDraw(FolderDrawing.DRAWN_WHOLE, dressed.copy(paint = FolderPaint.EMBLEM)).whole)
        assertTrue(folderDraw(FolderDrawing.WITH_PICTURES, dressed.copy(paint = FolderPaint.WHOLE)).whole)
    }

    @Test fun `show emblem hides the emblem and keeps it saved`() {
        val hidden = dressed.copy(showEmblem = false)
        for (f in FolderDrawing.entries) assertNull(f.name, folderDraw(f, hidden).emblem)
        assertEquals("music", hidden.emblem)
        assertEquals("music", folderDraw(FolderDrawing.DRAWN, dressed).emblem)
        assertEquals("music", folderDraw(FolderDrawing.WITH_PICTURES, dressed).emblem)
    }

    @Test fun `a record starts with its emblem showing and no paint of its own`() {
        assertTrue(FolderLook().showEmblem)
        assertNull(FolderLook().paint)
    }
}
