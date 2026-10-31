package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the store that are a decision rather than a directory.
 *
 * Creating folders needs a device; which file goes in which folder does not, and that is the
 * half that can be silently wrong.
 */
class FiletStoreTest {

    @Test fun `the two audio areas split the one folder they came from`() {
        // `AudioCache` wrote beat maps and decoded samples into a single directory, told apart
        // only by extension. Getting this backwards would file ten-megabyte sample dumps under
        // "Beat maps" - which reads as the beat maps having become enormous, and would be
        // deleted by somebody clearing what they thought was a small folder.
        assertTrue(FiletStore.belongs(FiletStore.Area.BEATS, "a1b2c3.beat"))
        assertFalse(FiletStore.belongs(FiletStore.Area.BEATS, "a1b2c3.pcm"))
        assertTrue(FiletStore.belongs(FiletStore.Area.AUDIO, "a1b2c3.pcm"))
        assertFalse(FiletStore.belongs(FiletStore.Area.AUDIO, "a1b2c3.beat"))
    }

    @Test fun `a half-written entry is claimed by neither`() {
        // Both writers go to `.part` and rename, so a process killed mid-write leaves one.
        // Moving it would carry a truncated file into the new folder, where it would parse as
        // far as its header and be wrong.
        assertFalse(FiletStore.belongs(FiletStore.Area.BEATS, "a1b2c3.beat.part"))
        assertFalse(FiletStore.belongs(FiletStore.Area.AUDIO, "a1b2c3.pcm.part"))
    }

    @Test fun `an area with a folder of its own takes everything in it`() {
        assertTrue(FiletStore.belongs(FiletStore.Area.LOGS, "filet_2026-10-26_120000.000_sun.log"))
        assertTrue(FiletStore.belongs(FiletStore.Area.REPORTS, "crash-1761400000000.txt"))
        assertTrue(FiletStore.belongs(FiletStore.Area.SCRIPTS, "rename-photos.sh"))
    }

    @Test fun `every area says what it holds and whether losing it costs anything`() {
        // Exhaustive `when`s, so a sixth area cannot be added without deciding both - the
        // settings screen that lists them reads these and would otherwise fall behind.
        for (area in FiletStore.Area.entries) {
            assertTrue(area.name, area.folder.isNotBlank())
            assertTrue(area.name, area.note.isNotBlank())
        }
        // Scripts are somebody's work rather than a derived file, and are the only area here
        // that cannot simply be made again.
        assertFalse(FiletStore.Area.SCRIPTS.disposable)
        assertTrue(FiletStore.Area.BEATS.disposable)
        assertTrue(FiletStore.Area.AUDIO.disposable)
    }

    @Test fun `folder names are written for a person opening a file list`() {
        assertEquals("Beat maps", FiletStore.Area.BEATS.folder)
        assertEquals("Decoded audio", FiletStore.Area.AUDIO.folder)
        // The logs already lived here under this name and keep it, so an existing install's
        // folder is not orphaned by the move.
        assertEquals("Logs", FiletStore.Area.LOGS.folder)
    }

    @Test fun `only the areas that moved declare where they came from`() {
        assertEquals(FiletStore.Legacy.CACHE_AUDIO, FiletStore.Area.BEATS.from)
        assertEquals(FiletStore.Legacy.CACHE_AUDIO, FiletStore.Area.AUDIO.from)
        assertEquals(FiletStore.Legacy.FILES_CRASH, FiletStore.Area.REPORTS.from)
        assertEquals(FiletStore.Legacy.FILES_SCRIPTS, FiletStore.Area.SCRIPTS.from)
        // Already in the right place, so nothing to move.
        assertEquals(null, FiletStore.Area.LOGS.from)
    }

    private val mb = 1024L * 1024

    @Test fun `the reminder waits until the cache passes the chosen size`() {
        val past = FiletStore.REMIND_DEFAULT
        assertTrue(FiletStore.shouldRemind(past, past, dismissedAt = 0))
        assertTrue(FiletStore.shouldRemind(past * 3, past, dismissedAt = 0))
        assertFalse(FiletStore.shouldRemind(past - 1, past, dismissedAt = 0))
        assertFalse(FiletStore.shouldRemind(0, past, dismissedAt = 0))
        assertFalse(FiletStore.shouldRemind(1500 * mb, 2048 * mb, dismissedAt = 0))
    }

    @Test fun `never means never`() =
        assertFalse(FiletStore.shouldRemind(50_000 * mb, threshold = 0, dismissedAt = 0))

    @Test fun `a dismissal stays quiet until the cache grows one more step past it`() {
        val past = 500 * mb
        // Dismissed at 620 MB: quiet through 1119 MB, back at 1120.
        assertFalse(FiletStore.shouldRemind(620 * mb, past, dismissedAt = 620 * mb))
        assertFalse(FiletStore.shouldRemind(1119 * mb, past, dismissedAt = 620 * mb))
        assertTrue(FiletStore.shouldRemind(1120 * mb, past, dismissedAt = 620 * mb))
    }

