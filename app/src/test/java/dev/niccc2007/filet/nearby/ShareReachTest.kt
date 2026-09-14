package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a browser on the network is allowed to walk into.
 *
 * Two opposite ways to be wrong, and the code had the first one:
 *
 * - **Too tight.** A folder the owner deliberately shared is listed and then refuses to open.
 *   Invisible from the app, and from a browser it looks like the feature does not work.
 * - **Too loose.** The upload folder becomes readable, so anyone who can drop a file in can
 *   also read back everything anybody else dropped in.
 *
 * Every test below is one of those two, which is why the refusals are asserted as carefully as
 * the permissions.
 */
class ShareReachTest {

    private val folder = VPath.of("local", "/storage/emulated/0/Filet/Shared")
    private val quarantine = VPath.of("local", "/storage/emulated/0/Filet/Received")
    private fun p(s: String) = VPath.of("local", s)

    private fun reach(path: VPath, shared: Collection<VPath> = emptyList()) =
        ShareReach.isReachable(path, folder, quarantine, shared)

    // ── the bug he reported ──

    @Test fun a_deliberately_shared_quarantine_folder_opens() {
        // His case exactly. He shared Filet/Received from the app; the root listing offered it
        // and `/api/list?d=<its token>` answered {"error":"gone"}, which the page turns into a
        // bounce back to the share root. From the far end that is "folders are not explorable".
        assertTrue(reach(quarantine, shared = listOf(quarantine)))
    }

    @Test fun the_contents_of_a_deliberately_shared_quarantine_folder_open_too() {
        // Being able to open the folder and not walk into it would be the same bug one level
        // down, and is what a half fix looks like.
        assertTrue(reach(p("/storage/emulated/0/Filet/Received/photo.jpg"), shared = listOf(quarantine)))
        assertTrue(reach(p("/storage/emulated/0/Filet/Received/sub/deep.txt"), shared = listOf(quarantine)))
    }

    @Test fun an_explicit_share_is_checked_before_the_quarantine_refusal() {
        // The ordering IS the fix. With the refusal first it overrides the owner's decision;
        // with it second it is only a default. Asserted as an ordering rather than trusted to
        // the shape of the code.
        assertFalse("not shared: refused", reach(quarantine))
        assertTrue("shared: allowed", reach(quarantine, shared = listOf(quarantine)))
    }

    // ── the refusal still has to hold ──

    @Test fun the_upload_folder_is_not_readable_by_default() {
        // Somebody who can upload must not be able to read back what everyone else uploaded.
        assertFalse(reach(quarantine))
        assertFalse(reach(p("/storage/emulated/0/Filet/Received/someone-elses.pdf")))
    }

    @Test fun nothing_under_the_upload_folder_is_readable_by_default_either() {
        // The old check compared for equality only, so a file INSIDE the quarantine was never
        // refused by that clause at all - it just happened not to be reachable another way.
        assertFalse(reach(p("/storage/emulated/0/Filet/Received/a/b/c.txt")))
    }

    @Test fun sharing_one_file_out_of_the_upload_folder_does_not_open_the_folder() {
        // The narrowest possible grant stays narrow. Sharing one received photo must not make
        // its neighbours readable.
        val one = p("/storage/emulated/0/Filet/Received/mine.jpg")
        assertTrue(reach(one, shared = listOf(one)))
        assertFalse(reach(quarantine, shared = listOf(one)))
        assertFalse(reach(p("/storage/emulated/0/Filet/Received/theirs.jpg"), shared = listOf(one)))
    }

    @Test fun an_unshared_folder_elsewhere_on_the_device_is_not_reachable() {
        assertFalse(reach(p("/storage/emulated/0/DCIM")))
        assertFalse(reach(p("/storage/emulated/0/Android/data/com.whatsapp")))
        assertFalse(reach(p("/")))
    }

    @Test fun a_sibling_whose_name_merely_starts_the_same_is_not_inside_the_share() {
        // `contains` has to compare path SEGMENTS. `/Filet/SharedSecrets` starts with the same
        // characters as `/Filet/Shared` and is a different folder.
        assertFalse(reach(p("/storage/emulated/0/Filet/SharedSecrets/keys.txt")))
    }

    @Test fun a_different_scheme_is_never_inside_the_local_share() {
        // A remote volume that happens to use the same spelling is a different filesystem.
        assertFalse(reach(VPath.of("smb", "/storage/emulated/0/Filet/Shared/x.txt")))
    }

    // ── the ordinary cases, which were already right and must stay right ──

    @Test fun the_shared_folder_and_everything_in_it_is_reachable() {
        assertTrue(reach(folder))
        assertTrue(reach(p("/storage/emulated/0/Filet/Shared/probe")))
        assertTrue(reach(p("/storage/emulated/0/Filet/Shared/probe/inner/a.txt")))
    }

    @Test fun a_shared_folder_elsewhere_opens_and_so_does_its_whole_tree() {
        val docs = p("/storage/emulated/0/Documents")
        assertTrue(reach(docs, shared = listOf(docs)))
        assertTrue(reach(p("/storage/emulated/0/Documents/tax/2026/receipt.pdf"), shared = listOf(docs)))
    }

    @Test fun revoking_a_share_closes_everything_under_it_at_once() {
        // Tokens are minted per listing and outlive a revoke, so this check is what actually
        // ends access. With the set empty, a token from a minute ago reaches nothing.
        val docs = p("/storage/emulated/0/Documents")
        assertTrue(reach(p("/storage/emulated/0/Documents/tax/receipt.pdf"), shared = listOf(docs)))
        assertFalse(reach(p("/storage/emulated/0/Documents/tax/receipt.pdf"), shared = emptyList()))
    }

    @Test fun a_shared_file_is_reachable_and_its_neighbours_are_not() {
        val one = p("/storage/emulated/0/DCIM/Camera/IMG_0001.jpg")
        assertTrue(reach(one, shared = listOf(one)))
        assertFalse(reach(p("/storage/emulated/0/DCIM/Camera/IMG_0002.jpg"), shared = listOf(one)))
        assertFalse(reach(p("/storage/emulated/0/DCIM/Camera"), shared = listOf(one)))
    }

    @Test fun a_parent_of_a_shared_folder_is_not_reachable_through_it() {
        // Sharing a child never opens the way upward, which is the traversal this whole design
        // exists to make unrepresentable.
        val docs = p("/storage/emulated/0/Documents")
        assertFalse(reach(p("/storage/emulated/0"), shared = listOf(docs)))
        assertFalse(reach(p("/storage/emulated/0/Documents/.."), shared = listOf(docs)))
    }
}
