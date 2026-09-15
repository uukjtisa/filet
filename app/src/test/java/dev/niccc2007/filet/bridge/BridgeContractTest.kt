package dev.niccc2007.filet.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names that decide whether two builds of Filet can sit on one phone.
 *
 * The manifest declared `dev.niccc2007.filet.permission.BRIDGE` with no `${'$'}{applicationId}`,
 * so the debug and the release build declared the *same* permission. . Found by downloading the real v0.1.0 release through the in-app
 * updater onto a phone that had a debug build on it - which is every phone this is developed on.
 *
 * These are string constants, so a test can only pin the *relationship* between them. That is
 * the part that was wrong: they were equal when they had to differ.
 */
class BridgeContractTest {

    @Test fun the_two_builds_declare_different_permissions() {
        // The whole bug in one assertion.
        assertNotEquals(BridgeContract.PERMISSION_RELEASE, BridgeContract.PERMISSION_DEBUG)
    }

    @Test fun each_permission_is_namespaced_under_its_own_application_id() {
        // Not cosmetic: Android keys the "already defined" check on the exact string, and the
        // application id is the only part guaranteed unique to a build.
        assertTrue(
            BridgeContract.PERMISSION_RELEASE.startsWith("dev.niccc2007.filet."),
        )
        assertTrue(
            BridgeContract.PERMISSION_DEBUG.startsWith("dev.niccc2007.filet.debug."),
        )
    }

    @Test fun the_debug_permission_is_the_release_one_with_the_suffix_in_the_right_place() {
        // The suffix goes on the application id, not on the end of the whole name - AGP
        // substitutes ${'$'}{applicationId}, so this is what the merged manifest will contain.
        assertEquals(
            BridgeContract.PERMISSION_RELEASE.replace(
                "dev.niccc2007.filet.",
                "dev.niccc2007.filet.debug.",
            ),
            BridgeContract.PERMISSION_DEBUG,
        )
    }

    @Test fun a_writer_is_handed_the_permission_belonging_to_the_provider_it_found() {
        // One constant for both was the bug: a writer compiled against the release name would
        // ask for a permission the debug build never declares, and be refused by a build that
        // is sitting right there.
        assertEquals(
            BridgeContract.PERMISSION_DEBUG,
            BridgeContract.permissionFor(BridgeContract.AUTHORITY_DEBUG),
        )
        assertEquals(
            BridgeContract.PERMISSION_RELEASE,
            BridgeContract.permissionFor(BridgeContract.AUTHORITY_RELEASE),
        )
    }

    @Test fun an_unknown_authority_falls_back_to_the_release_permission() {
        // Somebody's fork, or a future flavour. Guessing release is the safe half of the
        // guess: the worst case is a permission denial, not a silent grant.
        assertEquals(
            BridgeContract.PERMISSION_RELEASE,
            BridgeContract.permissionFor("com.example.something.bridge"),
        )
    }

    @Test fun the_authorities_differ_the_same_way_and_both_are_still_enumerated() {
        assertNotEquals(BridgeContract.AUTHORITY_RELEASE, BridgeContract.AUTHORITY_DEBUG)
        assertTrue(BridgeContract.AUTHORITY_RELEASE in BridgeContract.AUTHORITIES)
        assertTrue(BridgeContract.AUTHORITY_DEBUG in BridgeContract.AUTHORITIES)
    }

    @Test fun every_authority_has_a_permission_and_no_two_share_one() {
        val permissions = BridgeContract.AUTHORITIES.map { BridgeContract.permissionFor(it) }
        assertEquals(BridgeContract.AUTHORITIES.size, permissions.toSet().size)
    }
}
