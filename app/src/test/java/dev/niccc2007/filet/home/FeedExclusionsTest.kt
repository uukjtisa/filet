package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedExclusionsTest {

    private val none = emptySet<String>()

    @Test fun `an excluded folder takes everything beneath it`() {
        // The whole point. `.thumbnails` is not one entry to hide, it is ten thousand - and a
        // rule that hid the folder and kept its contents would be the most annoying possible
        // outcome, because the reason for the rule would still be flooding the list.
        val folders = setOf("/storage/emulated/0/Android")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Android", folders, none))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Android/data", folders, none))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Android/data/a/b/c.txt", folders, none))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/photo.jpg", folders, none))
    }

    @Test fun `a neighbour with a longer name is not swept up with it`() {
        // `startsWith` on its own puts /Downloads2 inside /Downloads. The neighbour is usually
        // the folder somebody actually wanted, so this reads as the feature being broken
        // rather than as a rule being too broad.
        val folders = setOf("/storage/emulated/0/Downloads")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Downloads/a.txt", folders, none))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/Downloads2/a.txt", folders, none))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DownloadsOld", folders, none))
    }

    @Test fun `a prefix on a folder excludes its whole subtree`() {
        // Checked per SEGMENT, which is what gets the subtree rule for free: a file deep
        // inside `.thumbnails` carries `.thumbnails` as one of its segments.
        val prefixes = setOf(".*")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails", none, prefixes))
        assertFalse(
            FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails/deep/1.jpg", none, prefixes),
        )
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/photo.jpg", none, prefixes))
    }

    @Test fun `a prefix matches a file by its own name too`() {
        val prefixes = setOf("log_*")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/log_2026.txt", none, prefixes))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/catalog_2026.txt", none, prefixes))
    }

    @Test fun `a prefix is matched at the start of a segment and nowhere else`() {
        // `catalog_` contains `log_`. Matching anywhere in the name would exclude it, and
        // somebody filtering their logs would silently lose their catalogue.
        assertTrue(FeedExclusions.allows("/a/catalog_1.txt", none, setOf("log_*")))
        assertTrue(FeedExclusions.allows("/a/my.notes/x.txt", none, setOf("notes*")))
        assertFalse(FeedExclusions.allows("/a/notes.txt", none, setOf("notes*")))
    }

    @Test fun `case is kept because the filesystem keeps it`() {
        // Folding would exclude more than was asked for, which is the failure this file exists
        // to avoid - and on this filesystem Log_ and log_ really are different folders.
        assertFalse(FeedExclusions.allows("/a/log_1.txt", none, setOf("log_*")))
        assertTrue(FeedExclusions.allows("/a/Log_1.txt", none, setOf("log_*")))
    }

    @Test fun `nothing configured excludes nothing`() {
        assertTrue(FeedExclusions.allows("/a/b/c.txt", none, none))
        // An empty prefix would match every segment and empty the whole feed.
        assertTrue(FeedExclusions.allows("/a/b/c.txt", none, setOf("")))
        assertTrue(FeedExclusions.allows("/a/b/c.txt", setOf(""), none))
    }

    @Test fun `separators are normalised before anything is compared`() {
        val folders = setOf("/storage/emulated/0/Android")
        assertFalse(FeedExclusions.allows("""\storage\emulated\0\Android\data\x""", folders, none))
        // A trailing separator must not stop a folder matching itself.
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Android/", folders, none))
        assertFalse(FeedExclusions.allows("/a/b", setOf("/a/b/"), none))
    }

    @Test fun `a typed list is read as separate rules`() {
        assertEquals(
            linkedSetOf(".", "log_", "tmp_"),
            FeedExclusions.parse(" . , log_ \n tmp_ \n\n "),
        )
        assertEquals(linkedSetOf("a"), FeedExclusions.parse("a, a\na"))
        assertEquals(emptySet<String>(), FeedExclusions.parse("  \n , "))
    }

    @Test fun `what is typed survives a round trip`() {
        val typed = FeedExclusions.parse(".\nlog_")
        assertEquals(typed, FeedExclusions.parse(FeedExclusions.format(typed)))
    }

    @Test fun `the suggestions are offered rather than applied`() {
        // Not a default value: a feed that quietly hid things from the first launch would be
        // impossible to tell apart from a feed that was not working.
        assertTrue(FeedExclusions.SUGGESTED.isNotEmpty())
        assertTrue(FeedExclusions.SUGGESTED.none { it.isEmpty() })
        assertTrue(FeedExclusions.allows("/a/.hidden/x", none, none))
    }

    @Test fun `a fresh install hides dot-files and the files Filet writes into its own folder`() {
        val d = FeedExclusions.DEFAULT_RULES
        val root = "/storage/emulated/0/Filet"
        val own = FeedExclusions.ownPaths(root)
        assertFalse(FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails/1.jpg", own, d))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Pictures/.trashed-1699-a.jpg", own, d))
        for (name in listOf("filet.json", "playlists.json", "bookmarks.json", "About this folder.txt")) {
            assertFalse(name, FeedExclusions.allows("$root/$name", own, d))
        }
        // Real files keep showing - including a file of the person's own with the same name as
        // one of Filet's, which a name prefix used to hide wherever it was.
        assertTrue(FeedExclusions.allows("/storage/emulated/0/Download/playlists.json", own, d))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/Download/my playlists.json", own, d))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/Camera/IMG_0412.jpg", own, d))
        // And every default is one the settings box can show and the chips can offer back.
        assertEquals(d, FeedExclusions.parse(FeedExclusions.format(d)))
    }

    @Test fun `a hidden file is hidden by its own path, and its neighbours are not`() {
        val hidden = setOf("/storage/emulated/0/Download/report.pdf")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Download/report.pdf", hidden, none))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/Download/report.pdf.bak", hidden, none))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/Download/other.pdf", hidden, none))
    }

    @Test fun `Filet's own folders are excluded but Shared and Received are not`() {
        // The one case where a default value is right: these are the APP's folders, not the
        // user's, and a file manager reporting its own cache writes as "new files on your
        // phone" is the app talking about itself.
        //
        // Shared and Received are deliberately absent from the list. They hold real files that
        // arrived from somewhere else, which is exactly what New files is for - excluding them
        // would hide the most useful rows in the tab.
        val root = "/storage/emulated/0/Filet"
        val own = FeedExclusions.ownPaths(root)
        assertFalse(FeedExclusions.allows("$root/.cache/Beat maps/a.beat", own, none))
        assertFalse(FeedExclusions.allows("$root/Logs/run.log", own, none))
        assertFalse(FeedExclusions.allows("$root/Settings/settings-1/filet.xml", own, none))
        assertFalse(FeedExclusions.allows("$root/Memory/filet-memory.db", own, none))
        assertTrue(FeedExclusions.allows("$root/Shared/holiday.jpg", own, none))
        assertTrue(FeedExclusions.allows("$root/Received/lecture.pdf", own, none))
        assertTrue(FeedExclusions.allows("$root/Extracted-Apks/app.apk", own, none))
        // The root files are the files alone: nothing that merely starts with their names.
        assertFalse(FeedExclusions.allows("$root/filet.json", own, none))
        assertTrue(FeedExclusions.allows("$root/filet.json.bak", own, none))
        // And nothing outside Filet is touched by them.
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/photo.jpg", own, none))
    }

    // ── the four kinds of line ──

    @Test fun `an unmarked line is the whole name and nothing longer`() {
        val r = setOf(".thumbnails")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails", none, r))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails/1.jpg", none, r))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/.thumbnails2/1.jpg", none, r))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/DCIM/my.thumbnails", none, r))
    }

    @Test fun `a leading star matches the end of a name, file or folder`() {
        val r = setOf("*.ft_lnk")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Received.ft_lnk", none, r))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/odd.ft_lnk/inside.txt", none, r))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/notes.ft_lnk.txt", none, r))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/ft_lnk", none, r))
    }

    @Test fun `stars at both ends match a piece of a name`() {
        val r = setOf("*cache*")
        assertFalse(FeedExclusions.allows("/storage/emulated/0/my_cache_dir/a.jpg", none, r))
        assertFalse(FeedExclusions.allows("/storage/emulated/0/cache", none, r))
        assertTrue(FeedExclusions.allows("/storage/emulated/0/cash/a.jpg", none, r))
    }

    @Test fun `a line of stars names nothing rather than everything`() {
        // `*` read as a piece of nothing would match every name and empty the whole feed.
        assertTrue(FeedExclusions.allows("/a/b.txt", none, setOf("*")))
        assertTrue(FeedExclusions.allows("/a/b.txt", none, setOf("**", " * ")))
        assertEquals(null, FeedExclusions.rule("***"))
    }

    @Test fun `each line is read as the kind its stars say`() {
        assertEquals(FeedExclusions.Rule("logs", FeedExclusions.Match.NAME), FeedExclusions.rule(" logs "))
        assertEquals(FeedExclusions.Rule("log_", FeedExclusions.Match.START), FeedExclusions.rule("log_*"))
        assertEquals(FeedExclusions.Rule(".ft_lnk", FeedExclusions.Match.END), FeedExclusions.rule("*.ft_lnk"))
        assertEquals(FeedExclusions.Rule("cache", FeedExclusions.Match.PIECE), FeedExclusions.rule("*cache*"))
    }

    // ── a list stored before the markers ──

    @Test fun `a list of starts reads as the same starts, plus shortcut files`() {
        val old = linkedSetOf(".", "log_", "logs")
        val now = FeedExclusions.fromPrefixes(old)
        assertEquals(linkedSetOf(".*", "log_*", "logs*", FeedExclusions.FILET_LINKS), now)
        // Hides exactly what it hid before: `logs` was a start, so `logs2024` stays hidden.
        for (path in listOf("/a/.hidden", "/a/log_1.txt", "/a/logs2024/x.txt")) {
            assertFalse(path, FeedExclusions.allows(path, none, old.map { "$it*" }.toSet()))
            assertFalse(path, FeedExclusions.allows(path, none, now))
        }
        assertTrue(FeedExclusions.allows("/a/catalog_1.txt", none, now))
    }

    @Test fun `a fresh install hides shortcut files`() {
        assertFalse(FeedExclusions.allows("/storage/emulated/0/Received.ft_lnk", none, FeedExclusions.DEFAULT_RULES))
    }

    @Test fun `every suggestion is a line that names something`() {
        for (s in FeedExclusions.SUGGESTED) assertTrue(s, FeedExclusions.rule(s) != null)
    }
}
