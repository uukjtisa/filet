package dev.niccc2007.filet.playback

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playlist is a kept queue, and Favourites is one of them.
 *
 * Every rule here is one that silently does the wrong thing rather than failing: a
 * de-duplication that moves the copy already in the list, a reorder that renumbers off by
 * one, a built-in list that a delete button quietly removed.
 */
class PlaylistsTest {

    private fun p(name: String, dir: String = "/Music") = VPath.of("local", "$dir/$name")

    private fun list(vararg names: String) =
        Playlist("id", "Mix", names.map { p(it) })

    // ── what a playlist does ──────────────────────────────────────────────────

    @Test
    fun `adding puts it at the end`() {
        val l = list("a.mp3").plus(p("b.mp3"))
        assertEquals(listOf(p("a.mp3"), p("b.mp3")), l.tracks)
    }

    @Test
    fun `adding something already there changes nothing at all`() {
        // Not "deduplicated afterwards": the copy already in the list must not MOVE, which
        // is what an add-then-distinct would do to it.
        val l = list("a.mp3", "b.mp3").plus(p("a.mp3"))
        assertEquals(listOf(p("a.mp3"), p("b.mp3")), l.tracks)
    }

    @Test
    fun `adding several keeps their order and skips the ones already here`() {
        val l = list("a.mp3").plusAll(listOf(p("b.mp3"), p("a.mp3"), p("c.mp3")))
        assertEquals(listOf(p("a.mp3"), p("b.mp3"), p("c.mp3")), l.tracks)
    }

    @Test
    fun `the same name in two folders is two different tracks`() {
        // The case a playlist exists for. Matching on the name would merge them.
        val l = list("a.mp3").plus(p("a.mp3", "/Download"))
        assertEquals(2, l.size)
    }

    @Test
    fun `moving a track lands it exactly where it was dropped`() {
        val l = list("a", "b", "c", "d").move(0, 2)
        assertEquals(listOf(p("b"), p("c"), p("a"), p("d")), l.tracks)
    }

    @Test
    fun `moving backwards works too`() {
        val l = list("a", "b", "c", "d").move(3, 1)
        assertEquals(listOf(p("a"), p("d"), p("b"), p("c")), l.tracks)
    }

    @Test
    fun `a move that lands off the end changes nothing`() {
        // A drag can end anywhere, including past the last row and outside the list.
        val l = list("a", "b")
        assertEquals(l, l.move(0, 9))
        assertEquals(l, l.move(-1, 0))
        assertEquals(l, l.move(0, 0))
    }

    @Test
    fun `removing takes out every copy of that path and nothing else`() {
        val l = list("a", "b", "c").minus(p("b"))
        assertEquals(listOf(p("a"), p("c")), l.tracks)
    }

    // ── favourites ────────────────────────────────────────────────────────────

    @Test
    fun `favourites exists even when nothing was ever saved`() {
        // The heart's destination. A heart whose list does not exist yet is a control that
        // works the second time it is pressed.
        val lists = Playlists.ensureFavourites(emptyList())
        assertEquals(1, lists.size)
        assertEquals(Playlists.FAVOURITES, lists.first().id)
        assertTrue(lists.first().builtIn)
    }

    @Test
    fun `favourites is first, however it was stored`() {
        val stored = listOf(
            Playlist("pl-1", "Mix", emptyList()),
            Playlist(Playlists.FAVOURITES, "whatever", listOf(p("a"))),
        )
        val lists = Playlists.ensureFavourites(stored)
        assertEquals(Playlists.FAVOURITES, lists.first().id)
        assertEquals(2, lists.size)
        // And it keeps its tracks while having its name and its built-in flag put right.
        assertEquals(listOf(p("a")), lists.first().tracks)
        assertEquals(Playlists.FAVOURITES_NAME, lists.first().name)
        assertTrue(lists.first().builtIn)
    }

    @Test
    fun `a stored favourites that claims not to be built in is corrected`() {
        // Otherwise a hand-edited or migrated blob gets a delete button on the one list that
        // must not have one.
        val stored = listOf(Playlist(Playlists.FAVOURITES, "Faves", emptyList(), builtIn = false))
        assertTrue(Playlists.ensureFavourites(stored).first().builtIn)
    }

    @Test
    fun `there is never a second favourites`() {
        val stored = listOf(
            Playlist(Playlists.FAVOURITES, "A", listOf(p("a"))),
            Playlist("pl-1", "Mix", emptyList()),
        )
        val lists = Playlists.ensureFavourites(stored)
        assertEquals(1, lists.count { it.id == Playlists.FAVOURITES })
    }

    // ── names ─────────────────────────────────────────────────────────────────

    @Test
    fun `a blank name becomes something rather than nothing`() {
        assertEquals("Playlist", Playlists.cleanName("   ", emptyList()))
        assertEquals("Playlist", Playlists.cleanName("", emptyList()))
    }

    @Test
    fun `a name already taken gets a number`() {
        // The dialogue that adds a track shows names, not ids, so two lists called "Mix" is
        // a choice nobody can make.
        assertEquals("Mix 2", Playlists.cleanName("Mix", listOf("Mix")))
        assertEquals("Mix 3", Playlists.cleanName("Mix", listOf("Mix", "Mix 2")))
    }

    @Test
    fun `a name is trimmed and capped`() {
        assertEquals("Mix", Playlists.cleanName("  Mix  ", emptyList()))
        assertEquals(60, Playlists.cleanName("x".repeat(200), emptyList()).length)
    }

    @Test
    fun `a free name is left exactly as it is`() {
        assertEquals("Driving", Playlists.cleanName("Driving", listOf("Mix", "Gym")))
    }

    // ── a video is only ever in a playlist on purpose ─────────────────────────

    @Test
    fun `a playlist holds whatever was put in it, video included`() {
        // Asked for directly. A playlist is the EXPLICIT case: the automatic folder queue
        // still takes only audio - see `audioQueue` - so a tutorial in the same folder as an
        // album never joins a queue by itself, and a music video does when it is chosen.
        val l = list("song.mp3").plus(p("clip.mp4"))
        assertEquals(2, l.size)
        assertTrue(l.contains(p("clip.mp4")))
    }

    @Test
    fun `contains is about the exact path, not the name`() {
        val l = list("a.mp3")
        assertTrue(l.contains(p("a.mp3")))
        assertFalse(l.contains(p("a.mp3", "/Download")))
    }
}
