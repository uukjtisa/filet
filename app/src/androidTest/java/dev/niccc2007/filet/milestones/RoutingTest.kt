package dev.niccc2007.filet.milestones

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.handlers.HandlerRegistry
import dev.niccc2007.filet.handlers.mimeOf
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2's exit criterion.
 *
 * > Single/double tap routing resolves through the handler registry, and another app can open
 * > a file in Filet via an inbound intent (PLAN.md §8, M2).
 *
 * Two halves, both checkable here:
 *
 * 1. **Routing** - the registry decides what a single tap opens, a chooser's "Always" writes
 *    to it, and the next tap obeys. Pure state, so it is asserted directly.
 * 2. **Inbound** - the manifest filters are resolved by the *platform's* PackageManager
 *    against real Intents. Asking the system whether it would route a VIEW to us is the only
 *    honest way to check a manifest filter; reading the XML back would only prove the XML.
 */
@RunWith(AndroidJUnit4::class)
class RoutingTest {

    private lateinit var registry: HandlerRegistry
    private val touched = mutableListOf<String>()

    private fun node(name: String, isDir: Boolean = false) =
        VNode(VPath.of("local", "/storage/emulated/0/Download/$name"), isDir, 10L, 0L)

    @Before fun setUp() {
        registry = HandlerRegistry(Prefs(ApplicationProvider.getApplicationContext()))
    }

    @After fun tearDown() {
        touched.forEach { registry.clearDefault(it) }
    }

    // ── single tap ──

    @Test fun a_single_tap_routes_by_kind() {
        assertEquals(HandlerId.TEXT, registry.handlerFor(node("notes.kt")))
        assertEquals(HandlerId.IMAGE, registry.handlerFor(node("photo.jpg")))
        assertEquals(HandlerId.MEDIA, registry.handlerFor(node("song.mp3")))
        assertEquals(HandlerId.MEDIA, registry.handlerFor(node("clip.mp4")))
        assertEquals(HandlerId.APK, registry.handlerFor(node("app.apk")))
        assertEquals(HandlerId.ARCHIVE, registry.handlerFor(node("bundle.zip")))
        assertEquals(HandlerId.HEX, registry.handlerFor(node("firmware.bin")))
    }

    /** "Always" in the chooser is the user editing the registry, and it must stick. */
    @Test fun always_in_the_chooser_changes_what_the_next_single_tap_does() {
        touched += "jpg"
        assertEquals(HandlerId.IMAGE, registry.handlerFor(node("photo.jpg")))
        registry.setDefault("jpg", HandlerId.HEX)
        assertEquals(HandlerId.HEX, registry.handlerFor(node("photo.jpg")))

        // And it is persisted, not just held in memory.
        val fresh = HandlerRegistry(Prefs(ApplicationProvider.getApplicationContext()))
        assertEquals(HandlerId.HEX, fresh.handlerFor(node("photo.jpg")))

        registry.clearDefault("jpg")
        assertEquals(HandlerId.IMAGE, registry.handlerFor(node("photo.jpg")))
    }

    /** The double-tap sheet must offer more than one real answer, or it is decoration. */
    @Test fun the_chooser_offers_every_handler_that_can_open_the_file() {
        val candidates = registry.candidatesFor(node("photo.jpg"))
        assertTrue("$candidates", candidates.contains(HandlerId.IMAGE))
        assertTrue("a hex viewer can open anything: $candidates", candidates.contains(HandlerId.HEX))
        assertTrue("handing off to another app is always an option: $candidates", candidates.contains(HandlerId.EXTERNAL))
        assertTrue("the chooser needs choices: $candidates", candidates.size >= 3)
    }

    @Test fun outgoing_intents_carry_a_sensible_mime_type() {
        assertEquals("image/jpeg", mimeOf(node("photo.jpg")))
        assertEquals("application/vnd.android.package-archive", mimeOf(node("app.apk")))
        assertTrue(mimeOf(node("unknown.zzz")).isNotEmpty())
    }

    // ── inbound: what the platform thinks our manifest says ──

    private fun resolves(intent: Intent): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return matches.any { it.activityInfo.packageName == context.packageName }
    }

    @Test fun another_app_can_open_a_file_in_filet() {
        val view = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(
                android.net.Uri.parse("content://media/external/file/1"),
                "application/octet-stream",
            )
        }
        assertTrue("Filet is not in the Open-with list for a content:// file", resolves(view))
    }

    @Test fun filet_is_a_share_sheet_target() {
        val send = Intent(Intent.ACTION_SEND).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            type = "image/jpeg"
        }
        assertTrue("Filet is not a share target", resolves(send))

        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            type = "*/*"
        }
        assertTrue("Filet does not accept multiple shared files", resolves(multi))
    }

    @Test fun filet_is_a_valid_answer_to_pick_a_file() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        assertTrue("Filet does not answer GET_CONTENT", resolves(get))
    }

    /**
     * The shortcut router must be reachable by the launcher - an exported activity with no
     * filter still has to resolve by component, or every pinned shortcut is dead on arrival.
     */
    @Test fun the_shortcut_router_is_launchable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().setClassName(
            context.packageName,
            "dev.niccc2007.filet.shortcuts.ShortcutRouterActivity",
        )
        assertTrue(
            "the shortcut router does not resolve",
            context.packageManager.resolveActivity(intent, 0) != null,
        )
    }
}
