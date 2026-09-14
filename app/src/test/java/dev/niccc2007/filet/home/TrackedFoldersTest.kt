package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracked folders, and the two things that could go wrong quietly.
 *
 * Subfolder tracking changed how the list is stored, and a list that silently empties itself
 * on upgrade would look exactly like "I never tracked anything". So the read path is tested
 * against both shapes directly, without needing a `Prefs` and therefore without Android.
 */
class TrackedFoldersTest {

    private fun p(path: String) = VPath.of("local", path)

    /** The parse the store does, extracted so it can be checked without a `SharedPreferences`. */
    private fun parse(raw: String): List<TrackedFolder> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.get(i)
            runCatching {
                if (entry is JSONObject) {
                    TrackedFolder(VPath.parse(entry.getString("path")), entry.optBoolean("deep", false))
                } else {
                    TrackedFolder(VPath.parse(entry.toString()), recursive = false)
                }
            }.getOrNull()
        }
    }.getOrElse { emptyList() }

    private fun write(folders: List<TrackedFolder>): String {
        val arr = JSONArray()
        folders.forEach { arr.put(JSONObject().put("path", it.path.toString()).put("deep", it.recursive)) }
        return arr.toString()
    }

    @Test fun a_list_written_before_subfolders_existed_still_loads() {
        // Bare strings, the old shape. Losing these on upgrade reads as "I never tracked
        // anything" and there is nothing on screen that would say otherwise.
        val old = JSONArray()
            .put("local:///storage/emulated/0/Download")
            .put("local:///storage/emulated/0/DCIM")
            .toString()
        val loaded = parse(old)
        assertEquals(2, loaded.size)
        assertEquals(p("/storage/emulated/0/Download"), loaded[0].path)
        assertFalse("an old entry is not silently made recursive", loaded[0].recursive)
    }

    @Test fun the_new_shape_round_trips_with_its_flag() {
        val folders = listOf(
            TrackedFolder(p("/storage/emulated/0/Download"), recursive = true),
            TrackedFolder(p("/storage/emulated/0/DCIM"), recursive = false),
        )
        assertEquals(folders, parse(write(folders)))
    }

    @Test fun a_mixed_list_loads_both_ways_at_once() {
        // What the file looks like the moment after an upgrade adds one new entry.
        val mixed = JSONArray()
            .put("local:///storage/emulated/0/Download")
            .put(JSONObject().put("path", "local:///storage/emulated/0/Music").put("deep", true))
            .toString()
        val loaded = parse(mixed)
        assertEquals(2, loaded.size)
        assertFalse(loaded[0].recursive)
        assertTrue(loaded[1].recursive)
    }

    @Test fun one_unparseable_entry_does_not_take_the_rest_with_it() {
        val raw = JSONArray()
            .put("not a vpath at all")
            .put("local:///storage/emulated/0/Download")
            .toString()
        assertEquals(listOf(p("/storage/emulated/0/Download")), parse(raw).map { it.path })
    }

    @Test fun a_corrupt_blob_loses_the_list_and_never_the_app() {
        assertEquals(emptyList<TrackedFolder>(), parse("{{{not json"))
        assertEquals(emptyList<TrackedFolder>(), parse(""))
    }

    @Test fun recursion_is_off_unless_it_was_asked_for() {
        // The cost is real: the tracked list is also the inotify watch list, inotify charges
        // one watch per directory, and the ceiling is around 8192. A default of "everything
        // beneath" would spend that on a single entry.
        assertFalse(TrackedFolder(p("/storage/emulated/0/Download")).recursive)
        assertFalse(parse(JSONArray().put("local:///a").toString())[0].recursive)
        assertFalse(
            parse(JSONArray().put(JSONObject().put("path", "local:///a")).toString())[0].recursive,
        )
    }
}
