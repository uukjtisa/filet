package dev.niccc2007.filet.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a home-screen shortcut goes.
 *
 * The bug this exists to stop ever recurring: `MainActivity` read the file-target extra and
 * nothing else, so an action shortcut arrived complete and correct and was silently dropped.
 * Search, Start sharing, Index now and Recent were dead icons for three rounds, and Nic
 * reported it twice before the cause was found - because the icon looked right, the app did
 * launch, and nothing anywhere said no.
 */
class ShortcutRouteTest {

    @Test fun a_file_shortcut_opens_its_target() {
        assertEquals(
            ShortcutRoute.OpenTarget("local:///storage/emulated/0/a.pdf", null),
            shortcutRoute(target = "local:///storage/emulated/0/a.pdf", action = null, handler = null),
        )
    }

    @Test fun a_folder_shortcut_is_the_same_route_and_the_view_model_stats_it() {
        // Deliberately not decided here. Whether a path is a file or a folder is a question
        // for the filesystem, and answering it from the name is the bug round 6 fixed in the
        // bookmark list.
        val route = shortcutRoute("local:///storage/emulated/0/Download", null, null)
        assertTrue(route is ShortcutRoute.OpenTarget)
    }

    @Test fun a_handler_override_rides_along() {
        assertEquals(
            ShortcutRoute.OpenTarget("local:///a.txt", "HEX"),
            shortcutRoute("local:///a.txt", null, "HEX"),
        )
    }

    @Test fun an_action_shortcut_runs_its_action() {
        // The line that was missing.
        assertEquals(ShortcutRoute.RunAction("SEARCH"), shortcutRoute(null, "SEARCH", null))
        assertEquals(ShortcutRoute.RunAction("SHARE_NEARBY"), shortcutRoute(null, "SHARE_NEARBY", null))
        assertEquals(ShortcutRoute.RunAction("INDEX_NOW"), shortcutRoute(null, "INDEX_NOW", null))
        assertEquals(ShortcutRoute.RunAction("RECENT"), shortcutRoute(null, "RECENT", null))
    }

    @Test fun every_action_the_ui_offers_is_one_the_router_carries() {
        // A shortcut offered in the app for an action nothing dispatches is another dead
        // icon, which is the whole class of bug this round is closing.
        for (action in AppAction.entries) {
            val route = shortcutRoute(null, action.name, null)
            assertEquals(ShortcutRoute.RunAction(action.name), route)
            assertEquals(action, AppAction.parse((route as ShortcutRoute.RunAction).action))
        }
    }

    @Test fun a_script_shortcut_carries_its_id() {
        val route = shortcutRoute(null, "script:abc123", null)
        assertEquals(ShortcutRoute.RunAction("script:abc123"), route)
        assertEquals("abc123", scriptIdOrNull("script:abc123"))
        assertNull("an app action is not a script", scriptIdOrNull("SEARCH"))
        assertNull("a script with no id is not a script", scriptIdOrNull("script:"))
    }

    @Test fun a_plain_launch_routes_nowhere_and_that_is_correct() {
        assertEquals(ShortcutRoute.Nothing, shortcutRoute(null, null, null))
    }

    @Test fun a_blank_extra_is_the_same_as_no_extra() {
        // An extra put with an empty string is what a half-built intent looks like. Routing on
        // it would navigate a pane to nowhere and look like a crash that did not happen.
        assertEquals(ShortcutRoute.Nothing, shortcutRoute("", "", null))
        assertEquals(ShortcutRoute.Nothing, shortcutRoute("   ", null, null))
        assertEquals(ShortcutRoute.RunAction("SEARCH"), shortcutRoute("", "SEARCH", null))
    }

    @Test fun a_blank_handler_means_follow_the_default_rather_than_force_nothing() {
        assertEquals(ShortcutRoute.OpenTarget("local:///a.txt", null), shortcutRoute("local:///a.txt", null, ""))
    }

    @Test fun a_target_wins_over_an_action_when_both_somehow_arrive() {
        // An intent carrying both is a bug upstream. Picking the more specific instruction is
        // the behaviour that degrades least.
        assertEquals(
            ShortcutRoute.OpenTarget("local:///a.txt", null),
            shortcutRoute("local:///a.txt", "SEARCH", null),
        )
    }

    @Test fun an_action_this_build_no_longer_has_parses_to_null_rather_than_throwing() {
        // A launcher icon outlives the version that made it, and a crash on tap is the worst
        // possible way to say "that was renamed".
        assertNull(AppAction.parse("SOMETHING_REMOVED"))
        assertNull(AppAction.parse(null))
        assertNull(AppAction.parse(""))
    }
}
