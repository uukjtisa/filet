package dev.niccc2007.filet.handlers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the picture viewer plays an animation rather than showing the still. */
class AnimatedPictureTest {

    private val small = 2L * 1024 * 1024

    @Test fun a_gif_or_animated_webp_plays_from_android_9() {
        assertTrue(AnimatedPicture.wanted("gif", 28, small))
        assertTrue(AnimatedPicture.wanted("GIF", 34, small))
        assertTrue(AnimatedPicture.wanted("webp", 31, small))
        assertFalse("ImageDecoder is Android 9 and later", AnimatedPicture.wanted("gif", 27, small))
    }

    @Test fun a_format_that_cannot_move_is_never_read_twice() {
        for (e in listOf("png", "jpg", "jfif", "bmp", "heic")) assertFalse(e, AnimatedPicture.wanted(e, 34, small))
    }

    @Test fun a_file_too_big_or_of_unknown_size_stays_still() {
        // Playing means holding the whole file; an unknown size is not read whole on a guess.
        assertFalse(AnimatedPicture.wanted("gif", 34, AnimatedPicture.MAX_BYTES + 1))
        assertFalse(AnimatedPicture.wanted("gif", 34, -1))
        assertFalse(AnimatedPicture.wanted("gif", 34, 0))
        assertTrue(AnimatedPicture.wanted("gif", 34, AnimatedPicture.MAX_BYTES))
    }

    @Test fun the_still_is_shown_whenever_it_is_what_an_edit_would_save() {
        assertTrue(AnimatedPicture.plays(edited = false, turned = false, inTools = false))
        assertFalse(AnimatedPicture.plays(edited = true, turned = false, inTools = false))
        assertFalse(AnimatedPicture.plays(edited = false, turned = true, inTools = false))
        assertFalse(AnimatedPicture.plays(edited = false, turned = false, inTools = true))
    }
}
