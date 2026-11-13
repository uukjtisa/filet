package dev.niccc2007.filet.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plainer icons are opt-in, place by place; nothing starts out plain. */
class IconStyleTest {

    @Test fun `every place starts on the drawn art`() {
        val styles = IconStyles()
        val fields = IconStyles::class.java.declaredFields.filter { it.type == IconStyle::class.java }
        assertTrue("IconStyles has no places", fields.isNotEmpty())
        for (f in fields) {
            f.isAccessible = true
            assertEquals("${f.name} starts drawn", IconStyle.DRAWN, f.get(styles))
        }
    }

    @Test fun `the default set is the all-drawn set`() {
        assertEquals(IconStyles(), IconStyles.DRAWN)
    }

    @Test fun `each place switches on its own`() {
        val one = IconStyles(files = IconStyle.PLAIN)
        assertEquals(IconStyle.PLAIN, one.files)
        assertEquals(IconStyle.DRAWN, one.tabs)
        assertEquals(IconStyle.DRAWN, one.gallery)
        assertEquals(IconStyle.DRAWN, one.shortcuts)
    }
}
