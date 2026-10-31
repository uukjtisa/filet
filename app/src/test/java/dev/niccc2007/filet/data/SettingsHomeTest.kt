package dev.niccc2007.filet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that decides whether a snapshot is written.
 *
 * This is the whole safety of the feature and it fails silently in the direction that matters.
 * If it is too permissive, a wiped app overwrites the only good copy seconds after the wipe and
 * the data is gone for the second time, by the hand of the thing meant to protect it. If it is
 * too strict, the snapshot freezes at some old state and quietly stops tracking reality.
 *
 * Tested as arithmetic because that is what it is, and because the alternative is finding out
 * on the one day it is load-bearing.
 */
class SettingsHomeTest {

    private fun w(bytes: Long, stores: Int) = SettingsHome.weigh(bytes, stores)

    @Test fun `a wiped app never replaces a good snapshot`() {
        // The sequence this exists for. Data cleared, app restarts with one small store, the
        // writer fires before anybody has been asked whether to restore.
        val good = w(120_000, 12)
        assertTrue(SettingsHome.shrunk(w(400, 1), good))
        assertTrue(SettingsHome.shrunk(w(0, 0), good))
    }

    @Test fun `losing a whole store is loss however small the store was`() {
        // Byte count alone would miss this: a store holding a few hundred bytes disappearing
        // barely moves the total, and a store that vanishes is not somebody editing settings.
        assertTrue(SettingsHome.shrunk(w(119_900, 11), w(120_000, 12)))
    }

    @Test fun `ordinary editing is allowed to shrink the file`() {
        // A store that could never shrink would stop tracking reality, which is its own kind
        // of failure - the snapshot would freeze at whatever the largest state ever was.
        val before = w(100_000, 12)
        assertFalse("deleting a playlist", SettingsHome.shrunk(w(92_000, 12), before))
        assertFalse("clearing recents", SettingsHome.shrunk(w(70_000, 12), before))
        // Half is the line. Below it, this is not editing.
        assertTrue(SettingsHome.shrunk(w(40_000, 12), before))
    }

    @Test fun `growth is always allowed`() {
        val before = w(100_000, 12)
        assertFalse(SettingsHome.shrunk(w(100_001, 12), before))
        assertFalse(SettingsHome.shrunk(w(500_000, 20), before))
    }

    @Test fun `the very first snapshot is never refused`() {
        // Nothing to compare against must not mean "do not write", or the feature never
        // produces its first copy and is useless precisely until it is needed.
        assertFalse(SettingsHome.shrunk(w(1, 1), w(0, 0)))
        assertFalse(SettingsHome.shrunk(w(120_000, 12), w(0, 0)))
    }

    @Test fun `the connections store is never copied raw`() {
        // Its contents are AES-GCM under a keystore key that is DESTROYED by an uninstall, so
        // a raw copy restores ciphertext nothing can ever decrypt - present, plausible and
        // permanently unreadable, which is worse than absent because it looks like it worked.
        // Connections travel through their own export, re-encrypted under the new key.
        assertTrue(SettingsHome.NET_STORE == "filet-net")
    }

    @Test fun `several snapshots are kept rather than one`() {
        // One file is one chance to be wrong. The shrink guard is a judgement, and a judgement
        // needs an older copy behind it for the times it judges badly.
        assertTrue(SettingsHome.KEEP > 1)
    }

    @Test fun `the floor is a share, not a byte count`() {
        // A fixed number of bytes would mean something different on a phone with four
        // playlists and one with four hundred.
        assertTrue(SettingsHome.SHRINK_FLOOR > 0.0 && SettingsHome.SHRINK_FLOOR < 1.0)
    }
}
