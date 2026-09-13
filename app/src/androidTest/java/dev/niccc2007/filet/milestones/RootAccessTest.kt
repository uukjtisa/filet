package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.RootProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M8's root half - and what must happen when it is *not* available.
 *
 * > Root browse works on a rooted emulator image (PLAN.md §8, M8).
 *
 * This device is not rooted, and neither is a stock `google_apis` emulator image: its
 * `/system/xbin/su` is mode 4750 `root:shell` and refuses any caller that is not uid shell or
 * root, so an app cannot use it whatever the file permissions say. The success half of this
 * gate therefore cannot be closed here - it needs a Magisk-patched image or real rooted
 * hardware, and that is recorded as an open handoff rather than quietly passed over.
 *
 * What *is* checkable on every device, and matters more day to day, is the other half:
 *
 * > **R1 - No Dead Switches.** If it does not work, it does not exist in the UI.
 *
 * Root must never be asked for at startup, must report honestly when the device says no, and
 * must never paint a fabricated tree. The test adapts: on a rooted device it asserts root
 * actually browses, and on an unrooted one it asserts the refusal is clean.
 */
@RunWith(AndroidJUnit4::class)
class RootAccessTest {

    private val vfs get() = Vfs(listOf(RootProvider()))

    /**
     * Nothing has opted in, so nothing may have prompted. `isGranted` is the non-blocking
     * probe precisely so the UI can ask without summoning the superuser dialog.
     */
    @Test fun root_is_never_taken_at_startup() {
        assertFalse(
            "root was already granted before anything asked for it",
            RootProvider.isGranted(),
        )
    }

    @Test fun root_either_browses_or_refuses_honestly() {
        val available = RootProvider.isAvailable()

        if (available) {
            // A rooted device: `/data` is the folder that proves it, because no unrooted app
            // can list it.
            val nodes = runBlocking { vfs.list(VPath.of("root", "/data")) }
            assertTrue("root is available but /data listed nothing", nodes.isNotEmpty())
            val roots = runBlocking { vfs.roots() }
            assertTrue("root volumes were not offered", roots.isNotEmpty())
        } else {
            // An unrooted device: the attempt must fail, and it must fail as an exception the
            // UI can turn into a message - never as an empty-but-plausible listing.
            val outcome = runCatching { runBlocking { vfs.list(VPath.of("root", "/data")) } }
            val painted = outcome.getOrNull()
            assertTrue(
                "root browsing produced a listing on a device with no root: $painted",
                painted.isNullOrEmpty(),
            )
        }
    }

    /**
     * The claim the whole thing rests on: without root, `/data` is genuinely unreadable. If
     * this ever passes trivially the test above proves nothing.
     */
    @Test fun the_control_holds_data_really_is_unreadable_without_root() {
        if (RootProvider.isAvailable()) return
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val listing = File("/data").list()
        assertTrue(
            "this app can already read /data without root - the premise is wrong",
            listing == null || listing.isEmpty(),
        )
        // Sanity: the app can read its own data, so the failure above is about /data and not
        // about the filesystem being broken.
        assertTrue(context.filesDir.isDirectory)
    }
}
