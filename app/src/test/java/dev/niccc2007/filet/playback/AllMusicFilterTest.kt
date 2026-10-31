package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllMusicFilterTest {

    private val primary = "/storage/emulated/0"

    @Test fun `a relative folder resolves against the primary volume`() {
        assertEquals(
            listOf("/storage/emulated/0/Music", "/storage/emulated/0/Trawl/Audio"),
            AllMusicFilter.prefixes(listOf("Music", "Trawl/Audio"), primary),
        )
    }

    @Test fun `an absolute folder is kept as it is, so an SD card can be added`() {
        assertEquals(
            listOf("/mnt/sd/Albums"),
            AllMusicFilter.prefixes(listOf("/mnt/sd/Albums"), primary),
        )
    }

    @Test fun `blanks and a bare root are dropped, and a leading slash means absolute`() {
        // "/Music" is a path, "Music" is a name under the primary volume. Treating the two as
        // the same thing is what broke SD-card folders: trimming both ends made every absolute
        // path relative and it silently matched nothing.
        assertEquals(
            listOf("/Music", "/storage/emulated/0/Music"),
            AllMusicFilter.prefixes(listOf("/Music/", "  ", "", "Music", "/"), primary),
        )
    }

    @Test fun `the defaults are the three folders music actually arrives in`() {
        assertEquals(listOf("Music", "Download", "Trawl/Audio"), AllMusicFilter.DEFAULT_FOLDERS)
        assertEquals(60_000L, AllMusicFilter.DEFAULT_MIN_MS)
        assertEquals(600_000L, AllMusicFilter.DEFAULT_MAX_MS)
    }

    @Test fun `the clock reads as a track length`() {
        assertEquals("1:00", AllMusicFilter.clock(60_000))
        assertEquals("10:00", AllMusicFilter.clock(600_000))
        assertEquals("0:05", AllMusicFilter.clock(5_000))
        assertEquals("any", AllMusicFilter.clock(0))
    }
}