    @Test fun `the default is 500 MB and the steps read as Settings shows them`() {
        assertEquals(500 * mb, FiletStore.REMIND_DEFAULT)
        assertEquals(
            listOf("500 MB", "1 GB", "2 GB", "5 GB", "Never"),
            FiletStore.REMIND_STEPS.map(FiletStore::remindLabel),
        )
    }

    @Test fun `the visible folder is the default`() {
        assertEquals(FiletStore.Location.FILET, FiletStore.Location.DEFAULT)
        assertEquals(FiletStore.Location.DEFAULT, FiletStore.Location.of(null))
        // A value written by a newer build, or a corrupted preference, falls back rather than
        // throwing on start.
        assertEquals(FiletStore.Location.DEFAULT, FiletStore.Location.of("SOMEWHERE_ELSE"))
        assertEquals(FiletStore.Location.ANDROID, FiletStore.Location.of("ANDROID"))
    }

    @Test fun `every location says what it means for the person choosing it`() {
        for (l in FiletStore.Location.entries) {
            assertTrue(l.name, l.label.isNotBlank())
        }
    }

    @Test fun `only derived areas are offered for deletion`() {
        val keep = FiletStore.Area.entries.filterNot { it.disposable }.map { it.name }.toSet()
        // The three that hold something which cannot be made again. Pinned so that adding a
        // tenth area and marking it disposable by accident shows up here rather than as
        // somebody's scripts disappearing behind a Clear button.
        assertEquals(setOf("LOGS", "SCRIPTS", "REPORTS", "MEMORY", "SETTINGS"), keep)
    }

    @Test fun `what Filet remembers is never treated as a cache`() {
        // The distinction the whole enum exists for. MEMORY holds the one fact nothing else
        // can recreate - when this phone first saw a file - so being non-disposable is what
        // keeps the location toggle from moving it, keeps both Clear buttons from offering
        // it, and keeps the home screen from counting it as space worth reclaiming.
        //
        // The near-miss worth pinning: EXIF capture dates and MediaStore DATE_TAKEN look like
        // they could rebuild it and cannot. They prove the CONTENT existed by then, not that
        // it was on this phone - a 2019 photo received yesterday would read as having been
        // here since 2019.
        assertFalse(FiletStore.Area.MEMORY.disposable)
        assertEquals(null, FiletStore.Area.MEMORY.from)
        assertEquals("Memory", FiletStore.Area.MEMORY.folder)
    }

    // ── the note the folder carries ───────────────────────────────────────────────────────

    @Test fun `the note names every area, so a tenth one cannot go undocumented`() {
        // Built from Area.entries rather than written out. A note that silently stops
        // describing the folder is worse than no note, because it is read as complete.
        val note = FiletStore.folderNote()
        for (a in FiletStore.Area.entries) {
            assertTrue("missing " + a.folder, note.contains(a.folder))
            assertTrue("missing the note for " + a.folder, note.contains(a.note))
        }
    }

    @Test fun `the note puts each area on the right side of the line`() {
        // The whole value of the file is somebody deciding what is safe to delete. Getting a
        // kept folder onto the disposable list is how a person loses their playlists by
        // following our own instructions.
        val note = FiletStore.folderNote()
        val kept = note.substringAfter("KEPT").substringBefore("SAFE TO DELETE")
        val safe = note.substringAfter("SAFE TO DELETE")
        for (a in FiletStore.Area.entries) {
            if (a.disposable) {
                assertTrue(a.folder + " should be listed as safe", safe.contains(a.folder))
                assertFalse(a.folder + " must not be listed as kept", kept.contains(a.folder))
            } else {
                assertTrue(a.folder + " should be listed as kept", kept.contains(a.folder))
                assertFalse(a.folder + " must not be listed as safe", safe.contains(a.folder))
            }
        }
    }

    @Test fun `the note says the thing it exists to say`() {
        // The one sentence somebody needs, and the reason the file is written at all.
        val note = FiletStore.folderNote()
        assertTrue(note.contains("SURVIVES UNINSTALLING FILET"))
        assertTrue(note.contains("clearing the app's data"))
        // And it must not promise to restore passwords, which are deliberately never written
        // here - a promise we cannot keep reads as the restore being broken.
        assertTrue(note.contains("passwords"))
    }

    @Test fun `the note carries its own version`() {
        // The version lives in the TEXT, not in a preference: a preference would be wiped by
        // the exact event this file exists to survive, and the note would then never be
        // refreshed again on a phone that had been cleared.
        assertTrue(FiletStore.folderNote().contains("note v" + FiletStore.NOTE_VERSION))
    }
}
