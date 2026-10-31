package dev.niccc2007.filet.playback

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicScrollLabelTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis

    @Test fun `a name shows the letter the sort actually groups by`() {
        assertEquals("A", MusicScrollLabel.initial("abba - dancing queen.mp3"))
        assertEquals("A", MusicScrollLabel.initial("ABBA.mp3"))
        // Case folds because the ordering folds it. Two spellings in one place must not show
        // two different letters.
        assertEquals(MusicScrollLabel.initial("abba"), MusicScrollLabel.initial("ABBA"))
    }

    @Test fun `anything that is not a letter groups under hash`() {
        assertEquals("#", MusicScrollLabel.initial("01 intro.mp3"))
        assertEquals("#", MusicScrollLabel.initial("_hidden.mp3"))
        assertEquals("#", MusicScrollLabel.initial(""))
        assertEquals("#", MusicScrollLabel.initial("   "))
    }

    @Test fun `a date is spelled, never a slash-separated guess`() {
        // 2/3/26 is two different days depending on where you are, and the bubble has the room
        // because it is only on screen while a thumb is down.
        assertEquals("Oct 8 2026", MusicScrollLabel.date(at(2026, 10, 8), utc))
        assertEquals("Jan 1 2020", MusicScrollLabel.date(at(2020, 1, 1), utc))
    }

    @Test fun `a missing figure says so rather than showing a false zero`() {
        assertEquals("—", MusicScrollLabel.date(0L, utc))
        assertEquals("—", MusicScrollLabel.size(0L))
        assertEquals("—", MusicScrollLabel.clock(0L))
    }

    @Test fun `sizes keep a steady width so the bubble does not jitter`() {
        assertEquals("512 B", MusicScrollLabel.size(512))
        assertEquals("1.0 KB", MusicScrollLabel.size(1024))
        assertEquals("4.6 MB", MusicScrollLabel.size((4.6 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", MusicScrollLabel.size((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun `a running time grows an hours field only when it needs one`() {
        assertEquals("3:41", MusicScrollLabel.clock(221_000))
        assertEquals("0:09", MusicScrollLabel.clock(9_000))
        assertEquals("1:02:15", MusicScrollLabel.clock(3_735_000))
    }

    @Test fun `every ordering has a label and none of them borrows another's`() {
        val name = "Zebra.mp3"
        val added = at(2026, 10, 8)
        val modified = at(2024, 2, 3)
        val labels = MusicSortKey.entries.associateWith {
            MusicScrollLabel.of(it, name, added, modified, 4_800_000, 221_000, utc)
        }
        assertEquals("Z", labels[MusicSortKey.NAME])
        assertEquals("Oct 8 2026", labels[MusicSortKey.ADDED])
        assertEquals("Feb 3 2024", labels[MusicSortKey.MODIFIED])
        assertEquals("4.6 MB", labels[MusicSortKey.SIZE])
        assertEquals("3:41", labels[MusicSortKey.DURATION])
        // Added and Modified are the only pair that could legitimately coincide, and here they
        // do not - so a label lifted from the wrong field would show up as a duplicate.
        assertEquals(labels.values.size, labels.values.toSet().size)
    }
}
